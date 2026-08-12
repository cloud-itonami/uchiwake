#!/usr/bin/env bb
;; Offline coverage + content-address parity for the uchiwake LOCAL→LIVE bridge.
(ns uchiwake.methods.test-bridge
  "test_bridge.clj — uchiwake kotoba-bridge offline invariants (ADR-2606081800 / 2606142300).

  bridge.clj was previously UNTESTED. Covers the PURE / offline leg (the network `push` is
  G7-gated and exercised only for its refusal): the `graph-cid` content address, the durable
  push-cursor replay, pending-tx filtering, tx→tx_edn provenance, the exactly-once checkpoint,
  and the G7 live-push refusal.

  graph-cid gets a genuine INDEPENDENT oracle: the CID is base32-decoded in-test and asserted to
  be [0x01 0x71 0x12 0x20] ++ sha256(name) — i.e. a CORRECT CIDv1 (dag-cbor, sha2-256 multihash),
  not merely self-consistent. Pinned literals additionally prove cross-process reproducibility.

  Run:  bb test/uchiwake/methods/test_bridge.clj"
  (:require [uchiwake.methods.bridge :as b]
            [uchiwake.methods.kotoba :as k]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

;; ── independent base32 (RFC4648 lowercase, no pad) decoder + sha256, the oracle ──
(def ^:private b32-alpha "abcdefghijklmnopqrstuvwxyz234567")
(defn- b32-decode [s]
  (let [idx (into {} (map-indexed (fn [i c] [c i]) b32-alpha))]
    (loop [cs (seq s) buf 0 bits 0 out []]
      (if (empty? cs)
        (byte-array out)
        (let [buf (bit-or (bit-shift-left buf 5) (idx (first cs)))
              bits (+ bits 5)]
          (if (>= bits 8)
            (recur (rest cs) buf (- bits 8)
                   (conj out (bit-and (unsigned-bit-shift-right buf (- bits 8)) 0xff)))
            (recur (rest cs) buf bits out)))))))
(defn- sha256-bytes [^String s]
  (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))

;; ── pinned literals (captured 2026-06-16; cross-process anchor) ──
(def ^:private cid-uchiwake "bafyreidexpoa2rcwit3dpvxaaw4a5fbry2rqifagser4wxy4s4u67urnca")
(def ^:private cid-org "bafyreicpsc73b6rag6wqeyyczfwhe3ymfphzf76mq3phdp27shgwluggue")

(deftest graph-cid-is-pinned-cross-process
  (is (= cid-uchiwake (b/graph-cid "uchiwake")))
  (is (= cid-org (b/graph-cid "org.uchiwake.bom"))))

(deftest graph-cid-is-a-cidv1-dagcbor-sha256
  ;; CIDv1 dag-cbor + sha2-256 always renders base32-lower starting "bafyrei".
  (let [c (b/graph-cid "uchiwake")]
    (is (str/starts-with? c "bafyrei"))
    (is (= 59 (count c)))
    (is (every? #(str/index-of b32-alpha %) (subs c 1)))))

(deftest graph-cid-decodes-to-correct-multihash
  ;; the independent oracle: strip multibase 'b', base32-decode → [0x01 0x71 0x12 0x20] ++ sha256
  (doseq [name ["uchiwake" "org.uchiwake.bom" "any-graph-name"]]
    (let [raw (b32-decode (subs (b/graph-cid name) 1))
          prefix (vec (take 4 (map #(bit-and % 0xff) raw)))
          digest (vec (map #(bit-and % 0xff) (drop 4 raw)))
          expect (vec (map #(bit-and % 0xff) (sha256-bytes name)))]
      (is (= [0x01 0x71 0x12 0x20] prefix) (str name " CIDv1/dag-cbor/sha256 prefix"))
      (is (= 32 (count digest)) (str name " sha256 length"))
      (is (= expect digest) (str name " embedded digest == sha256(name)")))))

(deftest graph-cid-is-deterministic-pure-fn
  (is (= (b/graph-cid "x") (b/graph-cid "x")))
  (is (not= (b/graph-cid "x") (b/graph-cid "y"))))

;; ── push-cursor replay + pending filtering ──────────────────────────────────

(def ^:private sample-txs
  [{":tx/id" 1 ":tx/datoms" [[":db/add" "p1" ":product/gtin" "x"]]}
   {":tx/id" 2 ":tx/datoms" [[":db/add" "p2" ":product/gtin" "y"]]}
   {":tx/id" 3 ":tx/datoms" [[":db/add" "bridge-2" ":bridge/pushed-to-tx" 2]
                             [":db/add" "bridge-2" ":bridge/parent-commit" "bABC"]]}])

(deftest bridge-state-last-checkpoint-wins
  (is (= {:pushed-to 2 :parent-commit "bABC"} (b/bridge-state sample-txs)))
  (is (= {:pushed-to 0 :parent-commit ""} (b/bridge-state []))))

(deftest pending-excludes-pushed-and-checkpoint-txs
  ;; cursor=2 → tx1/tx2 already pushed, tx3 is a :bridge checkpoint → no pending.
  (is (= [] (mapv #(get % ":tx/id") (b/pending-txs sample-txs (b/bridge-state sample-txs)))))
  ;; with a fresh cursor, the two product txs are pending but the checkpoint is never pending.
  (let [p (b/pending-txs sample-txs {:pushed-to 0 :parent-commit ""})]
    (is (= [1 2] (mapv #(get % ":tx/id") p)))))

;; ── tx → tx_edn provenance ──────────────────────────────────────────────────

(deftest tx-to-edn-vec-appends-provenance
  (let [s (b/tx-to-edn-vec {":tx/id" 5 ":tx/cid" "bC" ":tx/prev" "bP" ":tx/as-of" 99
                            ":tx/datoms" [[":db/add" "p" ":product/name" "Nutella"]]})]
    (is (str/includes? s "[:db/add \"p\" :product/name \"Nutella\"]"))
    (is (str/includes? s ":uchiwake.tx/id 5"))
    (is (str/includes? s ":uchiwake.tx/local-cid \"bC\""))
    (is (str/includes? s ":uchiwake.tx/local-prev \"bP\""))
    (is (str/includes? s ":uchiwake.tx/as-of 99"))
    (is (str/starts-with? s "[[") (str "vector-of-vectors: " s))))

;; ── exactly-once checkpoint over a temp local log ───────────────────────────

(deftest make-checkpoint-records-cursor-and-graph-cid
  (let [tmp (java.io.File/createTempFile "uchiwake-bridge-" ".kotoba.edn")
        path (.getAbsolutePath tmp)]
    (try
      (.delete tmp)
      ;; seed a local heartbeat tx so head-cid/read-log are non-empty
      ;; (uchiwake.methods.kotoba/make-tx is positional: [datoms tx-id as-of prev-cid])
      (k/append-tx (k/make-tx [[:db/add "p1" :product/gtin "x"]] 1 1 "") path)
      ;; uchiwake kotoba tx maps use STRING keys (":tx/datoms" / ":tx/cid"), like the log on disk.
      (let [pending [{":tx/id" 1 ":tx/cid" "bLOCAL"}]
            ck (b/make-checkpoint pending "uchiwake" "http://127.0.0.1:8077/x" ["bREMOTE"] path)
            ds (get ck ":tx/datoms")
            attr (fn [a] (some (fn [d] (when (= a (nth d 2)) (nth d 3))) ds))]
        (is (= 1 (attr ":bridge/pushed-to-tx")))
        (is (= "uchiwake" (attr ":bridge/graph")))
        (is (= cid-uchiwake (attr ":bridge/graph-cid")))
        (is (= "127.0.0.1" (attr ":bridge/endpoint-host")))
        (is (= ["bREMOTE"] (attr ":bridge/remote-tx-cids")))
        (is (= 1 (attr ":bridge/count")))
        ;; the checkpoint is itself a valid commit-DAG tx
        (is (string? (get ck ":tx/cid")))
        (is (str/starts-with? (get ck ":tx/cid") "b")))
      (finally (.delete (io/file path))))))

;; ── G7 live-push refusal ────────────────────────────────────────────────────

(deftest push-refuses-without-the-live-gate
  ;; UCHIWAKE_KOTOBA_LIVE is unset in the test env → push must refuse BEFORE any network I/O.
  ;; (If a CI env ever set the gate we'd skip rather than hit the network; assert the normal case.)
  (if (= "1" (System/getenv "UCHIWAKE_KOTOBA_LIVE"))
    (is true "live gate set in env — refusal path not exercised")
    (let [e (try (b/push "uchiwake" "http://127.0.0.1:9/none" "/tmp/nonexistent.edn") nil
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e) "push must throw without the live gate")
      (is (= :G7 (:gate (ex-data e)))))))

;; ── internal-trust header (ADR-2608124000, "clients first") ──────────────────
;; kotoba-server's require_internal_trust gate returns success while
;; KOTOBA_INTERNAL_SECRET is unset, and it is unset across the fleet — so sending
;; this header today changes nothing on the wire. These tests pin the shape NOW
;; so that arming the server later is a one-variable decision rather than a
;; fleet-wide outage. Values are obviously synthetic; no real secret is used.
;;
;; NOTE: this bridge has NO Authorization header and NO host allowlist, so the
;; usual positive controls do not exist here. What IS asserted is that the G7
;; live gate still refuses, which is uchiwake's actual pre-existing control.
(def ^:private synthetic-trust "synthetic-internal-trust-not-a-real-secret")

(deftest internal-trust-header-present-when-configured
  (let [h (b/request-headers synthetic-trust)]
    (is (= synthetic-trust (get h "x-internal-trust"))
        "the configured value is sent verbatim")
    (is (= "application/json" (get h "Content-Type")) "Content-Type untouched")))

(deftest internal-trust-header-absent-when-unconfigured
  (doseq [trust [nil "" "   "]]
    (let [h (b/request-headers trust)]
      (is (not (contains? h "x-internal-trust"))
          (str "omitted entirely for " (pr-str trust) " — never an empty string"))))
  ;; the no-op property: unconfigured produces exactly the historical header map
  (is (= {"Content-Type" "application/json"} (b/request-headers nil)))
  ;; and this bridge still sends no bearer, with or without a trust secret
  (is (not (contains? (b/request-headers synthetic-trust) "Authorization"))))

(deftest internal-trust-absence-is-reported-not-silent
  (is (= "x-internal-trust" b/internal-trust-header))
  (is (= "KOTOBA_INTERNAL_SECRET" b/internal-trust-env)
      "the same variable the server and the Cloudflare gateway read")
  (with-redefs [b/internal-trust (constantly nil)]
    (is (= :unconfigured (b/internal-trust-status))))
  (with-redefs [b/internal-trust (constantly synthetic-trust)]
    (is (= :configured (b/internal-trust-status)))))

(deftest g7-gate-still-refuses-with-trust-configured
  ;; positive control — a configured secret is not a live-gate bypass
  (with-redefs [b/internal-trust (constantly synthetic-trust)]
    (let [e (try (b/push "uchiwake" "http://127.0.0.1:9/none" "/tmp/nonexistent.edn") nil
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e))
      (is (= :G7 (:gate (ex-data e)))))))
