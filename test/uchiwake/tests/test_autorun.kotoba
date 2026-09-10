(ns uchiwake.tests.test-autorun
  "uchiwake 内訳 — autonomous heartbeat + kotoba Datom-log + bridge/ingest-gate invariants
  (clojure.test). ADR-2606081800.

  The heartbeat (kotoba.cljc + autorun.cljc) + the live-node push (bridge.clj) are **clj-native
  SSoT** (ADR-2606142300 D1: new logic-core is authored in Clojure, not Python-first) — so this is
  the canonical test, not a port. Guards: one content-addressed tx per beat to an append-only log;
  a verifiable commit-DAG (every CID recomputes; tamper detected); deterministic / resume-safe
  (same cycles → same CIDs); G5 derived-:synthesized; G2/G4 resilience-not-target / not-a-recipe;
  append-only :db/add; the bridge exactly-once cursor + its G7 push-gate; the ingest G7 fetch-gate.
  `cid-byte-parity-with-python` is retained as a frozen golden-value regression guard (the value the
  removed Python reference produced) per the D2.1 byte-parity bar."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [kotoba.lang.text :as str]
            [clojure.java.io :as io]
            [uchiwake.methods.autorun :as autorun]
            [uchiwake.methods.kotoba :as k]
            [uchiwake.methods.bridge :as bridge]
            [uchiwake.methods.ingest :as ingest]))

(def ^:private seed-path "./data/seed-products.kotoba.edn")

(defn- tmp-log []
  (let [f (java.io.File/createTempFile "uchiwake-test" ".datoms.kotoba.edn")]
    (.delete f)
    (str f)))

(deftest heartbeat-persists
  (let [log (tmp-log)]
    (try
      (let [res (autorun/run-autonomous 3 seed-path log)]
        (is (= 3 (:log-length res)) "one tx per heartbeat")
        (is (every? #(pos? (:datoms %)) (:beats res)) "every heartbeat persisted datoms")
        (is (every? #(pos? (:concentration %)) (:beats res)) "derived concentration persisted")
        (is (:ok (:chain res)) "commit-DAG verifies (chain OK)")
        (is (str/starts-with? (:head-cid res) "b") "head CID is content-addressed"))
      (finally (.delete (io/file log))))))

(deftest deterministic-resume-safe
  (let [a (tmp-log) b (tmp-log)]
    (try
      (let [ra (autorun/run-autonomous 3 seed-path a)
            rb (autorun/run-autonomous 3 seed-path b)]
        (is (= (map :cid (:beats ra)) (map :cid (:beats rb)))
            "same cycles → same CIDs (deterministic / resume-safe)"))
      (finally (.delete (io/file a)) (.delete (io/file b))))))

(deftest append-only-and-tamper
  (let [log (tmp-log)]
    (try
      (autorun/run-cycle 1 seed-path log)
      (let [first-txs (k/read-log log)]
        (autorun/run-cycle 2 seed-path log)
        (let [second-txs (k/read-log log)]
          (is (= (inc (count first-txs)) (count second-txs)) "second beat appends, no rewrite")
          (is (= (get (second second-txs) ":tx/prev") (get (first first-txs) ":tx/cid"))
              "tx 2 links tx 1's CID (commit-DAG)"))
        ;; tamper an earlier tx → the chain must break at 0
        (let [lines (str/split-lines (slurp log))
              tampered (map (fn [ln]
                              (if (str/includes? ln ":tx/id 1 ")
                                (str/replace-first ln ":concentration/sourcing :synthesized"
                                                   ":concentration/sourcing :authoritative")
                                ln))
                            lines)]
          (spit log (str (str/join "\n" tampered) "\n"))
          (let [v (k/verify-chain log)]
            (is (and (not (:ok v)) (= 0 (:broken-at v))) "tampering an earlier tx breaks the chain"))))
      (finally (.delete (io/file log))))))

(defn- entity-attrs [tx]
  (reduce (fn [m d] (assoc-in m [(nth d 1) (nth d 2)] (nth d 3))) {} (get tx ":tx/datoms")))

(deftest g5-derived-synthesized
  (let [log (tmp-log)]
    (try
      (autorun/run-cycle 1 seed-path log)
      (let [tx (first (k/read-log log))
            ents (entity-attrs tx)
            derived (filter (fn [[_e at]]
                              (some #(str/starts-with? (str %) ":concentration/") (keys at)))
                            ents)]
        (is (seq derived) "derived :concentration entities persisted")
        (doseq [[e at] derived]
          (is (= ":synthesized" (get at ":concentration/sourcing"))
              (str "derived " e " declares :sourcing :synthesized (G5)"))
          (is (true? (get at ":concentration/derived"))
              (str "derived " e " carries :concentration/derived true (never re-ingested)"))))
      (finally (.delete (io/file log))))))

(deftest g2-g4-not-target-not-recipe
  (let [log (tmp-log)]
    (try
      (autorun/run-cycle 1 seed-path log)
      (let [tx (first (k/read-log log))
            attrs (set (map #(str (nth % 2)) (get tx ":tx/datoms")))
            ops (set (map first (get tx ":tx/datoms")))]
        (doseq [forbidden [":concentration/target" ":concentration/rank-to-hit" ":target"
                           ":product/clone" ":product/counterfeit" ":bom.edge/full-recipe"
                           ":bom.edge/exact-formulation" ":material/exact-quantity"]]
          (is (not (contains? attrs forbidden))
              (str "no target/recipe attr `" forbidden "` in the log (G2/G4)")))
        (is (= #{":db/add"} ops) "every datom is append-only :db/add (G11)"))
      (finally (.delete (io/file log))))))

(deftest cid-byte-parity-with-python
  ;; Frozen golden CID: the value the (removed) Python reference produced — a regression guard
  ;; that the clj-native SSoT encoder stays byte-stable (D2.1 byte-parity bar).
  (let [rows (uchiwake.methods.uchiwake-edn/load-edn seed-path)]
    (is (= "bccd2fa317cf3c10c9f1834da8155c6c2a0ecdebb3447f083a84355bc3230c67a"
           (k/tx-cid (k/graph-datoms rows) ""))
        "graph_datoms tx CID stays byte-stable (frozen golden value)")))

(deftest bridge-graph-cid-stable
  ;; Frozen golden base32 dag-cbor graph CID (the value the removed Python bridge produced).
  (is (= "bafyreidexpoa2rcwit3dpvxaaw4a5fbry2rqifagser4wxy4s4u67urnca"
         (bridge/graph-cid "uchiwake")) "graph-cid stays byte-stable (frozen golden value)"))

(deftest bridge-push-gated-G7
  ;; UCHIWAKE_KOTOBA_LIVE is unset in test → live-node push refuses (G7).
  (is (thrown? clojure.lang.ExceptionInfo (bridge/push "uchiwake" "http://x:8077/y" (tmp-log)))
      "bridge/push refuses without the live-node gate (G7)"))

(deftest ingest-fetch-gated-G7
  ;; UCHIWAKE_OPERATOR_GATE is unset in test → live OFF fetch refuses (G7) before any network call.
  (is (thrown? clojure.lang.ExceptionInfo (ingest/fetch-off "3017620422003"))
      "ingest/fetch-off refuses without the operator gate (G7)"))
