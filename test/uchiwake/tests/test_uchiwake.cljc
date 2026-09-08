(ns uchiwake.tests.test-uchiwake
  "uchiwake 内訳 — invariant + correctness tests (ADR-2606081800).
  1:1 Clojure port of tests/test_uchiwake.py (clojure.test).

  Ports every PURE assertion: seed loads + schema + sourcing honesty (G5), GTIN normalize +
  GS1 mod-10 check-digit validity + the seed GTIN gate (G1), BOM integrity + criticality
  bounds + material closure, ownership ultimate-parent rollup + cycle guard, the analyzer
  report/derived contract (incl. G5 derived flag + the G2 resilience-not-target-list framing),
  and the milk-powder-reachable-from-KitKat expanded-seed assertion.

  DEFERRED (depend on the unported crosscheck.py + the off/openfoodfacts adapter — a separate
  unit, mirroring the inochi/rasen precedent): TestCrosscheck (5 assertions in test_uchiwake.py)
  and the whole test_off_adapter.py suite. They are autorun/crosscheck-dependent and are noted
  in the port report, not silently dropped."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [kotoba.lang.text :as str]
            [clojure.set]
            [clojure.java.io :as io]
            [uchiwake.methods.uchiwake-edn :as uedn]
            [uchiwake.methods.analyze :as A]))

(def actor-dir (io/file "."))
(def seed (io/file actor-dir "data" "seed-products.kotoba.edn"))
(def schema (io/file actor-dir "contracts" "schemas" "product-bom-ontology.kotoba.edn"))

(defn load-rows [] (uedn/read-edn (slurp seed)))
(defn classify-seed [] (uedn/classify (load-rows)))

;; ── TestSeedLoads ────────────────────────────────────────────────────────────
(deftest test-nonempty
  (let [g (classify-seed)]
    (is (seq (:products g)))
    (is (seq (:materials g)))
    (is (seq (:bom g)))))

(deftest test-schema-loads
  (let [s (uedn/read-edn (slurp schema))]
    (is (map? s))
    (is (= "2606081800" (get s ":schema/adr")))))

(deftest test-every-node-has-sourcing
  (testing "G5: every node/edge carries an explicit :*/sourcing keyword."
    (doseq [r (load-rows) :when (map? r)]
      (let [srcing (for [[k v] r :when (str/ends-with? k "/sourcing")] v)]
        (is (seq srcing) (str "missing sourcing on " r))
        (doseq [v srcing]
          (is (contains? #{":authoritative" ":representative" ":synthesized"} v)))))))

;; ── TestGtin ───────────────────────────────────────────────────────────────── (GTIN mod-10, do-not-weaken)
(deftest test-normalize-pads-to-14
  (is (= "05449000000996" (uedn/normalize-gtin "5449000000996")))
  (is (= 14 (count (uedn/normalize-gtin "5449000000996")))))

(deftest test-real-gtin-check-digits-valid
  ;; Coca-Cola 330ml + Nutella 750g — real public EAN-13s with valid GS1 mod-10 check digits.
  (is (true? (uedn/gtin-check-digit-ok "5449000000996")))
  (is (true? (uedn/gtin-check-digit-ok "3017620422003"))))

(deftest test-bad-check-digit-rejected
  (is (false? (uedn/gtin-check-digit-ok "5449000000997"))))

(deftest test-seed-gtins-valid
  (let [g (classify-seed)
        products (:products g)]
    (doseq [pid (uedn/keys-in-order products)]
      (let [gt (get-in products [pid ":product/gtin"])]
        (when gt
          (is (true? (uedn/gtin-check-digit-ok gt))
              (str pid " GTIN check digit invalid: " gt)))))))

;; ── TestBomIntegrity ───────────────────────────────────────────────────────────
(deftest test-bom-edges-reference-known-nodes
  (let [g (classify-seed)
        known (clojure.set/union (set (uedn/keys-in-order (:products g)))
                                 (set (uedn/keys-in-order (:parts g)))
                                 (set (uedn/keys-in-order (:materials g))))]
    (doseq [e (:bom g)]
      (is (contains? known (get e ":bom.edge/parent")) (str "dangling parent " e))
      (is (contains? known (get e ":bom.edge/child")) (str "dangling child " e)))))

(deftest test-criticality-bounded
  (doseq [e (:bom (classify-seed))]
    (let [c (get e ":bom.edge/criticality")]
      (when (some? c)
        (is (>= c 0.0))
        (is (<= c 1.0))))))

(deftest test-materials-reachable-from-products
  (testing "Every product must decompose down to at least one raw material (BOM closure)."
    (let [g (classify-seed)
          child-idx (A/bom-children-index (:bom g))]
      (doseq [pid (uedn/keys-in-order (:products g))]
        (is (seq (A/all-materials-reachable pid child-idx))
            (str "product " pid " reaches no raw material"))))))

;; ── TestOwnershipRollup ────────────────────────────────────────────────────────
(defn ownership-idx [g]
  (reduce (fn [m o] (assoc m (get o ":company.ownership/child")
                           (get o ":company.ownership/parent")))
          {} (:ownership g)))

(deftest test-subsidiary-rolls-to-ultimate-parent
  (let [idx (ownership-idx (classify-seed))]
    (is (= "org.corp.jp.sony" (A/resolve-ultimate-parent "org.corp.jp.sony-semicon" idx)))
    (is (= "org.corp.lu.ferrero-intl" (A/resolve-ultimate-parent "org.corp.it.ferrero" idx)))))

(deftest test-unowned-company-is-its-own-parent
  (let [idx (ownership-idx (classify-seed))]
    (is (= "org.corp.tw.tsmc" (A/resolve-ultimate-parent "org.corp.tw.tsmc" idx)))))

(deftest test-rollup-terminates-on-cycle
  (let [cyc {"a" "b" "b" "a"}]
    ;; must not infinite-loop; returns some node within depth guard
    (is (contains? #{"a" "b"} (A/resolve-ultimate-parent "a" cyc)))))

;; ── TestAnalyzeRuns ────────────────────────────────────────────────────────────
(deftest test-analyze-produces-report-and-derived
  (let [[md derived] (A/analyze (classify-seed))]
    (is (str/includes? md "uchiwake"))
    (is (str/includes? md "Material dependence"))
    (is (seq derived))
    (doseq [d derived]
      (is (get d ":concentration/derived")))))  ; G5: flagged derived

(deftest test-g2-no-target-framing
  (testing "G2: report is framed as resilience, never a target-list."
    (let [[md _] (A/analyze (classify-seed))]
      (is (str/includes? md "RESILIENCE"))
      (is (not (str/includes? (str/replace md "never a target-list" "") "target-list,"))))))

;; ── TestExpandedSeed ───────────────────────────────────────────────────────────
(deftest test-milk-powder-reachable-from-kitkat
  (let [g (classify-seed)
        idx (A/bom-children-index (:bom g))
        mats (A/all-materials-reachable "gtin.07613035044289" idx)]
    (is (some #{"mat.milk-powder"} mats))))
