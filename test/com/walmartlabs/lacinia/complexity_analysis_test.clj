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
   [clojure.test :refer [deftest is run-test]]
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
  {:edges []
   :pageInfo {}})

(def ^:private schema
  (utils/compile-schema "complexity-analysis-error.edn"
                        {:resolve-products resolve-products
                         :resolve-followings resolve-followings
                         :resolve-reviews resolve-reviews
                         :resolve-likers resolve-likers
                         :resolve-node resolve-node}))

(defn ^:private q [query variables]
  (utils/simplify (execute schema query variables nil {:max-complexity 10})))

(deftest over-complexity-analysis
  (is (= {:errors {:message "Over max complexity! Current number of resources to be queried: 22"}}
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
                     id
                   }
                 }
               }
             }" {:productId "1"}))))

(comment
  (run-test over-complexity-analysis))
