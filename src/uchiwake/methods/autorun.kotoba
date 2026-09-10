(ns uchiwake.methods.autorun
  "uchiwake 内訳 — AUTONOMOUS product-resilience heartbeat on the kotoba Datom log.
  1:1 Clojure port of `methods/autorun.py` (ADR-2606081800).

  Each heartbeat the actor runs its whole product-BOM pipeline ITSELF, with no human in the loop:
  observe (load the OFFLINE merged product graph) → recursive BOM material-closure → material
  dependence + processing-jurisdiction load + ultimate-parent rollup (子会社) → PERSIST a
  content-addressed transaction to the append-only LOCAL kotoba Datom log (graph datoms + derived
  :concentration), linking the previous tx's CID.

  Constitutional posture holds by construction (uchiwake G1/G2/G4/G5): only public trade-item facts
  + transparent concentration are representable — never a target-list and never a clone/counterfeit
  recipe; derived :concentration/* carry :sourcing :synthesized and are never re-ingested.

  Deterministic / resume-safe (cycle drives tx-id + as-of → same CIDs; derived sorted by id, so
  independent of set-iteration order) and append-only. WHAT STAYS GATED (G7): no live universe
  fetch, no live-node push. Byte-parity with autorun.py is test-asserted (same beat CIDs)."
  (:require [uchiwake.methods.uchiwake-edn :as edn]
            [uchiwake.methods.analyze :as analyze]
            [uchiwake.methods.kotoba :as k]
            #?(:clj [clojure.java.io :as io])))

(def base-as-of 20260616)

#?(:clj
   (do
     (def ^:private here (-> (io/file *file*) .getParentFile .getParentFile))
     (def ^:private merged (io/file here "data" "products.merged.kotoba.edn"))
     (def ^:private seed (io/file here "data" "seed-products.kotoba.edn"))
     (def log-default (str (io/file here "data" "uchiwake.datoms.kotoba.edn")))

     (defn- graph-file [graph-path]
       (cond
         graph-path (io/file graph-path)
         (.exists merged) merged
         :else seed))

     (defn run-cycle
       "One autonomous heartbeat: observe → analyze concentration → persist a content-addressed
       Datom transaction (graph + derived :concentration). cycle drives tx-id + as-of."
       [cycle graph-path log-path]
       (let [gf (graph-file graph-path)
             rows (edn/load-edn (str gf))               ; observe — OFFLINE (G7: no live fetch)
             g (edn/classify rows)
             [_md derived] (analyze/analyze g)           ; recursive BOM closure → :concentration
             datoms (into (k/graph-datoms rows) (k/derived-datoms derived))
             tx (k/make-tx datoms cycle (+ base-as-of cycle) (k/head-cid log-path))
             cid (k/append-tx tx log-path)]             ; PERSIST to append-only LOCAL kotoba log
         {:cycle cycle
          :products (count (:products g)) :parts (count (:parts g))
          :materials (count (:materials g)) :bom (count (:bom g))
          :concentration (count derived) :datoms (count datoms) :cid cid}))

     (defn run-autonomous [cycles graph-path log-path]
       (let [beats (mapv #(run-cycle % graph-path log-path) (range 1 (inc cycles)))]
         {:cycles cycles :beats beats
          :log-length (count (k/read-log log-path))
          :head-cid (k/head-cid log-path)
          :chain (k/verify-chain log-path)}))

     (defn -main [& argv]
       (let [argv (vec argv)
             idx (fn [flag] (let [i (.indexOf argv flag)] (when (>= i 0) i)))
             cycles (if-let [i (idx "--cycles")] (Long/parseLong (nth argv (inc i))) 3)
             log-path (if-let [i (idx "--log")] (nth argv (inc i)) log-default)
             graph-path (when-let [i (idx "--graph")] (nth argv (inc i)))]
         (when (and (idx "--fresh") (.exists (io/file log-path))) (.delete (io/file log-path)))
         (let [res (run-autonomous cycles graph-path log-path)]
           (println (str "# uchiwake — AUTONOMOUS product-BOM resilience over the kotoba Datom log "
                         "(offline ingest, LOCAL persist; live GS1/GLEIF/OFF universe + live-node "
                         "push stays G7-gated)\n"))
           (doseq [bt (:beats res)]
             (println (str "  ♥ cycle " (:cycle bt) ": " (:products bt) " products / " (:parts bt)
                           " parts / " (:materials bt) " materials / " (:bom bt) " BOM edges · "
                           "concentration " (:concentration bt) " +" (:datoms bt) " datoms → cid "
                           (subs (:cid bt) 0 (min 14 (count (:cid bt)))) "…")))
           (let [ch (:chain res)]
             (println (str "\n  log: " (:log-length res) " tx · head "
                           (subs (:head-cid res) 0 (min 14 (count (:head-cid res)))) "… · chain "
                           (if (:ok ch) "OK ✓" (str "BROKEN at " (:broken-at ch)))
                           " · public product facts + :synthesized concentration, resilience map "
                           "not a target (G2/G4)"))))))))
