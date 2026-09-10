(require '[clojure.test :as test]
         'uchiwake.tests.test-autorun)

(let [result (test/run-tests 'uchiwake.tests.test-autorun)]
  (println "==> uchiwake integration:" (select-keys result [:test :pass :fail :error]))
  (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
