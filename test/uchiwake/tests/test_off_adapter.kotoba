(ns uchiwake.tests.test-off-adapter
  "uchiwake 内訳 — Open Food Facts bulk-ingest adapter tests (ADR-2606081800).
  1:1 Clojure port of tests/test_off_adapter.py (clojure.test).

  Ports every assertion of TestOffAdapter: the bad-GTIN record is skipped, products are
  keyed on the normalized GTIN-14 and marked :representative (G5, OFF is crowd-sourced),
  ingredients become :bom.edge with %mass, known ingredients map to canonical materials,
  materials dedup across products, and the emitted EDN re-parses.

  The OFF fixture is loaded via a *file*-relative path behind #?(:clj) (kanae/test_pipeline
  pattern). assertAlmostEqual → (is (== … …)) on the rounded double. The __main__ demo is
  omitted (a non-pure entry point)."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [kotoba.lang.text :as str]
            [clojure.java.io :as io]
            [uchiwake.methods.uchiwake-edn :as uedn]
            [uchiwake.methods.adapters.openfoodfacts :as off]))

;; ── minimal JSON reader (subset sufficient for the OFF fixture; danjo pattern) ─
;; maps string-keyed, integers → long, floats → double, literals → true/false/nil —
;; the same shapes Python json.loads produces.
(declare json-value)

(defn- skip-ws [^String s i]
  (loop [i i]
    (if (and (< i (count s)) (contains? #{\space \tab \newline \return} (nth s i)))
      (recur (inc i)) i)))

(defn- json-string [^String s i]
  (loop [i (inc i), sb (StringBuilder.)]
    (let [c (nth s i)]
      (cond
        (= c \") [(.toString sb) (inc i)]
        (= c \\)
        (let [e (nth s (inc i))]
          (case e
            \" (do (.append sb \") (recur (+ i 2) sb))
            \\ (do (.append sb \\) (recur (+ i 2) sb))
            \/ (do (.append sb \/) (recur (+ i 2) sb))
            \b (do (.append sb \backspace) (recur (+ i 2) sb))
            \f (do (.append sb \formfeed) (recur (+ i 2) sb))
            \n (do (.append sb \newline) (recur (+ i 2) sb))
            \r (do (.append sb \return) (recur (+ i 2) sb))
            \t (do (.append sb \tab) (recur (+ i 2) sb))
            \u (let [cp (Integer/parseInt (subs s (+ i 2) (+ i 6)) 16)]
                 (.append sb (char cp)) (recur (+ i 6) sb))
            (do (.append sb e) (recur (+ i 2) sb))))
        :else (do (.append sb c) (recur (inc i) sb))))))

(defn- json-number [^String s i]
  (let [end (loop [j i]
              (if (and (< j (count s))
                       (contains? #{\- \+ \. \e \E \0 \1 \2 \3 \4 \5 \6 \7 \8 \9} (nth s j)))
                (recur (inc j)) j))
        tok (subs s i end)]
    [(if (re-find #"[.eE]" tok) (Double/parseDouble tok) (Long/parseLong tok)) end]))

(defn- json-array [^String s i]
  (loop [i (skip-ws s (inc i)), out []]
    (if (= (nth s i) \])
      [out (inc i)]
      (let [[v i] (json-value s i)
            i (skip-ws s i)
            out (conj out v)]
        (if (= (nth s i) \,)
          (recur (skip-ws s (inc i)) out)
          [out (inc i)])))))

(defn- json-object [^String s i]
  (loop [i (skip-ws s (inc i)), out {}]
    (if (= (nth s i) \})
      [out (inc i)]
      (let [[k i] (json-string s i)
            i (inc (skip-ws s i))           ; skip ':'
            [v i] (json-value s (skip-ws s i))
            i (skip-ws s i)
            out (assoc out k v)]
        (if (= (nth s i) \,)
          (recur (skip-ws s (inc i)) out)
          [out (inc i)])))))

(defn- json-value [^String s i]
  (let [i (skip-ws s i)
        c (nth s i)]
    (cond
      (= c \") (json-string s i)
      (= c \{) (json-object s i)
      (= c \[) (json-array s i)
      (= c \t) [true (+ i 4)]
      (= c \f) [false (+ i 5)]
      (= c \n) [nil (+ i 4)]
      :else (json-number s i))))

(defn- read-json [^String s] (first (json-value s (skip-ws s 0))))

(def ^:private sample-path
  #?(:clj (io/file "." "wire" "data" "openfoodfacts.sample.json")
     :cljs nil))

(def records #?(:clj (read-json (slurp sample-path)) :cljs []))
(def normalized (off/normalize-dataset records))
(def datoms (first normalized))
(def stats (second normalized))

;; ── tests ────────────────────────────────────────────────────────────────────
(deftest test-bad-gtin-record-skipped
  ;; 4 records in, 1 has a wrong check digit → 3 admitted, 1 skipped
  (is (= 3 (get stats "products_ok")))
  (is (= 1 (get stats "skipped_bad_gtin"))))

(deftest test-products-keyed-on-normalized-gtin14
  (let [prods (filter #(contains? % ":product/id") datoms)]
    (is (= 3 (count prods)))
    (doseq [p prods]
      (is (= 14 (count (get p ":product/gtin"))))
      (is (true? (uedn/gtin-check-digit-ok (get p ":product/gtin"))))
      (is (= ":representative" (get p ":product/sourcing"))))))  ; OFF is crowd-sourced

(deftest test-ingredients-become-bom-edges-with-mass
  (let [edges (filter #(contains? % ":bom.edge/id") datoms)]
    (is (seq edges))
    ;; Nutella sugar edge carries the 56% estimate
    (let [sugar (filter #(and (= (get % ":bom.edge/parent") "gtin.03017620422003")
                              (= (get % ":bom.edge/child") "mat.sugar"))
                        edges)]
      (is (= 1 (count sugar)))
      (is (== 56.0 (get (first sugar) ":bom.edge/qty")))
      (is (= "%mass" (get (first sugar) ":bom.edge/qty-unit"))))))

(deftest test-known-ingredients-map-to-canonical-materials
  (let [mat-ids (set (for [d datoms :when (contains? d ":material/id")] (get d ":material/id")))]
    (doseq [canon ["mat.sugar" "mat.cocoa" "mat.palm-oil" "mat.water" "mat.co2" "mat.milk-powder"]]
      (is (contains? mat-ids canon)))))

(deftest test-materials-deduped-across-products
  (let [ids (for [d datoms :when (contains? d ":material/id")] (get d ":material/id"))]
    (is (= (count ids) (count (set ids))))))  ; sugar appears in all 3, emitted once

(deftest test-output-is-valid-edn-loadable
  (testing "to-edn output re-parses via the uchiwake EDN reader (_parse(_tokens(edn)))"
    (let [edn (off/to-edn datoms)
          parsed (uedn/read-edn edn)]
      (is (vector? parsed))
      (is (= 3 (count (filter #(and (map? %) (contains? % ":product/id")) parsed)))))))
