(ns uchiwake.methods.adapters.openfoodfacts
  "uchiwake 内訳 — Open Food Facts → kotoba datom normalizer.
  1:1 Clojure port of `methods/adapters/openfoodfacts.py` (ADR-2606081800).

  The first concrete BULK-INGEST adapter: turns Open Food Facts product records (a
  CC-BY-SA open dataset of ~3M+ real food/beverage trade items, each with a real GTIN
  barcode + brand + ingredient list) into uchiwake :product / :material / :bom.edge
  datoms. The LIVE network fetch of the full OFF dump stays G7 / operator gated; this
  module operates on a LOCAL file or fixture and is import-safe.

  HONESTY (G5): OFF is crowd-sourced, so every emitted datom is :sourcing :representative
  (never :authoritative). The GTIN is validated against the GS1 mod-10 check digit; a record
  with a bad/missing check digit is SKIPPED, not admitted. Ingredient percentages become
  bounded :bom.edge/qty \"%mass\" estimates, never a manufacturer's confidential recipe.

  House style (lineage): Python ':…' keyword strings stay strings; string-keyed maps; pure
  fns; file/network I/O only at #?(:clj) edges. round(float(pct), 2) mirrors Python HALF_EVEN."
  (:require [kotoba.lang.text :as str]
            [uchiwake.methods.uchiwake-edn :as uedn]))

;; ── Python-faithful numeric rounding ─────────────────────────────────────────
(defn- py-round
  "round(x, n) — HALF_EVEN on the EXACT double value (mirrors Python round())."
  [x n]
  (-> (java.math.BigDecimal. (double x))
      (.setScale n java.math.RoundingMode/HALF_EVEN)
      .doubleValue))

;; OFF ingredient id (en:sugar) / free text → canonical uchiwake material id.
;; Conservative map; unknown ingredients fall through to a slugified mat.<id> (still honest).
(def ^:private mat-alias
  {"en:sugar" "mat.sugar" "en:sucrose" "mat.sugar"
   "en:water" "mat.water"
   "en:cocoa" "mat.cocoa" "en:cocoa-butter" "mat.cocoa" "en:fat-reduced-cocoa" "mat.cocoa"
   "en:hazelnut" "mat.hazelnut" "en:hazelnuts" "mat.hazelnut"
   "en:palm-oil" "mat.palm-oil" "en:palm-fat" "mat.palm-oil"
   "en:skimmed-milk-powder" "mat.milk-powder" "en:milk" "mat.milk-powder"
   "en:carbon-dioxide" "mat.co2"})

(def ^:private mat-name  ; display names for the canonical material ids above (used when freshly created)
  {"mat.sugar" "Sugar (sucrose)" "mat.water" "Water" "mat.cocoa" "Cocoa"
   "mat.hazelnut" "Hazelnut" "mat.palm-oil" "Palm oil" "mat.milk-powder" "Skim milk powder"
   "mat.co2" "Carbon dioxide (food grade)"})

(defn- slug
  "re.sub(r'[^a-z0-9]+', '-', str(s).lower().split(':')[-1]).strip('-') or 'unknown'"
  [s]
  (let [last- (last (str/split (str/lower (str s)) #":" -1))
        slugged (-> (str/replace last- #"[^a-z0-9]+" "-")
                    (str/replace #"^-+" "")
                    (str/replace #"-+$" ""))]
    (if (str/blank? slugged) "unknown" slugged)))

(defn material-for
  "Return [material-id material-datom] for an OFF ingredient map (1:1 with material_for)."
  [ingredient]
  (let [iid (or (get ingredient "id") "")
        [mid name-]
        (if (contains? mat-alias iid)
          (let [mid (get mat-alias iid)]
            [mid (or (get mat-name mid) (get ingredient "text") mid)])
          (let [mid (str "mat." (slug (or (when-not (str/blank? iid) iid)
                                          (get ingredient "text") "unknown")))]
            [mid (or (get ingredient "text") (slug iid))]))]
    [mid {":material/id" mid ":material/name" name-
          ":material/kind" ":agricultural" ":material/sourcing" ":representative"}]))

(defn normalize-record
  "One OFF record map → vector of datom maps, or [] if the GTIN is invalid (skipped).
  1:1 with normalize_record. Returned maps carry ::order meta so insertion order is preserved."
  [rec]
  (let [raw (str/trim (str (or (get rec "code") "")))]
    (if (or (str/blank? raw) (not (uedn/gtin-check-digit-ok raw)))
      []
      (let [gtin14 (uedn/normalize-gtin raw)
            pid (str "gtin." gtin14)
            digits (apply str (filter #(Character/isDigit ^char %) raw))
            fmt (get {8 ":gtin-8" 12 ":gtin-12" 13 ":gtin-13" 14 ":gtin-14"}
                     (count digits) ":gtin-13")
            brand (str/trim (first (str/split (or (get rec "brands") "") #"," -1)))
            product {":product/id" pid ":product/gtin" gtin14 ":product/gtin-format" fmt
                     ":product/name" (or (get rec "product_name") pid)
                     ":product/brand" (if (str/blank? brand) "(unknown)" brand)
                     ":product/gs1-prefix" (subs digits 0 (min 3 (count digits)))
                     ":product/sector" ":food-beverage" ":product/sourcing" ":representative"}]
        (loop [ings (or (get rec "ingredients") [])
               seen-mat #{}
               out [(with-meta product
                      {::order [":product/id" ":product/gtin" ":product/gtin-format"
                                ":product/name" ":product/brand" ":product/gs1-prefix"
                                ":product/sector" ":product/sourcing"]})]]
          (if (empty? ings)
            out
            (let [ing (first ings)
                  [mid mdat] (material-for ing)
                  [seen-mat out]
                  (if-not (contains? seen-mat mid)
                    [(conj seen-mat mid)
                     (conj out (with-meta mdat
                                 {::order [":material/id" ":material/name"
                                           ":material/kind" ":material/sourcing"]}))]
                    [seen-mat out])
                  pct (get ing "percent_estimate")
                  edge-base {":bom.edge/id" (str "bom." gtin14 "." (last (str/split mid #"\.")))
                             ":bom.edge/parent" pid ":bom.edge/child" mid ":bom.edge/tier" 1
                             ":bom.edge/criticality" 0.3 ":bom.edge/sourcing" ":representative"}
                  edge-order [":bom.edge/id" ":bom.edge/parent" ":bom.edge/child" ":bom.edge/tier"
                              ":bom.edge/criticality" ":bom.edge/sourcing"]
                  [edge edge-order]
                  (if (number? pct)
                    [(assoc edge-base ":bom.edge/qty" (py-round pct 2) ":bom.edge/qty-unit" "%mass")
                     (conj edge-order ":bom.edge/qty" ":bom.edge/qty-unit")]
                    [edge-base edge-order])]
              (recur (rest ings) seen-mat
                     (conj out (with-meta edge {::order edge-order}))))))))))

(defn normalize-dataset
  "Normalize many OFF records; dedup materials by id (first wins). Returns [datoms stats]
  where stats is a string-keyed map (1:1 with normalize_dataset)."
  [records]
  (loop [recs records, out [], mat-ids #{}, n-ok 0, n-skip 0]
    (if (empty? recs)
      [out {"products_ok" n-ok "skipped_bad_gtin" n-skip "materials" (count mat-ids)}]
      (let [ds (normalize-record (first recs))]
        (if (empty? ds)
          (recur (rest recs) out mat-ids n-ok (inc n-skip))
          (let [[out mat-ids]
                (reduce (fn [[o mids] d]
                          (if (contains? d ":material/id")
                            (if (contains? mids (get d ":material/id"))
                              [o mids]
                              [(conj o d) (conj mids (get d ":material/id"))])
                            [(conj o d) mids]))
                        [out mat-ids] ds)]
            (recur (rest recs) out mat-ids (inc n-ok) n-skip)))))))

;; ── datom → EDN serialization (1:1 with _to_edn) ─────────────────────────────
(defn- val-edn
  [v]
  (cond
    (boolean? v) (if v "true" "false")
    (string? v) (if (str/starts-with? v ":") v (uedn/edn-str v))
    (and (number? v) (not (integer? v))) (str (double v))
    :else (str v)))

(defn to-edn
  "Serialize the datom vector to OFF-provenance EDN text (1:1 with _to_edn)."
  [datoms]
  (let [head [";; uchiwake — datoms normalized from Open Food Facts (CC-BY-SA). :representative (G5)."
              ";; ADR-2606081800. GTINs validated by GS1 mod-10; LIVE OFF fetch is G7-gated."
              "["]
        body (map (fn [d]
                    (let [order (or (::order (meta d)) (keys d))]
                      (str " {" (str/join " " (map (fn [k] (str k " " (val-edn (get d k)))) order)) "}")))
                  datoms)]
    (str (str/join "\n" (concat head body ["]"])) "\n")))

;; ── CLI (#?(:clj) edge; __main__ demo omitted as a non-pure entry) ───────────
#?(:clj
   (defn -main
     "CLI entry (1:1 with main()): normalize an OFF records JSON → EDN on stdout / --out file.
     File I/O only at this edge; requires a JSON reader on the host (data.json/cheshire)."
     [& argv]
     (throw (ex-info "openfoodfacts -main is a host-IO entry; invoke normalize-dataset/to-edn directly" {}))))
