(require '[clojure.test :as test])

(def test-namespaces
  '[uchiwake.tests.test-off-adapter
    uchiwake.tests.test-uchiwake
    uchiwake.methods.test-bridge
    uchiwake.methods.test-py-round
    uchiwake.murakumo-test
    uchiwake.repository-contract-test])

(doseq [namespace test-namespaces] (require namespace))
(let [result (apply test/run-tests test-namespaces)]
  (println "==> uchiwake:" (select-keys result [:test :pass :fail :error]))
  (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
