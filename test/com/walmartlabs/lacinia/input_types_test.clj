; Copyright (c) 2021-present Walmart, Inc.
;
; Licensed under the Apache License, Version 2.0 (the "License")
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;     http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.

(ns com.walmartlabs.lacinia.input-types-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.walmartlabs.test-utils :refer [execute compile-schema-injected]]))

(deftest mix-of-literals-and-dynamics
  (let [resolver (fn [_ args _]
                   (is (= {:filter {:or [{:color {:equals "blue"}}
                                         {:color {:equals "red"}}]}}
                          args))
                   {:nodes [{:color "blue"}]})
        schema (compile-schema-injected "dyn-args-schema.edn"
                                        {:queries/cars resolver})
        query "query($color: String) {
               cars(filter: {or: [{color: {equals: \"blue\"}},
                                  {color: {equals: $color}}]}) {
                 nodes {
                   color
                 }
               }
             }"
        args {:color "red"}]
    (is (= {:data
            {:cars
             {:nodes [{:color "blue"}]}}}
           (execute schema query args nil)))))

(deftest one-of-input-object
  (let [resolver (fn [_ args _]
                   ;; TODO: arguments validation
                   {})
        schema (compile-schema-injected "input-object-one-of-schema.edn"
                                        {:queries/user resolver})]
    (testing "provide exactly one of the fields"
      (let [query "{
                     tom: user(by: {
                       id: 1
                     }) {
                       name
                     }

                     jerry: user(by: {
                       email: \"jerry@warnerbros.com\"
                     }) {
                       name
                     }
                   }"
            args {}]
       (is (= {:data
               {:tom   {:name "Tom"}
                :jerry {:name "Jerry"}}}
              (execute schema query args nil)))))
    (testing "provide two values"
      (let [query "{
                     user(by: {
                       id: 1
                       email: \"jerry@warnerbros.com\"
                     }) {
                       name
                     }
                   }"
            args {}]
        (is (= {:data
                {:cars
                 {:nodes [{:color "blue"}]}}}
               (execute schema query args nil)))))))


(comment
  (require 'com.walmartlabs.lacinia.parser.schema)
  )