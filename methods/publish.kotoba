#!/usr/bin/env nbb
;; self-publish wrapper → shared kototama publish.cljs (ADR-2607173000)
(def path (js/require "node:path"))
(def cp (js/require "node:child_process"))
(def fs (js/require "node:fs"))
(def root (.resolve path (.dirname path *file*) ".."))
(def candidates
  [(.resolve path root "../../com-junkawasaki/kototama/lib/actor/publish.cljs")
   (.resolve path root "../../kotoba-lang/kototama/lib/actor/publish.cljs")])
(def runtime (or (first (filter #(.existsSync fs %) candidates)) (first candidates)))
(when-not (.existsSync fs runtime)
  (binding [*out* *err*] (println "missing publish.cljs at" runtime))
  (.exit js/process 1))
(let [args (into ["nbb" runtime "--actor" root] (vec *command-line-args*))
      r (.spawnSync cp (first args) (to-array (rest args)) #js {:stdio "inherit"})]
  (.exit js/process (or (.-status r) 1)))
