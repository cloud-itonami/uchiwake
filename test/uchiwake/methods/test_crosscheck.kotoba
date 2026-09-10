;; test_crosscheck.clj — uchiwake⇄kabuto coverage-linkage crosscheck, byte-parity with crosscheck.py.
;; Auto-discovered by `bb test:actors` (path-matching ns). ADR-2606142300.
(ns uchiwake.methods.test-crosscheck
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.java.io :as io]
            [uchiwake.methods.uchiwake-edn :as edn]
            [uchiwake.methods.crosscheck :as cc]))

;; Same loading contract as crosscheck/-main: uchiwake's own seed graph classified,
;; kabuto's seed rows read as-is (crosscheck itself indexes them via load-kabuto-rows).
(def ^:private here
  (-> *file* io/file .getAbsoluteFile .getParentFile .getParentFile))
(def ^:private seed (io/file here "data" "seed-products.kotoba.edn"))
(def ^:private kabuto-seed
  (io/file (.getParentFile here) "kabuto" "data" "seed-public-companies.kotoba.edn"))
(def ^:private g (edn/classify (edn/read-edn (slurp (str seed)))))
(def ^:private kabuto-rows (when (.isFile kabuto-seed) (edn/read-edn (slurp (str kabuto-seed)))))
(def ^:private s (cc/crosscheck g kabuto-rows))

(deftest linkage-headline
  (testing "measured uchiwake→kabuto linkage matches crosscheck.py exactly (golden)"
    (is (true? (get s "kabuto_available")))
    (is (= 1719 (get s "kabuto_company_count")))
    (is (= 26 (get s "distinct_company_refs")))
    (is (= 21 (get s "distinct_resolved")))
    (is (== 80.8 (get s "linkage_pct")))))                       ; round(100*21/26, 1)

(deftest by-kind-counts
  (testing "per-reference-kind total/resolved (golden)"
    (is (= {"total" 10 "resolved" 8}  (get-in s ["by_kind" "brand-owner"])))
    (is (= {"total" 15 "resolved" 14} (get-in s ["by_kind" "bom-supplier"])))
    (is (= {"total" 4  "resolved" 4}  (get-in s ["by_kind" "process-operator"])))
    (is (= {"total" 2  "resolved" 2}  (get-in s ["by_kind" "logistics-carrier"])))
    (is (= {"total" 3  "resolved" 0}  (get-in s ["by_kind" "ownership-child"])))
    (is (= {"total" 3  "resolved" 1}  (get-in s ["by_kind" "ownership-parent"])))))

(deftest ownership-rollup-and-gap
  (testing "子会社 rollup recovers a subsidiary→ultimate-parent link; unresolved is the honest gap (G5)"
    (is (= [{"ref" "org.corp.jp.sony-semicon" "ultimate" "org.corp.jp.sony" "kind" "bom-supplier"}
            {"ref" "org.corp.jp.sony-semicon" "ultimate" "org.corp.jp.sony" "kind" "ownership-child"}]
           (get s "rollup_recovered")))
    (is (= ["org.corp.it.ferrero" "org.corp.jp.sony-semicon" "org.corp.lu.ferrero-intl"
            "org.corp.us.coca-cola" "org.corp.us.coca-cola-na"]
           (get s "unresolved")))))

(deftest reverse-coverage
  (testing "reverse coverage (how much of kabuto has product-level BOM detail) — the honest figure"
    (let [r (get s "reverse")]
      (is (= 233 (get r "kabuto_supply_companies")))
      (is (= 15 (get r "with_product_detail")))
      (is (== 6.438 (get r "reverse_pct")))                      ; round(100*15/233, 3)
      (is (== 1.163 (get r "all_company_coverage_pct")))         ; round(100*covered/1719, 3)
      ;; worklist = top-15 highest-out-degree kabuto suppliers with NO product detail.
      ;; assert length + that the top entry has the max out-degree (tie-order is impl-specific).
      (is (= 15 (count (get r "worklist"))))
      (is (= 3 (get (first (get r "worklist")) "supply_out_degree")))
      (is (apply >= (map #(get % "supply_out_degree") (get r "worklist")))))))   ; sorted desc by out-degree
