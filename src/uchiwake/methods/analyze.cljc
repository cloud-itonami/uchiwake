(ns uchiwake.methods.analyze
  "uchiwake 内訳 — global product bill-of-materials concentration analyzer.
  1:1 Clojure port of `methods/analyze.py` (ADR-2606081800).

  Reads a kotoba-EDN product graph (:product/* trade items keyed on GTIN, :part/*
  sub-assemblies, :material/* raw inputs, :bom.edge/* parent→child edges, :process.step/*,
  :logistics.leg/*, :design.ref/*, :company.ownership/* subsidiary→parent) and emits:

    1. an AGGREGATE-FIRST product-resilience report (out/intel-report.md) — where the
       world's products concentrate onto a single raw MATERIAL, a single processing
       JURISDICTION, or a single ULTIMATE PARENT (after rolling subsidiaries up via
       GLEIF-style ownership edges), framed toward redundancy + accountability.
    2. the derived concentration datoms (out/product-criticality.kotoba.edn),
       flagged :concentration/derived true — never re-ingested as authoritative fact.

  CONSTITUTIONAL framing (uchiwake G2/G4): a supply-chain RESILIENCE + corporate-power
  TRANSPARENCY map, NEVER a target-list and NEVER a clone/counterfeit recipe.

  House style (lineage): Python ':…' keyword strings stay strings; pure fns; file I/O only
  at #?(:clj) edges. Float formatting mirrors Python exactly — round() = BigDecimal HALF_EVEN
  on the exact double, str(float) = Double/toString (shortest round-trip), and :.0% / :.2f =
  BigDecimal HALF_EVEN (Python's banker's rounding, NOT Java String.format's HALF_UP).

  ORDERING / byte-parity (N.B.): Python builds material→products reachability with an
  unordered `set`, whose iteration order is hash-seed-dependent and therefore NOT reproducible
  across runs. This port pins a DETERMINISTIC discovery order (first BOM-edge appearance, DFS
  over the seed in edge order) so the report + derived datoms are stable and reproducible; the
  byte-parity oracle (tools/parity_oracle.py) feeds analyze.py's own functions that same
  deterministic order so both sides agree byte-for-byte."
  (:require [kotoba.lang.text :as str]
            [uchiwake.methods.uchiwake-edn :as edn]))

;; ── Python-faithful numeric formatting ───────────────────────────────────────
(defn py-round
  "round(x, n) — HALF_EVEN on the EXACT double value (mirrors Python round())."
  [x n]
  (-> (java.math.BigDecimal. (double x))
      (.setScale n java.math.RoundingMode/HALF_EVEN)
      .doubleValue))

(defn py-float-str
  "str(float) — shortest round-trip repr. Clojure's Double/toString matches Python repr for
  the moderate-magnitude rounded shares we emit (0.2727 / 0.3 / 0.6667 …)."
  [x]
  (str (double x)))

(defn fmt-pct0
  "Python f'{x:.0%}' — x×100 rounded HALF_EVEN to 0 places, suffixed '%'."
  [x]
  (str (.setScale (java.math.BigDecimal. (* 100.0 (double x))) 0 java.math.RoundingMode/HALF_EVEN)
       "%"))

(defn fmt-2f
  "Python f'{x:.2f}' — HALF_EVEN to 2 places (banker's rounding)."
  [x]
  (.toPlainString (.setScale (java.math.BigDecimal. (double x)) 2 java.math.RoundingMode/HALF_EVEN)))

;; ── helpers (1:1 with the module fns) ────────────────────────────────────────
(defn resolve-ultimate-parent
  "Follow ownership edges up to the topmost parent. Cycle/depth guarded. Returns the input id
  if no edge (1:1 with _resolve_ultimate_parent)."
  ([company-id ownership-index] (resolve-ultimate-parent company-id ownership-index 0))
  ([company-id ownership-index depth]
   (if (or (nil? company-id) (> depth 16))
     company-id
     (let [parent (get ownership-index company-id)]
       (if (or (nil? parent) (= parent company-id))
         company-id
         (recur parent ownership-index (inc depth)))))))

(defn bom-children-index
  "defaultdict(list): :bom.edge/parent → [edges]. Preserves seed edge order per parent;
  first-touch parent order tracked in ::order meta (1:1 with _bom_children_index)."
  [bom]
  (reduce
   (fn [idx e]
     (let [p (get e ":bom.edge/parent")]
       (if (contains? idx p)
         (update idx p conj e)
         (vary-meta (assoc idx p [e]) update ::order (fnil conj []) p))))
   {}
   bom))

(defn all-materials-reachable
  "Recursively collect every :material/id reachable from a product/part via BOM, in
  DETERMINISTIC first-appearance (DFS, seed edge order) order. Faithful to
  _all_materials_reachable except the unordered Python `set` is replaced by an ordered
  accumulator so the result is reproducible (see ns docstring). Returns an ordered vector
  of distinct material ids."
  ([node-id child-idx] (all-materials-reachable node-id child-idx #{} 0))
  ([node-id child-idx seen depth]
   (if (or (contains? seen node-id) (> depth 24))
     []
     (let [seen (conj seen node-id)]
       (loop [es (get child-idx node-id []), out [], seen seen]
         (if (empty? es)
           out
           (let [e (first es)
                 child (get e ":bom.edge/child")]
             (cond
               (and child (str/starts-with? child "mat."))
               (recur (rest es) (if (some #{child} out) out (conj out child)) seen)

               :else
               (let [sub (all-materials-reachable child child-idx seen (inc depth))]
                 (recur (rest es)
                        (reduce (fn [o m] (if (some #{m} o) o (conj o m))) out sub)
                        seen))))))))))

;; ── ordered accumulator (first-touch order, mirroring a Python dict) ─────────
(defn- omap [] (with-meta {} {::order []}))
(defn- oassoc [m k v]
  (if (contains? m k) (assoc m k v)
      (vary-meta (assoc m k v) update ::order conj k)))
(defn- okeys [m] (or (::order (meta m)) (keys m)))
(defn- oitems [m] (map (fn [k] [k (get m k)]) (okeys m)))

(defn analyze
  "Faithful 1:1 of analyze(). Returns [report-md derived] where report-md is the markdown
  string (trailing newline) and derived is a vector of ordered concentration maps."
  [g]
  (let [products  (:products g)
        parts     (:parts g)
        materials (:materials g)
        bom       (:bom g)
        process   (:process g)
        logistics (:logistics g)
        ownership (:ownership g)
        product-ids (edn/keys-in-order products)
        material-ids (edn/keys-in-order materials)
        child-idx (bom-children-index bom)
        ownership-index (reduce (fn [m o]
                                  (assoc m (get o ":company.ownership/child")
                                         (get o ":company.ownership/parent")))
                                {} ownership)
        L (transient [])
        D (transient [])
        count-products (count product-ids)
        count-parts (count (edn/keys-in-order parts))
        count-materials (count material-ids)]

    ;; ── coverage summary ──
    (conj! L "# uchiwake 内訳 — product bill-of-materials resilience report\n")
    (conj! L (str "> ADR-2606081800. Aggregate-first RESILIENCE map, never a target-list (G2). "
                  "BOM decompositions are :representative public estimates, not authoritative recipes (G5).\n"))
    (conj! L (str "- products (trade items): **" count-products "**"))
    (conj! L (str "- parts / sub-assemblies: **" count-parts "**"))
    (conj! L (str "- raw materials: **" count-materials "**"))
    (conj! L (str "- BOM edges: **" (count bom) "**"))
    (conj! L (str "- process steps: **" (count process) "**"))
    (conj! L (str "- logistics legs: **" (count logistics) "**"))
    (conj! L (str "- ownership (子会社→parent) edges: **" (count ownership) "**\n"))

    ;; ── GTIN coverage ──
    (let [with-gtin (filter #(get (get products %) ":product/gtin") product-ids)
          n-with-gtin (count with-gtin)]
      (conj! L (str "## GTIN coverage\n\n" n-with-gtin "/" count-products
                    " products carry a GTIN. Full coverage target = the GS1 GDSN universe (G7-gated).\n"))

      ;; ── 1. MATERIAL concentration ──
      (let [mat-to-products
            (reduce
             (fn [acc pid]
               (reduce (fn [a mat]
                         (let [s (get a mat #{})]
                           (oassoc a mat (conj s pid))))
                       acc
                       (all-materials-reachable pid child-idx)))
             (omap)
             product-ids)
            n-prod (max 1 count-products)]
        (conj! L "## Material dependence (how many products trace down to each raw material)\n")
        (conj! L "| material | products depending | share |")
        (conj! L "|---|---:|---:|")
        (doseq [[mat pids] (sort-by (fn [[_ pids]] (- (count pids))) (oitems mat-to-products))]
          (let [share (/ (double (count pids)) n-prod)
                name- (get-in materials [mat ":material/name"] mat)]
            (conj! L (str "| " name- " | " (count pids) " | " (fmt-pct0 share) " |"))
            (conj! D (with-meta
                       {":concentration/id" (str "conc.mat." mat)
                        ":concentration/dimension" ":material" ":concentration/key" mat
                        ":concentration/share" (py-round share 4) ":concentration/count" (count pids)
                        ":concentration/derived" true}
                       {::order [":concentration/id" ":concentration/dimension" ":concentration/key"
                                 ":concentration/share" ":concentration/count" ":concentration/derived"]})))))

      ;; ── 2. PROCESS-COUNTRY concentration ──
      (let [country-steps
            (reduce (fn [acc s]
                      (let [c (get s ":process.step/country")]
                        (if c (oassoc acc c (inc (get acc c 0))) acc)))
                    (omap) process)
            n-steps (max 1 (reduce + 0 (vals country-steps)))]
        (conj! L "\n## Processing-jurisdiction load (where production steps cluster)\n")
        (conj! L "| country | process steps | share |")
        (conj! L "|---|---:|---:|")
        (doseq [[c n] (sort-by (fn [[_ n]] (- n)) (oitems country-steps))]
          (let [share (/ (double n) n-steps)]
            (conj! L (str "| " c " | " n " | " (fmt-pct0 share) " |"))
            (conj! D (with-meta
                       {":concentration/id" (str "conc.procctry." c)
                        ":concentration/dimension" ":process-country" ":concentration/key" c
                        ":concentration/share" (py-round share 4) ":concentration/count" n
                        ":concentration/derived" true}
                       {::order [":concentration/id" ":concentration/dimension" ":concentration/key"
                                 ":concentration/share" ":concentration/count" ":concentration/derived"]})))))

      ;; ── 3. ULTIMATE-PARENT rollup ──
      (let [parent-products
            (reduce
             (fn [acc pid]
               (let [p (get products pid)
                     bo (get p ":product/brand-owner")]
                 (if-not bo
                   acc
                   (let [ultimate (resolve-ultimate-parent bo ownership-index)
                         s (get acc ultimate #{})]
                     (oassoc acc ultimate (conj s pid))))))
             (omap) product-ids)
            n-with-gtin n-with-gtin
            denom (max 1 (if (and (number? n-with-gtin) (pos? n-with-gtin)) n-with-gtin count-products))]
        (conj! L "\n## Brand-owner concentration (subsidiaries rolled up to ultimate parent — 子会社)\n")
        (conj! L "| ultimate parent | products | rolled-up from subsidiary? |")
        (conj! L "|---|---:|:--:|")
        (doseq [[parent pids] (sort-by (fn [[_ pids]] (- (count pids))) (oitems parent-products))]
          (let [;; preserve Python set iteration of pids for the `any(...)` short-circuit;
                ;; result is order-independent (a boolean OR), so set order is irrelevant here.
                rolled (boolean (some (fn [pid]
                                        (let [bo (get-in products [pid ":product/brand-owner"])]
                                          (not= (resolve-ultimate-parent bo ownership-index) bo)))
                                      pids))]
            (conj! L (str "| " parent " | " (count pids) " | " (if rolled "yes" "no") " |"))
            (conj! D (with-meta
                       {":concentration/id" (str "conc.parent." parent)
                        ":concentration/dimension" ":ultimate-parent" ":concentration/key" parent
                        ":concentration/share" (py-round (/ (double (count pids)) denom) 4)
                        ":concentration/count" (count pids)
                        ":concentration/derived" true}
                       {::order [":concentration/id" ":concentration/dimension" ":concentration/key"
                                 ":concentration/share" ":concentration/count" ":concentration/derived"]}))))))

    ;; ── 4. single-source / high-criticality BOM edges ──
    (let [hot (filter #(>= (or (get % ":bom.edge/criticality") 0) 0.8) bom)]
      (conj! L "\n## High-criticality (single-source-risk) BOM edges — diversification candidates\n")
      (conj! L "| parent | child | criticality | disclosed supplier |")
      (conj! L "|---|---|---:|---|")
      (doseq [e (sort-by (fn [e] (- (or (get e ":bom.edge/criticality") 0))) hot)]
        (conj! L (str "| " (get e ":bom.edge/parent") " | " (get e ":bom.edge/child") " | "
                      (fmt-2f (get e ":bom.edge/criticality")) " | "
                      (or (get e ":bom.edge/supplier") "—") " |"))))

    [(str (str/join "\n" (persistent! L)) "\n") (persistent! D)]))

;; ── derived-datom EDN serialization (1:1 with main()'s loop) ─────────────────
(defn- derived-pair
  "f\"{k} {edn_str(v) if isinstance(v,str) and not v.startswith(':') else (str(v).lower() if isinstance(v,bool) else v)}\""
  [k v]
  (str k " "
       (cond
         (and (string? v) (not (str/starts-with? v ":"))) (edn/edn-str v)
         (boolean? v) (str/lower (str v))
         (string? v) v
         (double? v) (py-float-str v)
         :else (str v))))

(defn derived-edn
  "Render the derived concentration datoms EDN (1:1 with main()'s lines builder)."
  [derived]
  (let [L (transient [])]
    (conj! L ";; uchiwake 内訳 — DERIVED concentration datoms. ADR-2606081800.")
    (conj! L ";; :concentration/derived true — a uchiwake OBSERVATION, never re-ingested as fact.")
    (conj! L "[")
    (doseq [d derived]
      (let [order (or (::order (meta d)) (keys d))
            parts (map (fn [k] (derived-pair k (get d k))) order)]
        (conj! L (str " {" (str/join " " parts) "}"))))
    (conj! L "]")
    (str (str/join "\n" (persistent! L)) "\n")))

#?(:clj
   (defn -main
     "CLI entry (1:1 with main()): analyze a seed EDN graph → out/intel-report.md +
      out/product-criticality.kotoba.edn. File I/O only at this edge."
     [& argv]
     (let [argv (vec argv)
           here (-> *file* clojure.java.io/file .getParentFile .getParentFile)
           seed (if (and (seq argv) (not (str/starts-with? (first argv) "--")))
                  (clojure.java.io/file (first argv))
                  (clojure.java.io/file here "data" "seed-products.kotoba.edn"))
           outdir (if (some #{"--out"} argv)
                    (clojure.java.io/file (nth argv (inc (.indexOf argv "--out"))))
                    (clojure.java.io/file here "out"))
           g (edn/classify (edn/read-edn (slurp (str seed))))
           [md derived] (analyze g)]
       (.mkdirs outdir)
       (spit (clojure.java.io/file outdir "intel-report.md") md)
       (spit (clojure.java.io/file outdir "product-criticality.kotoba.edn") (derived-edn derived))
       (print md)
       (println)
       (println (str "\n→ " (clojure.java.io/file outdir "intel-report.md")
                     "\n→ " (clojure.java.io/file outdir "product-criticality.kotoba.edn")
                     " (" (count derived) " derived datoms)"))
       0)))
