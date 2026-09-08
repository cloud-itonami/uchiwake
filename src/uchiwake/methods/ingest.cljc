(ns uchiwake.methods.ingest
  "uchiwake 内訳 — product / GTIN / BOM ingestion bridge (offline default; live G7-gated).
  1:1 Clojure port of `methods/ingest.py` (ADR-2606081800).

  Bridges public product-data sources into the kotoba Datom log as
  :product/:part/:material/:bom.edge/:process.step/:logistics.leg/:design.ref/
  :company.ownership datoms, dedup-merged with the bounded real seed (seed wins on id).

  GATES (enforced here):
    G1  public trade items + public-record data only.
    G5  every emitted datom carries :*/sourcing; bridged data defaults :representative.
    G7  live full-universe fetch requires UCHIWAKE_OPERATOR_GATE=1 (Council + operator).
        Default is OFFLINE: bridge wire/data/*.json if present, else just the seed.
    no-server-key: read-only.

  House style (lineage): ':…' keyword strings stay strings; string-keyed maps; pure fns;
  file/network I/O behind #?(:clj) edges (json.loads / file globbing / EDN text splicing)."
  (:require [kotoba.lang.text :as str]
            [uchiwake.methods.uchiwake-edn :as edn]
            [uchiwake.methods.adapters.openfoodfacts :as off]))

(def ^:private id-keys
  [":product/id" ":part/id" ":material/id" ":bom.edge/id"
   ":process.step/id" ":logistics.leg/id" ":design.ref/id" ":company.ownership/id"])

(defn seed-ids
  "Set of every entity id present across the seed rows (1:1 with _seed_ids)."
  [rows]
  (reduce
   (fn [ids r]
     (if-not (map? r)
       ids
       (reduce (fn [ids k] (if (contains? r k) (conj ids (get r k)) ids)) ids id-keys)))
   #{}
   rows))

(defn- first-id
  "next((r[k] for k in id-keys if k in r), None) — first present entity id, or nil."
  [r]
  (some (fn [k] (when (contains? r k) (get r k))) id-keys))

(defn admit-datom-doc-rows
  "Pure body of the non-OFF doc branch of bridge_offline: filter+default a list of datom rows
  against the seed ids (1:1 with the inner loop). Skips bad-GTIN product datoms (G5 honesty)
  and seed-owned ids (seed wins); defaults :product/sourcing to :representative on products."
  [rows seed-ids]
  (reduce
   (fn [bridged r]
     (cond
       (and (contains? r ":product/gtin")
            (not (edn/gtin-check-digit-ok (get r ":product/gtin"))))
       bridged                              ; skip — bad GTIN check digit

       :else
       (let [rid (first-id r)]
         (if (and rid (contains? seed-ids rid))
           bridged                          ; seed wins
           (let [r (if (contains? r ":product/id")
                     (if (contains? r ":product/sourcing") r
                         (assoc r ":product/sourcing" ":representative"))
                     r)]
             (conj bridged r))))))
   []
   rows))

(defn admit-off-datoms
  "Pure body of the OFF branch of bridge_offline: drop any normalized OFF datom whose product/
  material/bom id is already in the seed (seed wins). 1:1 with the off_datoms loop."
  [off-datoms seed-ids]
  (reduce
   (fn [bridged r]
     (let [rid (or (get r ":product/id") (get r ":material/id") (get r ":bom.edge/id"))]
       (if (and rid (contains? seed-ids rid)) bridged (conj bridged r))))
   []
   off-datoms))

(defn emit-bridged-edn
  "Serialize bridged datom maps to EDN map literals, one per line (1:1 with _emit_bridged_edn)."
  [datoms]
  (let [val (fn [v]
              (cond
                (boolean? v) (if v "true" "false")
                (string? v) (if (str/starts-with? v ":") v (edn/edn-str v))
                (and (number? v) (not (integer? v))) (str (double v))
                :else (str v)))
        lines (cons " ;; ── bridged datoms (offline adapters; :representative, G5) ──"
                    (map (fn [d]
                           (let [order (or (::order (meta d)) (keys d))]
                             (str " {" (str/join " " (map (fn [k] (str k " " (val (get d k)))) order)) "}")))
                         datoms))]
    (str/join "\n" lines)))

;; ── file/network-bound bridge + CLI (#?(:clj) edge; __main__ omitted) ────────

#?(:clj
   (defn fetch-off
     "LIVE single-GTIN Open Food Facts fetch — G7-gated, single public read-only request
     (OFF's product API needs no auth). Refuses (throws) unless UCHIWAKE_OPERATOR_GATE=1,
     mirroring ingest.py's sys.exit guard, before any network call."
     [gtin]
     (when (not= (System/getenv "UCHIWAKE_OPERATOR_GATE") "1")
       (throw (ex-info (str "refused: live fetch requires UCHIWAKE_OPERATOR_GATE=1 "
                            "(G7 Council+operator gate). Offline mode reads wire/data/*.json.")
                       {:uchiwake/gate "G7"})))
     (let [url (str "https://world.openfoodfacts.org/api/v2/product/" gtin ".json")
           parse-json (requiring-resolve 'cheshire.core/parse-string)
           reader (requiring-resolve 'clojure.java.io/reader)
           conn (doto (.openConnection (java.net.URL. url))
                  (.setRequestProperty "User-Agent" "etzhayyim-uchiwake research jun@etzhayyim.group")
                  (.setConnectTimeout 30000)
                  (.setReadTimeout 30000))]
       (with-open [r (reader (.getInputStream conn))]
         (parse-json (slurp r))))))

#?(:clj
   (defn -main
     "CLI entry (1:1 with main()): offline bridge of wire/data/*.json (OFF adapter for
     openfoodfacts*, datom docs otherwise) + the seed, merged to products.merged.kotoba.edn
     (seed wins on id; seed text preserved verbatim, bridged datoms spliced before the closing
     ']'). Live ingest (--live) is G7-gated — refused without UCHIWAKE_OPERATOR_GATE=1 (offline
     fallback); the wired single-GTIN live OFF fetch stays host-bound. File I/O only at this edge.
     ADR-2606261200 cljc-native operator leg. UCHIWAKE_ACTOR_DIR overrides the actor root."
     [& argv]
     (let [io-file  (requiring-resolve 'clojure.java.io/file)
           parse    (requiring-resolve 'cheshire.core/parse-string)
           read-edn (requiring-resolve 'uchiwake.methods.uchiwake-edn/read-edn)
           off-norm (requiring-resolve 'uchiwake.methods.adapters.openfoodfacts/normalize-dataset)
           root     (io-file (or (System/getenv "UCHIWAKE_ACTOR_DIR") "."))
           seed-f   (io-file root "data" "seed-products.kotoba.edn")
           ingest-d (io-file root "wire" "data")
           merged-f (io-file root "data" "products.merged.kotoba.edn")
           argv     (vec argv)]
       (when (and (some #{"--live"} argv) (not= (System/getenv "UCHIWAKE_OPERATOR_GATE") "1"))
         (binding [*out* *err*]
           (println "REFUSED (G7): live ingest requires UCHIWAKE_OPERATOR_GATE=1 + Council; running offline.")))
       (let [seed-rows (read-edn (slurp seed-f))
             sids      (seed-ids seed-rows)
             files     (when (.isDirectory ingest-d)
                         (->> (.listFiles ingest-d)
                              (filter #(.endsWith (.getName %) ".json"))
                              (sort-by #(.getName %))))
             bridged   (reduce
                        (fn [acc f]
                          (let [doc (parse (slurp f))]
                            (if (str/starts-with? (.getName f) "openfoodfacts")
                              (let [recs (if (map? doc) (get doc "products" []) doc)]
                                (into acc (admit-off-datoms (first (off-norm recs)) sids)))
                              (let [rows (if (sequential? doc) doc (get doc "datoms" []))]
                                (into acc (admit-datom-doc-rows rows sids))))))
                        []
                        (or files []))
             seed-txt  (slurp seed-f)]
       (if (seq bridged)
         (let [block   (emit-bridged-edn bridged)
               trimmed (str/trimr seed-txt)
               cut     (.lastIndexOf trimmed "]")
               out     (str (subs trimmed 0 cut) "\n" block "\n]\n")]
           (spit merged-f out)
           (println (str "-> data/products.merged.kotoba.edn (seed + " (count bridged) " bridged datoms)")))
         (do (spit merged-f seed-txt)
             (println "-> data/products.merged.kotoba.edn (== seed; no external ingest)")))))))
