(ns crux.api-test
  (:require [clojure.test :as t]
            [crux.api :as api]
            [crux.codec :as c]
            [crux.fixtures :as f])
  (:import clojure.lang.LazySeq
           java.util.Date
           crux.api.StandaloneSystem))

(t/use-fixtures :once f/with-embedded-kafka-cluster)
(t/use-fixtures :each f/with-each-api-implementation)

(t/deftest test-can-use-api-to-access-crux
  (t/testing "status"
    (t/is (= {:crux.zk/zk-active? (not (instance? StandaloneSystem f/*api*))
              :crux.kv/kv-backend "crux.kv.rocksdb.RocksKv"
              :crux.kv/estimate-num-keys 0
              :crux.tx-log/tx-time nil}
             (dissoc (api/status f/*api*) :crux.kv/size))))

  (t/testing "transaction"
    (let [business-time (Date.)
          submitted-tx (api/submit-tx f/*api* [[:crux.tx/put :ivan {:crux.db/id :ivan :name "Ivan"} business-time]])]
      (t/is (true? (api/submitted-tx-updated-entity? f/*api* submitted-tx :ivan)))

      (let [status-map (api/status f/*api*)]
        (t/is (pos? (:crux.kv/estimate-num-keys status-map)))
        (t/is (= (:crux.tx/tx-time submitted-tx) (:crux.tx-log/tx-time status-map))))

      (t/testing "query"
        (t/is (= #{[:ivan]} (api/q (api/db f/*api*) '{:find [e]
                                                      :where [[e :name "Ivan"]]})))
        (t/is (= #{} (api/q (api/db f/*api* #inst "1999") '{:find [e]
                                                            :where [[e :name "Ivan"]]})))

        (t/testing "malformed query"
          (t/is (thrown-with-msg? Exception
                                  #"(status 400|Invalid input)"
                                  (api/q (api/db f/*api*) '{:find [e]})))))

      (t/testing "query with streaming result"
        (let [db (api/db f/*api*)]
          (with-open [snapshot (api/new-snapshot db)]
            (let [result (api/q db snapshot '{:find [e]
                                              :where [[e :name "Ivan"]]})]
              (t/is (instance? LazySeq result))
              (t/is (not (realized? result)))
              (t/is (= '([:ivan]) result))
              (t/is (realized? result))))))

      (t/testing "entity"
        (t/is (= {:crux.db/id :ivan :name "Ivan"} (api/entity (api/db f/*api*) :ivan)))
        (t/is (nil? (api/entity (api/db f/*api* #inst "1999") :ivan))))

      (t/testing "entity-tx, document and history"
        (let [entity-tx (api/entity-tx (api/db f/*api*) :ivan)]
          (t/is (= (merge submitted-tx
                          {:crux.db/id (str (c/new-id :ivan))
                           :crux.db/content-hash (str (c/new-id {:crux.db/id :ivan :name "Ivan"}))
                           :crux.db/business-time business-time})
                   entity-tx))
          (t/is (= {:crux.db/id :ivan :name "Ivan"} (api/document f/*api* (:crux.db/content-hash entity-tx))))
          (t/is (= [entity-tx] (api/history f/*api* :ivan)))

          (t/is (nil? (api/entity-tx (api/db f/*api* #inst "1999") :ivan))))))))