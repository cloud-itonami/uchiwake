;; mesh.clj — uchiwake 内訳 KOTOBA Mesh entry component (Clojure / kotoba-clj).
;;
;; The mesh-hosting face of actor:uchiwake (world product BOM / GTIN KG). Observatory
;; on-kse pattern (ADR-2606230001 §4): observes product→part decomposition edges as
;; Datom assertions and derives BOM concentration via Datalog. The full GTIN /
;; ultimate-parent rollup stays in the actor's existing methods.
;;
;; Posture: a resilience map, never a target-list or a clone recipe (disclosed
;; product CLASSES only).
;; host-imports: kqe-assert! / kqe-query → kotoba:kais/kqe (needs cap/kqe)
(ns uchiwake)

(defn observe []
  ;; observe — product → part → material decomposition (disclosed classes).
  (kqe-assert! "uchiwake" "device" "contains" "battery-cell")
  (kqe-assert! "uchiwake" "battery-cell" "contains" "lithium")
  (kqe-assert! "uchiwake" "device" "contains" "soc")
  ;; derive — BOM material concentration → resilience map (Datalog).
  (kqe-query "resilience(?p) :- contains(?p)."))

(defn run [ctx] (observe))
(defn on-kse [topic payload] (observe))
