(ns uchiwake.methods.kotoba
  "uchiwake 内訳 — kotoba Datom-log writer (local, content-addressed).
  1:1 Clojure port of `methods/kotoba.py` (ADR-2606081800 + ADR-2605262130 + ADR-2605312345).

  The local, autonomous-loop write path: a heartbeat appends content-addressed transactions to a
  local append-only EDN log with NO external I/O (the same shape kanjō / shionome / ipaddress use).

  Constitutional posture holds by construction (uchiwake hard rules): only public trade-item FACTS
  (:product/:part/:material/:bom.edge/:process.step/:logistics.leg/:design.ref/:company.ownership)
  + transparent concentration are representable — never a target-list and never a clone/counterfeit
  recipe (G2/G4); derived :concentration/* carry :sourcing :synthesized and are NEVER re-ingested
  as authoritative product facts (G5).

  BYTE-PARITY (do-not-weaken): tx CID = 'b' + sha256-hex over the Python-canonical JSON
  `json.dumps({\"prev\":…,\"datoms\":…}, sort_keys=True, separators=(',',':'), ensure_ascii=False)`.
  `canonical` below reproduces that string EXACTLY (sorted keys datoms<prev, no spaces, Python-repr
  floats, JSON string escaping), so a Clojure heartbeat and the Python heartbeat build the SAME
  commit-DAG. Keys stay ':…' STRINGS (house style). Maps are read via uchiwake-edn keys-in-order so
  >8-key datoms reproduce Python dict insertion order.

  EAVT = [op entity attribute value]; op is :db/add only (append-only — no :db/retract).
  Deterministic: the caller supplies tx-id + as-of (no wall clock) → resume-safe."
  (:require [kotoba.lang.text :as str]
            [uchiwake.methods.uchiwake-edn :as edn]
            #?(:clj [clojure.java.io :as io])))

(def id-keys
  [":product/id" ":part/id" ":material/id" ":bom.edge/id"
   ":process.step/id" ":logistics.leg/id" ":design.ref/id"
   ":company.ownership/id" ":concentration/id"])

(def ^:private id-key-set (set id-keys))

;; ── Python-faithful float repr (json.dumps uses float.__repr__) ──────────────
(defn- py-float-str [x] (str (double x)))

;; ── EAVT flattening ──────────────────────────────────────────────────────────
(defn- eavt-add [e a v] [":db/add" e a v])

(defn- rows->datoms
  "Flatten entity maps into append-only EAVT assertions (1:1 with _rows_to_datoms). E = the
  entity's first id-key; list values fan out; keys iterated in Python dict (insertion) order."
  [rows]
  (vec
   (mapcat
    (fn [r]
      (if-not (map? r)
        []
        (let [e (some #(when (contains? r %) (get r %)) id-keys)]
          (if (nil? e)
            []
            (mapcat
             (fn [k]
               (if (id-key-set k)
                 []
                 (let [v (get r k)]
                   (if (sequential? v)
                     (map #(eavt-add e k %) v)
                     [(eavt-add e k v)]))))
             (edn/keys-in-order r))))))
    rows)))

(defn graph-datoms
  "Flatten the product graph (products/parts/materials/bom/process/logistics/design/ownership)
  into append-only EAVT assertions. Public trade-item facts only (G1)."
  [rows]
  (rows->datoms rows))

(defn derived-datoms
  "Flatten the analyzer's derived :concentration/* into append-only EAVT assertions. Each is
  tagged :concentration/sourcing :synthesized + :concentration/derived true (G5) — a transparent
  uchiwake OBSERVATION, never re-ingested as a fact, never a target-list/recipe (G2/G4). Sorted by
  :concentration/id so it is independent of set-iteration order (PYTHONHASHSEED-stable)."
  [derived]
  (->> derived
       (sort-by #(str (get % ":concentration/id")))
       (map (fn [d]
              (cond-> d
                (not (contains? d ":concentration/sourcing"))
                (assoc ":concentration/sourcing" ":synthesized")
                (not (contains? d ":concentration/derived"))
                (assoc ":concentration/derived" true))))
       rows->datoms))

;; ── canonical JSON (Python json.dumps parity) ────────────────────────────────
(defn- json-escape
  "Reproduce Python json.dumps default string escaping (ensure_ascii=False): escape \\ \" and the
  C0 control chars (\\b \\f \\n \\r \\t else \\uXXXX lowercase); pass everything else through."
  [s]
  (let [sb (StringBuilder.)]
    (doseq [c (str s)]
      (let [code (int c)]
        (cond
          (= c \") (.append sb "\\\"")
          (= c \\) (.append sb "\\\\")
          (= c \newline) (.append sb "\\n")
          (= c \return) (.append sb "\\r")
          (= c \tab) (.append sb "\\t")
          (= code 8) (.append sb "\\b")
          (= code 12) (.append sb "\\f")
          (< code 0x20) (.append sb (format "\\u%04x" code))
          :else (.append sb c))))
    (str sb)))

(defn- json-val
  "One JSON value matching json.dumps (separators=(',',':'))."
  [v]
  (cond
    (boolean? v) (if v "true" "false")
    (nil? v) "null"
    (integer? v) (str v)
    (float? v) (py-float-str v)
    (string? v) (str \" (json-escape v) \")
    (sequential? v) (str "[" (str/join "," (map json-val v)) "]")
    :else (str v)))

(defn- canonical
  "json.dumps({\"prev\":prev,\"datoms\":datoms}, sort_keys=True, separators=(',',':'))
  → keys sorted (datoms<prev), no spaces. Byte-identical to the Python canonical bytes."
  [datoms prev-cid]
  (str "{\"datoms\":[" (str/join "," (map json-val datoms)) "],\"prev\":" (json-val prev-cid) "}"))

(defn- sha256-hex [^String s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        b (.digest md (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) b))))

(defn tx-cid
  "Content address = 'b' + sha256 over (prev_cid, datoms) → a commit-DAG (1:1 with tx_cid)."
  ([datoms] (tx-cid datoms ""))
  ([datoms prev-cid] (str "b" (sha256-hex (canonical datoms prev-cid)))))

(defn make-tx
  [datoms tx-id as-of prev-cid]
  {":tx/id" tx-id ":tx/as-of" as-of ":tx/prev" prev-cid
   ":tx/cid" (tx-cid datoms prev-cid) ":tx/count" (count datoms) ":tx/datoms" datoms})

;; ── EDN log serialization (1:1 with _edn_val / _tx_to_edn) ───────────────────
(defn- edn-val [v]
  (cond
    (boolean? v) (if v "true" "false")
    (integer? v) (str v)
    (float? v) (py-float-str v)
    (string? v) (if (str/starts-with? v ":") v (str \" (json-escape v) \"))
    (sequential? v) (str "[" (str/join " " (map edn-val v)) "]")
    :else (str v)))

(defn tx->edn [tx]
  (let [datoms (str/join " " (map (fn [d] (str "[" (str/join " " (map edn-val d)) "]"))
                                  (get tx ":tx/datoms")))]
    (str "{:tx/id " (get tx ":tx/id") " :tx/as-of " (get tx ":tx/as-of")
         " :tx/prev " (str \" (json-escape (get tx ":tx/prev")) \")
         " :tx/cid " (str \" (json-escape (get tx ":tx/cid")) \")
         " :tx/count " (get tx ":tx/count") " :tx/datoms [" datoms "]}")))

#?(:clj
   (do
     (def log-default
       (-> (io/file *file*) .getParentFile .getParentFile
           (io/file "data" "uchiwake.datoms.kotoba.edn") str))

     (defn append-tx
       "Append ONE transaction to the append-only log (never rewrites). Returns the tx CID."
       [tx log-path]
       (let [f (io/file log-path)]
         (when-let [p (.getParentFile f)] (.mkdirs p))
         (when-not (.exists f)
           (spit f (str ";; uchiwake kotoba Datom log — append-only EAVT transactions "
                        "(content-addressed DAG). Public product facts + :synthesized "
                        "concentration; resilience map, never a target-list/recipe (G2/G4). "
                        "DO NOT hand-edit. ADR-2606081800.\n")))
         (spit f (str (tx->edn tx) "\n") :append true)
         (get tx ":tx/cid")))

     (defn read-log
       "Read the log back as a vector of transaction maps (uses the shared uchiwake-edn reader)."
       [log-path]
       (let [f (io/file log-path)]
         (if-not (.exists f)
           []
           (->> (str/split-lines (slurp f))
                (map str/trim)
                (remove #(or (empty? %) (str/starts-with? % ";")))
                (map edn/read-edn)
                (filter map?)
                vec))))

     (defn head-cid [log-path]
       (let [txs (read-log log-path)] (if (seq txs) (get (last txs) ":tx/cid") "")))

     (defn verify-chain
       "Recompute every CID from its datoms + prev; verify the DAG is intact.
       {:ok bool :length n :broken-at i}."
       [log-path]
       (let [txs (read-log log-path)
             n (count txs)]
         (loop [i 0, prev "", ts txs]
           (if (empty? ts)
             {:ok true :length n :broken-at -1}
             (let [tx (first ts)
                   expect (tx-cid (get tx ":tx/datoms") prev)]
               (if (or (not= (get tx ":tx/cid") expect) (not= (get tx ":tx/prev") prev))
                 {:ok false :length n :broken-at i}
                 (recur (inc i) (get tx ":tx/cid") (rest ts))))))))))
