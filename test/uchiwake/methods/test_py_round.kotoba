#!/usr/bin/env bb
;; uchiwake 内訳 — gold-standard parity validation of the Python-round() mirror.
;; Run:  bb test/uchiwake/methods/test_py_round.cljc
(ns uchiwake.methods.test-py-round
  "Gold-standard parity validation of py-round / py-round-2 — uchiwake's CPython round() mirror used
  to round BOM quantities and measurements. Pins it against CPython's round(x, n): banker's rounding
  (round-half-even) AND the float-representation quirks, since a drift to naive round-half-up would
  silently mis-round product quantities. Every case below was verified equal to `python3 -c
  'round(x, n)'`."
  (:require [uchiwake.methods.uchiwake-edn :as u]
            [clojure.test :refer [deftest is run-tests]]))

(deftest round-half-even-at-integer-places
  ;; banker's rounding: a .5 tie goes to the EVEN neighbour (≠ naive half-up)
  (is (= 2.0 (u/py-round 2.5 0)) "2.5 → 2 (even), not 3")
  (is (= 4.0 (u/py-round 3.5 0)) "3.5 → 4 (even)")
  (is (= 0.0 (u/py-round 0.5 0)) "0.5 → 0 (even)")
  (is (= 2.0 (u/py-round 1.5 0)) "1.5 → 2 (even)"))

(deftest float-representation-quirks-match-cpython
  ;; the classic cases where the decimal literal is not exactly representable in binary float
  (is (= 2.67 (u/py-round 2.675 2)) "2.675 is really 2.6749… → 2.67")
  (is (= 1.0  (u/py-round 1.005 2)) "1.005 is really 1.00499… → 1.0")
  (is (= 2.35 (u/py-round 2.345 2)) "2.345 → 2.35"))

(deftest rounds-to-the-requested-number-of-places
  (is (= 3.142 (u/py-round 3.14159 3)))
  (is (= 2.7183 (u/py-round 2.7182818 4)))
  (is (= -2.35 (u/py-round -2.345 2)) "negatives round with the same rule"))

(deftest py-round-2-is-py-round-at-two-places
  (is (= (u/py-round 2.675 2) (u/py-round-2 2.675)))
  (is (= 0.12 (u/py-round-2 0.125)) "half-even at 2 dp: 0.125 (exactly representable) → 0.12 (even)"))
