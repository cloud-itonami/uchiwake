(ns uchiwake.methods.crosscheck
  "uchiwake 内訳 — kabuto coverage-linkage crosscheck.
  1:1 Clojure port of `methods/crosscheck.py` (ADR-2606081800).

  uchiwake's product graph references companies (brand-owner, BOM supplier, process
  operator, logistics carrier, ownership parent/child) by kabuto :company/id in the
  shared org.corp.* space. This tool computes — does not claim — how much of that
  product graph actually WIRES INTO kabuto's ingested company universe, and surfaces
  the gap honestly (a reference that does not resolve = \"not yet ingested in kabuto\",
  NOT \"does not exist\"; G5).

  It also reports the OWNERSHIP-ROLLUP effect (子会社) and the REVERSE coverage figure.

  House style (lineage): ':…' keyword strings stay strings; string-keyed maps; pure fns;
  file I/O only at #?(:clj) edges. round() = BigDecimal HALF_EVEN on the exact double."
  (:require [kotoba.lang.text :as str]
            [clojure.set]
            [uchiwake.methods.uchiwake-edn :as edn]))

(defn- py-round
  "round(x, n) — HALF_EVEN on the EXACT double value (mirrors Python round())."
  [x n]
  (-> (java.math.BigDecimal. (double x))
      (.setScale n java.math.RoundingMode/HALF_EVEN)
      .doubleValue))

;; ── ordered accumulator (first-touch order, mirroring a Python dict) ─────────
(defn- omap [] (with-meta {} {::order []}))
(defn- oassoc [m k v]
  (if (contains? m k) (assoc m k v)
      (vary-meta (assoc m k v) update ::order conj k)))
(defn- okeys [m] (or (::order (meta m)) (keys m)))
(defn- oitems [m] (map (fn [k] [k (get m k)]) (okeys m)))

(defn load-kabuto-rows
  "Classify kabuto seed rows into (company-ids, supply-out-degree). 1:1 with load_kabuto's
  body once the rows are read. Returns [ids-set out-degree-omap]. Pure over the row vector."
  [rows]
  (reduce
   (fn [[ids out-degree] r]
     (if-not (map? r)
       [ids out-degree]
       (cond
         (contains? r ":company/id") [(conj ids (get r ":company/id")) out-degree]
         (contains? r ":supply.edge/from")
         (let [k (get r ":supply.edge/from")]
           [ids (oassoc out-degree k (inc (get out-degree k 0)))])
         :else [ids out-degree])))
   [#{} (omap)]
   rows))

(defn uchiwake-covered-companies
  "Set of kabuto company ids that have ANY product-level detail in uchiwake (1:1 with
  uchiwake_covered_companies)."
  [g]
  (let [covered (transient #{})]
    (doseq [pid (edn/keys-in-order (:products g))
            :let [p (get (:products g) pid)
                  bo (get p ":product/brand-owner")]
            :when bo]
      (conj! covered bo))
    (doseq [e (:bom g) :let [s (get e ":bom.edge/supplier")] :when s] (conj! covered s))
    (doseq [s (:process g) :let [op (get s ":process.step/operator")] :when op] (conj! covered op))
    (doseq [lg (:logistics g) :let [c (get lg ":logistics.leg/carrier")] :when c] (conj! covered c))
    (persistent! covered)))

(defn collect-company-refs
  "Return ordered {kind [[ref-id holder-id] …]} for every company reference in the graph
  (1:1 with collect_company_refs). Kind order = first-touch; per-kind list = seed order."
  [g]
  (let [add (fn [refs kind pair] (oassoc refs kind (conj (get refs kind []) pair)))]
    (as-> (omap) refs
      (reduce (fn [refs pid]
                (let [bo (get-in (:products g) [pid ":product/brand-owner"])]
                  (if bo (add refs "brand-owner" [bo pid]) refs)))
              refs (edn/keys-in-order (:products g)))
      (reduce (fn [refs e]
                (let [s (get e ":bom.edge/supplier")]
                  (if s (add refs "bom-supplier" [s (get e ":bom.edge/id")]) refs)))
              refs (:bom g))
      (reduce (fn [refs s]
                (let [op (get s ":process.step/operator")]
                  (if op (add refs "process-operator" [op (get s ":process.step/id")]) refs)))
              refs (:process g))
      (reduce (fn [refs lg]
                (let [c (get lg ":logistics.leg/carrier")]
                  (if c (add refs "logistics-carrier" [c (get lg ":logistics.leg/id")]) refs)))
              refs (:logistics g))
      (reduce (fn [refs o]
                (-> refs
                    (add "ownership-child" [(get o ":company.ownership/child") (get o ":company.ownership/id")])
                    (add "ownership-parent" [(get o ":company.ownership/parent") (get o ":company.ownership/id")])))
              refs (:ownership g)))))

(defn- ultimate
  "Follow ownership index up to the ultimate parent (cycle/depth guarded; 1:1 with ultimate)."
  ([cid ownership-index] (ultimate cid ownership-index 0))
  ([cid ownership-index d]
   (if (or (nil? cid) (> d 16))
     cid
     (let [nxt (get ownership-index cid)]
       (if (or (nil? nxt) (= nxt cid)) cid (ultimate nxt ownership-index (inc d)))))))

(defn crosscheck
  "Pure crosscheck over a classified uchiwake graph + (optional) kabuto rows. 1:1 with
  crosscheck(). kabuto-rows nil ⇒ kabuto unavailable. Returns a string-keyed summary map."
  [g kabuto-rows]
  (let [[kabuto-ids out-degree] (if (nil? kabuto-rows) [nil nil] (load-kabuto-rows kabuto-rows))
        refs (collect-company-refs g)
        ownership-index (reduce (fn [m o] (assoc m (get o ":company.ownership/child")
                                                 (get o ":company.ownership/parent")))
                                {} (:ownership g))
        base {"kabuto_available" (not (nil? kabuto-ids))
              "kabuto_company_count" (if kabuto-ids (count kabuto-ids) 0)
              "by_kind" (omap) "rollup_recovered" []}
        ;; iterate refs in first-touch kind order (mirrors Python dict iteration of refs)
        [summary all-refs resolved]
        (reduce
         (fn [[summary all-refs resolved] [kind items]]
           (let [[summary all-refs resolved k-total k-res]
                 (reduce
                  (fn [[summary all-refs resolved k-total k-res] [ref-id _holder]]
                    (let [all-refs (conj all-refs ref-id)
                          k-total (inc k-total)]
                      (cond
                        (and kabuto-ids (contains? kabuto-ids ref-id))
                        [summary all-refs (conj resolved ref-id) k-total (inc k-res)]

                        kabuto-ids
                        (let [up (ultimate ref-id ownership-index)]
                          (if (and (not= up ref-id) (contains? kabuto-ids up))
                            [(update summary "rollup_recovered" conj
                                     {"ref" ref-id "ultimate" up "kind" kind})
                             all-refs resolved k-total k-res]
                            [summary all-refs resolved k-total k-res]))

                        :else [summary all-refs resolved k-total k-res])))
                  [summary all-refs resolved 0 0] items)]
             [(update summary "by_kind" oassoc kind {"total" k-total "resolved" k-res})
              all-refs resolved]))
         [base #{} #{}] (oitems refs))
        distinct- (sort all-refs)
        summary (-> summary
                    (assoc "distinct_company_refs" (count distinct-))
                    (assoc "distinct_resolved" (count resolved))
                    (assoc "linkage_pct" (py-round (/ (* 100.0 (count resolved))
                                                      (max 1 (count distinct-))) 1))
                    (assoc "unresolved" (sort (clojure.set/difference all-refs resolved))))]
    ;; ── REVERSE coverage ──
    (if (nil? kabuto-ids)
      summary
      (let [covered (clojure.set/intersection (uchiwake-covered-companies g) kabuto-ids)
            supply-companies (if (seq (okeys out-degree)) (set (okeys out-degree)) #{})
            covered-supply (clojure.set/intersection covered supply-companies)
            worklist (->> (oitems out-degree)
                          (sort-by (fn [[_ d]] (- d)))
                          (filter (fn [[c _]] (not (contains? covered c))))
                          (map (fn [[c d]] {"company" c "supply_out_degree" d}))
                          (take 15)
                          vec)]
        (assoc summary "reverse"
               {"kabuto_supply_companies" (count supply-companies)
                "with_product_detail" (count covered-supply)
                "reverse_pct" (py-round (/ (* 100.0 (count covered-supply))
                                           (max 1 (count supply-companies))) 3)
                "all_company_coverage_pct" (py-round (/ (* 100.0 (count covered))
                                                        (max 1 (count kabuto-ids))) 3)
                "worklist" worklist})))))

(defn render
  "Human markdown report (1:1 with render). Pure over the summary map."
  [s]
  (let [out (transient
             ["# uchiwake ⇄ kabuto coverage-linkage crosscheck\n"
              "> Measured (not claimed) integration of the uchiwake product graph into kabuto's"
              "> ingested company universe. Unresolved = \"not yet ingested in kabuto\", not \"nonexistent\" (G5).\n"])]
    (if-not (get s "kabuto_available")
      (do (conj! out "kabuto seed not found — cannot crosscheck. (expected at orgs/etzhayyim/com-etzhayyim-kabuto/data/)")
          (str (str/join "\n" (persistent! out)) "\n"))
      (do
        (conj! out (str "- kabuto ingested companies: **" (get s "kabuto_company_count") "**"))
        (conj! out (str "- distinct company refs in uchiwake: **" (get s "distinct_company_refs") "**"))
        (conj! out (str "- resolved into kabuto: **" (get s "distinct_resolved") "** "
                        "(**" (get s "linkage_pct") "%** linkage)\n"))
        (conj! out "| reference kind | total | resolved |")
        (conj! out "|---|---:|---:|")
        (doseq [[kind v] (sort-by first (oitems (get s "by_kind")))]
          (conj! out (str "| " kind " | " (get v "total") " | " (get v "resolved") " |")))
        (when (seq (get s "rollup_recovered"))
          (conj! out "\n## 子会社 rollup recovered (subsidiary not in kabuto, but ultimate parent is)\n")
          (doseq [r (get s "rollup_recovered")]
            (conj! out (str "- `" (get r "ref") "` → ultimate `" (get r "ultimate") "` (" (get r "kind") ")"))))
        (when (seq (get s "unresolved"))
          (conj! out "\n## Not yet ingested in kabuto (honest gap)\n")
          (doseq [u (get s "unresolved")]
            (conj! out (str "- `" u "`"))))
        (when-let [rev (get s "reverse")]
          (conj! out "\n## Reverse coverage — how much of kabuto has product-level BOM detail (情報取得割合)\n")
          (conj! out (str "- kabuto supply-chain companies (appear as a supplier): **"
                          (get rev "kabuto_supply_companies") "**"))
          (conj! out (str "- of those, with ANY uchiwake product detail: **" (get rev "with_product_detail")
                          "** (**" (get rev "reverse_pct") "%**)"))
          (conj! out (str "- across ALL " (get s "kabuto_company_count") " kabuto companies: **"
                          (get rev "all_company_coverage_pct") "%** have product detail"))
          (conj! out "\nThis is the honest worldwide-coverage figure: the product-BOM layer covers a")
          (conj! out "tiny fraction of the company universe today. Full ingest is R1 / G7-gated.\n")
          (when (seq (get rev "worklist"))
            (conj! out "### Ingest worklist — highest-centrality kabuto suppliers with NO product BOM yet\n")
            (conj! out "| kabuto supplier | supply out-degree |")
            (conj! out "|---|---:|")
            (doseq [w (get rev "worklist")]
              (conj! out (str "| `" (get w "company") "` | " (get w "supply_out_degree") " |")))))
        (str (str/join "\n" (persistent! out)) "\n")))))

;; ── CLI (#?(:clj) edge; --json branch uses a host JSON writer; __main__ omitted) ─
#?(:clj
   (defn -main
     "CLI entry (1:1 with main()): crosscheck the seed against kabuto's seed → markdown report.
     File I/O + the --json serializer are host-bound; invoke crosscheck/render directly in-process."
     [& argv]
     (let [here (-> *file* clojure.java.io/file .getParentFile .getParentFile)
           seed (clojure.java.io/file here "data" "seed-products.kotoba.edn")
           kabuto-seed (clojure.java.io/file (.getParentFile here)
                                             "kabuto" "data" "seed-public-companies.kotoba.edn")
           g (edn/classify (edn/read-edn (slurp (str seed))))
           kabuto-rows (when (.isFile kabuto-seed) (edn/read-edn (slurp (str kabuto-seed))))
           s (crosscheck g kabuto-rows)]
       (print (render s))
       0)))
