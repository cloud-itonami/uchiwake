(ns uchiwake.methods.bridge
  "uchiwake 内訳 — LOCAL→LIVE kotoba-node push leg (exactly-once, G7-gated).
  Clojure port of `methods/bridge.py` (ADR-2606081800), Wave-2 clj-native migration
  (ADR-2606142300). A clj-side network tool, so `.clj` (alongside ingest.clj / crosscheck.clj).

  autorun persists the heartbeat to the LOCAL append-only kotoba Datom log; this module pushes
  those local transactions to a LIVE kotoba node (the `datomic.transact` XRPC) and records an
  exactly-once cursor back on the local log, so the live graph maps to the local commit-DAG and a
  crash / re-run never double-sends.

  DISCIPLINE: the network push is gated by UCHIWAKE_KOTOBA_LIVE=1 (Council + operator); the
  cursor/replay logic is pure and runs offline (--status). no-server-key (unsigned public-DID
  operator bearer, G12). Each push appends ONE :bridge/* checkpoint (the highest local tx-id sent);
  on replay the LAST checkpoint wins. Every remote tx carries :uchiwake.tx/* provenance."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [uchiwake.methods.uchiwake-edn :as uedn]
            [uchiwake.methods.kotoba :as k]))

(def graph-default "uchiwake")
(def endpoint-default
  (or (System/getenv "UCHIWAKE_KOTOBA_ENDPOINT")
      "http://127.0.0.1:8077/xrpc/com.kotoba.datomic.transact"))
(def ^:private base-as-of 26060816)

;; ── internal-trust header (ADR-2608124000) ────────────────────────────────────
;; kotoba-server's `require_internal_trust` gate compares this header against its
;; own KOTOBA_INTERNAL_SECRET. That variable is unset across the murakumo fleet,
;; so the gate returns success and the header is never read — sending it TODAY is
;; a complete no-op. That is precisely why it is safe to ship now: every caller
;; must demonstrably send it BEFORE the server side can be armed, and arming the
;; server first would break every caller at once.
;;
;; We read the SAME variable name the server and the Cloudflare gateway read, so
;; arming the fleet later is one variable rather than one per actor. The value is
;; only ever read from the environment — never minted, never defaulted.
;;
;; When it is unconfigured we OMIT the header, but never SILENTLY: a one-shot
;; stderr warning fires on the live path, and `request-headers` /
;; `internal-trust-status` expose the same fact as values a fleet sweep can read,
;; instead of as a log line nobody reads.
;; Silent omission is the shape that let an unauthenticated
;; fleet look healthy in the first place.
;;
;; NOTE: this bridge sends NO Authorization header at all -- it has never had a
;; bearer, and none is added here. It also has NO host allowlist: the endpoint
;; comes from UCHIWAKE_KOTOBA_ENDPOINT or the loopback default. Both are
;; pre-existing gaps, recorded rather than fixed in this change.
(def internal-trust-header "x-internal-trust")
(def internal-trust-env "KOTOBA_INTERNAL_SECRET")

(defn internal-trust
     "The configured internal-trust secret, or nil when unset/blank. Environment
     only — this function never mints or defaults a value."
     []
     (let [v (System/getenv internal-trust-env)]
       (when-not (str/blank? v) v)))

(def ^:private internal-trust-warned? (atom false))

(defn internal-trust-status
     "`:configured` | `:unconfigured` — the machine-readable half of the warning."
     []
     (if (internal-trust) :configured :unconfigured))

(defn warn-unconfigured-internal-trust!
     "Announce ONCE per process that this push carries no internal-trust header."
     []
     (when (compare-and-set! internal-trust-warned? false true)
       (binding [*out* *err*]
         (println (str "WARN uchiwake.methods.bridge: " internal-trust-env " is unset — requests carry NO "
                       internal-trust-header " header. Harmless while kotoba-server's"
                       " require_internal_trust gate is disabled fleet-wide"
                       " (ADR-2608124000); it becomes a hard rejection the moment"
                       " that gate is armed.")))))

(defn request-headers
  "The full header map for a transact POST. PURE — the caller reads the
   environment and passes `trust` in, so this stays deterministic in tests.
   This bridge has never sent an Authorization header and still does not."
  [trust]
  (cond-> {"Content-Type" "application/json"}
    (not (str/blank? trust)) (assoc internal-trust-header trust)))

;; ── base32 (RFC4648 lowercase, no padding) — matches Python b32encode(...).rstrip('=').lower() ──
(def ^:private b32-alpha "abcdefghijklmnopqrstuvwxyz234567")

(defn- base32-lower [^bytes data]
  (let [sb (StringBuilder.)]
    (loop [i 0 buf 0 bits 0]
      (if (< i (alength data))
        (let [buf (bit-or (bit-shift-left buf 8) (bit-and (long (aget data i)) 0xff))
              bits (+ bits 8)
              [buf bits] (loop [buf buf bits bits]
                           (if (>= bits 5)
                             (do (.append sb (.charAt b32-alpha
                                                      (bit-and (unsigned-bit-shift-right buf (- bits 5)) 0x1f)))
                                 (recur buf (- bits 5)))
                             [buf bits]))]
          (recur (inc i) buf bits))
        (do (when (pos? bits)
              (.append sb (.charAt b32-alpha (bit-and (bit-shift-left buf (- 5 bits)) 0x1f))))
            (str sb))))))

(defn graph-cid
  "The kotoba graph identifier for a graph NAME: dag-cbor (0x71) multihash sha2-256, multibase
  base32 ('b' prefix) — 1:1 with bridge.py graph_cid."
  [name]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes (str name) "UTF-8"))
        raw (byte-array (concat [(byte 0x01) (byte 0x71) (byte 0x12) (byte 0x20)] (seq digest)))]
    (str "b" (base32-lower raw))))

(defn bridge-state
  "Replay the durable push cursor from the local log: the LAST :bridge/* checkpoint wins.
  Returns {:pushed-to n :parent-commit cid} (1:1 with bridge_state)."
  [txs]
  (reduce
   (fn [st tx]
     (reduce (fn [st d]
               (if (< (count d) 4) st
                   (let [a (nth d 2) v (nth d 3)]
                     (cond
                       (= a ":bridge/pushed-to-tx") (assoc st :pushed-to v)
                       (= a ":bridge/parent-commit") (assoc st :parent-commit v)
                       :else st))))
             st (get tx ":tx/datoms")))
   {:pushed-to 0 :parent-commit ""} txs))

(defn pending-txs
  "Heartbeat transactions not yet pushed (id beyond the cursor; excludes :bridge/* checkpoint txs)."
  [txs state]
  (filterv
   (fn [tx]
     (and (> (get tx ":tx/id" 0) (:pushed-to state))
          (not (some #(and (>= (count %) 3) (str/starts-with? (str (nth % 2)) ":bridge/"))
                     (get tx ":tx/datoms")))))
   txs))

(defn- edn-val [v]
  (cond
    (boolean? v) (if v "true" "false")
    (integer? v) (str v)
    (float? v) (uedn/py-float-str v)
    (string? v) (if (str/starts-with? v ":") v (json/generate-string v))
    (sequential? v) (str "[" (str/join " " (map edn-val v)) "]")
    :else (str v)))

(defn tx-to-edn-vec
  "One local transaction → the tx_edn vector string the transact lexicon takes: [:db/add e a v]
  forms + :uchiwake.tx/* provenance (1:1 with tx_to_edn_vec)."
  [tx]
  (let [meta-e (str "uchiwake-tx-" (get tx ":tx/id"))
        forms (concat (get tx ":tx/datoms")
                      [[":db/add" meta-e ":uchiwake.tx/id" (get tx ":tx/id")]
                       [":db/add" meta-e ":uchiwake.tx/local-cid" (get tx ":tx/cid")]
                       [":db/add" meta-e ":uchiwake.tx/local-prev" (get tx ":tx/prev")]
                       [":db/add" meta-e ":uchiwake.tx/as-of" (get tx ":tx/as-of")]])]
    (str "[" (str/join " " (map (fn [d] (str "[" (str/join " " (map edn-val d)) "]")) forms)) "]")))

(defn make-checkpoint
  "Build the ONE exactly-once :bridge/* checkpoint tx (1:1 with make_checkpoint). Pure — caller
  appends it."
  [pending graph endpoint remote-cids log-path]
  (let [beat (:pushed-to (bridge-state (k/read-log log-path)))
        e (str "bridge-" (get (last pending) ":tx/id"))
        host (.getHost (java.net.URL. endpoint))
        datoms [[":db/add" e ":bridge/pushed-to-tx" (get (last pending) ":tx/id")]
                [":db/add" e ":bridge/parent-commit" (k/head-cid log-path)]
                [":db/add" e ":bridge/graph" graph]
                [":db/add" e ":bridge/graph-cid" (graph-cid graph)]
                [":db/add" e ":bridge/endpoint-host" host]
                [":db/add" e ":bridge/remote-tx-cids" (vec remote-cids)]
                [":db/add" e ":bridge/count" (count pending)]]]
    (k/make-tx datoms (+ base-as-of beat 1) (+ base-as-of beat 1) (k/head-cid log-path))))

(defn push
  "Push every pending heartbeat tx to the LIVE kotoba node, then append one exactly-once
  :bridge/* checkpoint. G7: refuses unless UCHIWAKE_KOTOBA_LIVE=1."
  [graph endpoint log-path]
  (when (not= (System/getenv "UCHIWAKE_KOTOBA_LIVE") "1")
    (throw (ex-info "REFUSED (G7): live-node push requires UCHIWAKE_KOTOBA_LIVE=1 + Council." {:gate :G7})))
  (let [txs (k/read-log log-path)
        state (bridge-state txs)
        pending (pending-txs txs state)]
    (if (empty? pending)
      {:pushed 0 :pushed-to (:pushed-to state) :graph-cid (graph-cid graph)}
      (let [remote-cids
            (loop [ps pending parent (:parent-commit state) acc []]
              (if (empty? ps)
                acc
                (let [tx (first ps)
                      body (json/generate-string {"graph" graph
                                                  "tx_edn" (tx-to-edn-vec tx)
                                                  "expected_parent" parent})
                      conn (doto ^java.net.HttpURLConnection (.openConnection (java.net.URL. endpoint))
                             (.setRequestMethod "POST") (.setDoOutput true)
                             (.setConnectTimeout 30000) (.setReadTimeout 30000))
                      _ (let [trust (internal-trust)]
                          (when-not trust (warn-unconfigured-internal-trust!))
                          (doseq [[hk hv] (request-headers trust)]
                            (.setRequestProperty conn hk hv)))
                      _ (with-open [o (.getOutputStream conn)] (.write o (.getBytes body "UTF-8")))
                      resp (with-open [r (io/reader (.getInputStream conn))] (json/parse-string (slurp r)))
                      cid (or (get resp "tx_cid") (get resp "cid") "")]
                  (recur (rest ps) cid (conj acc cid)))))
            ck (make-checkpoint pending graph endpoint remote-cids log-path)]
        (k/append-tx ck log-path)
        {:pushed (count pending) :pushed-to (get (last pending) ":tx/id")
         :graph-cid (graph-cid graph) :remote-cids remote-cids}))))

(defn -main [& argv]
  (let [argv (vec argv)
        flag? (fn [f] (>= (.indexOf argv f) 0))
        opt (fn [f d] (let [i (.indexOf argv f)] (if (>= i 0) (nth argv (inc i)) d)))
        graph (opt "--graph" graph-default)
        endpoint (opt "--endpoint" endpoint-default)
        log-path (opt "--log" k/log-default)
        txs (k/read-log log-path)
        state (bridge-state txs)
        pend (pending-txs txs state)]
    (println (str "# uchiwake kotoba bridge — graph " graph " ("
                  (subs (graph-cid graph) 0 (min 16 (count (graph-cid graph)))) "…)"))
    (println (str "  local log: " (count txs) " tx · cursor pushed-to " (:pushed-to state)
                  " · pending " (count pend) " · parent-commit "
                  (let [p (:parent-commit state)] (if (seq p) (subs p 0 (min 14 (count p))) "∅"))))
    (cond
      (flag? "--push")
      (let [res (push graph endpoint log-path)]
        (println (str "  pushed " (:pushed res) " tx → cursor now " (:pushed-to res))))
      (not (flag? "--status"))
      (println "  (use --status to inspect, or UCHIWAKE_KOTOBA_LIVE=1 --push to push; G7)"))))
