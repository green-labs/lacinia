(ns com.walmartlabs.lacinia.repeatable-directives-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.walmartlabs.lacinia.schema :as schema]))

(deftest schema-compile-time-validation-test
  (testing "Non-repeatable directive used multiple times in schema definition should fail"
    (let [bad-schema {:directive-defs
                      {:rateLimit {:locations #{:field-definition}
                                   :repeatable false
                                   :args {:limit {:type 'Int}}}}
                      :queries
                      {:user {:type 'String
                              :directives [{:directive-type :rateLimit :directive-args {:limit 10}}
                                           {:directive-type :rateLimit :directive-args {:limit 20}}]
                              :resolve (constantly "test-user")}}}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"rateLimit.*non-repeatable.*used 2 times"
           (schema/compile bad-schema)))))

  (testing "Repeatable directive used multiple times in schema definition should succeed"
    (let [good-schema {:directive-defs
                       {:cache {:locations #{:field-definition}
                                :repeatable true
                                :args {:ttl {:type 'Int}}}}
                       :queries
                       {:user {:type 'String
                               :directives [{:directive-type :cache :directive-args {:ttl 60}}
                                            {:directive-type :cache :directive-args {:ttl 120}}]
                               :resolve (constantly "test-user")}}}]
      (is (some? (schema/compile good-schema))))))
