; Copyright (c) 2017-present Walmart, Inc.
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

(ns com.walmartlabs.lacinia.complexity-analysis-test 
  (:require
   [clojure.test :refer [deftest is run-test testing]]
   [com.walmartlabs.lacinia :refer [execute]]
   [com.walmartlabs.test-utils :as utils]))


(defn ^:private resolve-products
  [_ _ _]
  {:edges []
   :pageInfo {}})

(defn ^:private resolve-followings
  [_ _ _]
  {:edges []
   :pageInfo {}})

(defn ^:private resolve-reviews
  [_ _ _]
  {:edges []
   :pageInfo {}})

(defn ^:private resolve-likers
  [_ _ _]
  {:edges []
   :pageInfo {}})

(defn ^:private resolve-node
  [_ _ _]
  nil)

(defn ^:private resolve-name
  [_ _ _]
  "name")

(defn ^:private resolve-rooot
  [_ _ _]
  nil)

(def ^:private schema
  (utils/compile-schema "complexity-analysis-error.edn"
                        {:resolve-products resolve-products
                         :resolve-followings resolve-followings
                         :resolve-reviews resolve-reviews
                         :resolve-likers resolve-likers
                         :resolve-node resolve-node
                         :resolve-name resolve-name
                         :resolve-root resolve-rooot}))

(defn ^:private q [query variables]
  (utils/simplify (execute schema query variables nil {:analyze-query true})))

(deftest test-complexity-analysis
  (testing "It is possible to calculate the complexity of a query in the Relay connection spec 
            by taking into account both named fragments and inline fragments."
    (is (= {:data {:node nil}
            :extensions {:analysis {:complexity 32
                                    :max-depth 4}}}
           (q "query ProductDetail($productId: ID){
                 node(id: $productId) {
                   ... on Product {
                     ...ProductLikersFragment
                     seller{
                       id
                       products(first: 5){
                         edges{
                           node{
                             id
                           }
                         }
                       }
                     }
                     reviews(first: 5){
                       edges{
                         node{
                           id
                           author{
                             id
                             name
                           }
                           product{
                             id
                           }
                         }
                       }
                     }
                   }
                 }
               }
               fragment ProductLikersFragment on Product {
                 likers(first: 10){
                   edges{
                     node{
                       ... on Seller{
                         id
                       }
                       ... on Buyer{
                         id
                       }
                     }
                   }
                 }
               }" {:productId "id"}))))
  (testing "If no arguments are passed in the query, the calculation uses the default value defined in the schema."
    (is (= {:data {:node nil}
            :extensions {:analysis {:complexity 22
                                    :max-depth 4}}}
           (q "query ProductDetail($productId: ID){
                 node(id: $productId) {
                   ... on Product {
                     ...ProductLikersFragment
                     seller{
                       id
                       products(first: 5){
                         edges{
                           node{
                             id
                           }
                         }
                       }
                     }
                     reviews(first: 5){
                       edges{
                         node{
                           id
                           author{
                             id
                           }
                         }
                       }
                     }
                   }
                 }
               }
               fragment ProductLikersFragment on Product {
                 likers{
                   edges{
                     node{
                       ... on Seller{
                         id
                       }
                       ... on Buyer{
                         id
                       }
                     }
                   }
                 }
               }" {:productId "id"}))))
  (testing "If return type of root query is scala, then complexity is 0"
    (is (= {:data {:root nil}
            :extensions {:analysis {:complexity 0
                                    :max-depth 0}}}
           (q "query root{
                 root
               }" nil)))))

(comment
  (run-test test-complexity-analysis))
