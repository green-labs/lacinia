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

(ns com.walmartlabs.introspection-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [com.walmartlabs.lacinia :as lacinia]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.util :as util]
            [com.walmartlabs.test-schema :refer [test-schema]]
            [com.walmartlabs.test-utils :as utils :refer [simplify]]))

(def compiled-schema (schema/compile test-schema))

(defn ^:private execute
  ([query]
   (execute compiled-schema query))
  ([schema query]
   (simplify (lacinia/execute schema query {} nil))))

(deftest simple-introspection-query
  (let [q "{ __type(name: \"human\") { kind name fields { name }}}"]
    (is (= {:data {:__type {:kind :OBJECT
                            :name "human"
                            :fields
                            (->> compiled-schema :human :fields keys
                                 (map name)
                                 sort
                                 (map #(hash-map :name %)))
                            }}}
           (execute q)))))

(deftest first-level-field-types
  (let [q "{ __type(name: \"human\") {
             kind
             name
             fields {
               name
               type {
                 kind
                 name
               }
             }
           }}"]
    (is (= {:data {:__type {:fields [{:name "appears_in"
                                      :type {:kind :LIST
                                             :name nil}}
                                     {:name "arch_enemy"
                                      :type {:kind :NON_NULL
                                             :name nil}}
                                     {:name "bar"
                                      :type {:kind :INTERFACE
                                             :name "character"}}
                                     {:name "best_friend"
                                      :type {:kind :INTERFACE
                                             :name "character"}}
                                     {:name "droids"
                                      :type {:kind :NON_NULL
                                             :name nil}}
                                     {:name "enemies"
                                      :type {:kind :LIST
                                             :name nil}}
                                     {:name "family"
                                      :type {:kind :NON_NULL
                                             :name nil}}
                                     {:name "foo"
                                      :type {:kind :NON_NULL
                                             :name nil}}
                                     {:name "forceSide"
                                      :type {:kind :OBJECT
                                             :name "force"}}
                                     {:name "friends"
                                      :type {:kind :LIST
                                             :name nil}}
                                     {:name "homePlanet"
                                      :type {:kind :SCALAR
                                             :name "String"}}
                                     {:name "id"
                                      :type {:kind :SCALAR
                                             :name "String"}}
                                     {:name "name"
                                      :type {:kind :SCALAR
                                             :name "String"}}
                                     {:name "primary_function"
                                      :type {:kind :LIST
                                             :name nil}}]
                            :kind :OBJECT
                            :name "human"}}}
           (execute q)))))

(deftest three-level-type-data
  (let [schema (schema/compile {:objects {:movie {:fields {:release_year {:type 'Int}
                                                           :sequel {:type :movie}
                                                           :title {:type '(non-null String)}
                                                           :actors {:type '(list (non-null String))}}}}})
        q "
        { __type(name: \"movie\") {
          fields {
            name
            type {
              kind
              name
              ofType {
                kind
                name
                ofType {
                  kind
                  name
                }
              }
            }
          }
        }}"]
    (is (= {:data {:__type {:fields [{:name "actors"
                                      :type {:kind :LIST
                                             :name nil
                                             :ofType {:kind :NON_NULL
                                                      :name nil
                                                      :ofType {:kind :SCALAR
                                                               :name "String"}}}}
                                     {:name "release_year"
                                      :type {:kind :SCALAR
                                             :name "Int"
                                             :ofType nil}}
                                     {:name "sequel"
                                      :type {:kind :OBJECT
                                             :name "movie"
                                             :ofType nil}}
                                     {:name "title"
                                      :type {:kind :NON_NULL
                                             :name nil
                                             :ofType {:kind :SCALAR
                                                      :name "String"
                                                      :ofType nil}}}]}}}
           (utils/execute schema q nil nil)))))

(deftest object-introspection-query
  (let [q "{ __type(name: \"droid\") { kind name interfaces { name }}}"]
    (is (= {:data {:__type {:kind :OBJECT
                            :name "droid"
                            :interfaces [{:name "character"}]}}}
           (execute q)))))

(deftest recursive-introspection-query
  (let [q "{ __type(name: \"character\") {
             kind
             name
             fields {
               name
               type {
                 kind
                 name
                 fields {
                   name
                   type {
                     kind
                     name
                     ofType { enumValues { name }}
                   }
                 }
               }
             }
           }}"]
    (is (= {:data {:__type {:fields [{:name "appears_in"
                                      :type {:fields nil
                                             :kind :LIST
                                             :name nil}}
                                     {:name "arch_enemy"
                                      :type {:fields nil
                                             :kind :NON_NULL
                                             :name nil}}
                                     {:name "bar"
                                      :type {:fields [{:name "appears_in"
                                                       :type {:kind :LIST
                                                              :name nil
                                                              :ofType {:enumValues [{:name "NEWHOPE"}
                                                                                    {:name "EMPIRE"}
                                                                                    {:name "JEDI"}]}}}
                                                      {:name "arch_enemy"
                                                       :type {:kind :NON_NULL
                                                              :name nil
                                                              :ofType {:enumValues nil}}}
                                                      {:name "bar"
                                                       :type {:kind :INTERFACE
                                                              :name "character"
                                                              :ofType nil}}
                                                      {:name "best_friend"
                                                       :type {:kind :INTERFACE
                                                              :name "character"
                                                              :ofType nil}}
                                                      {:name "droids"
                                                       :type {:kind :NON_NULL
                                                              :name nil
                                                              :ofType {:enumValues nil}}}
                                                      {:name "enemies"
                                                       :type {:kind :LIST
                                                              :name nil
                                                              :ofType {:enumValues nil}}}
                                                      {:name "family"
                                                       :type {:kind :NON_NULL
                                                              :name nil
                                                              :ofType {:enumValues nil}}}
                                                      {:name "foo"
                                                       :type {:kind :NON_NULL
                                                              :name nil
                                                              :ofType {:enumValues nil}}}
                                                      {:name "forceSide"
                                                       :type {:kind :OBJECT
                                                              :name "force"
                                                              :ofType nil}}
                                                      {:name "friends"
                                                       :type {:kind :LIST
                                                              :name nil
                                                              :ofType {:enumValues nil}}}
                                                      {:name "id"
                                                       :type {:kind :SCALAR
                                                              :name "String"
                                                              :ofType nil}}
                                                      {:name "name"
                                                       :type {:kind :SCALAR
                                                              :name "String"
                                                              :ofType nil}}]
                                             :kind :INTERFACE
                                             :name "character"}}
                                     {:name "best_friend"
                                      :type {:fields [{:name "appears_in"
                                                       :type {:kind :LIST
                                                              :name nil
                                                              :ofType {:enumValues [{:name "NEWHOPE"}
                                                                                    {:name "EMPIRE"}
                                                                                    {:name "JEDI"}]}}}
                                                      {:name "arch_enemy"
                                                       :type {:kind :NON_NULL
                                                              :name nil
                                                              :ofType {:enumValues nil}}}
                                                      {:name "bar"
                                                       :type {:kind :INTERFACE
                                                              :name "character"
                                                              :ofType nil}}
                                                      {:name "best_friend"
                                                       :type {:kind :INTERFACE
                                                              :name "character"
                                                              :ofType nil}}
                                                      {:name "droids"
                                                       :type {:kind :NON_NULL
                                                              :name nil
                                                              :ofType {:enumValues nil}}}
                                                      {:name "enemies"
                                                       :type {:kind :LIST
                                                              :name nil
                                                              :ofType {:enumValues nil}}}
                                                      {:name "family"
                                                       :type {:kind :NON_NULL
                                                              :name nil
                                                              :ofType {:enumValues nil}}}
                                                      {:name "foo"
                                                       :type {:kind :NON_NULL
                                                              :name nil
                                                              :ofType {:enumValues nil}}}
                                                      {:name "forceSide"
                                                       :type {:kind :OBJECT
                                                              :name "force"
                                                              :ofType nil}}
                                                      {:name "friends"
                                                       :type {:kind :LIST
                                                              :name nil
                                                              :ofType {:enumValues nil}}}
                                                      {:name "id"
                                                       :type {:kind :SCALAR
                                                              :name "String"
                                                              :ofType nil}}
                                                      {:name "name"
                                                       :type {:kind :SCALAR
                                                              :name "String"
                                                              :ofType nil}}]
                                             :kind :INTERFACE
                                             :name "character"}}
                                     {:name "droids"
                                      :type {:fields nil
                                             :kind :NON_NULL
                                             :name nil}}
                                     {:name "enemies"
                                      :type {:fields nil
                                             :kind :LIST
                                             :name nil}}
                                     {:name "family"
                                      :type {:fields nil
                                             :kind :NON_NULL
                                             :name nil}}
                                     {:name "foo"
                                      :type {:fields nil
                                             :kind :NON_NULL
                                             :name nil}}
                                     {:name "forceSide"
                                      :type {:fields [{:name "id"
                                                       :type {:kind :SCALAR
                                                              :name "String"
                                                              :ofType nil}}
                                                      {:name "members"
                                                       :type {:kind :LIST
                                                              :name nil
                                                              :ofType {:enumValues nil}}}
                                                      {:name "name"
                                                       :type {:kind :SCALAR
                                                              :name "String"
                                                              :ofType nil}}]
                                             :kind :OBJECT
                                             :name "force"}}
                                     {:name "friends"
                                      :type {:fields nil
                                             :kind :LIST
                                             :name nil}}
                                     {:name "id"
                                      :type {:fields nil
                                             :kind :SCALAR
                                             :name "String"}}
                                     {:name "name"
                                      :type {:fields nil
                                             :kind :SCALAR
                                             :name "String"}}]
                            :kind :INTERFACE
                            :name "character"}}}
           (execute q)))))

(deftest schema-introspection-query
  (let [q "{ __schema { types { name }}}"]
    ;; Note that schema types are explicitly absent.
    (is (= {:data {:__schema {:types [{:name "Boolean"}
                                      {:name "Date"}
                                      {:name "Float"}
                                      {:name "ID"}
                                      {:name "Int"}
                                      {:name "Mutation"}
                                      {:name "Query"}
                                      {:name "String"}
                                      ;; Subscription not present, because there are no defined subscriptions
                                      {:name "character"}
                                      {:name "droid"}
                                      {:name "echoArgs"}
                                      {:name "episode"}
                                      {:name "force"}
                                      {:name "galaxy_date"}
                                      {:name "human"}
                                      {:name "nestedInputObject"}
                                      {:name "testInputObject"}]}}}
           (execute q))))
  (let [q "{ __schema
             { types { name kind description }
               queryType { name kind fields { name }}
             }
           }"]
    ;; TODO: Should Query and Mutation appear?
    (is (= {:data {:__schema {:queryType {:fields [{:name "droid"}
                                                   {:name "echoArgs"}
                                                   {:name "hero"}
                                                   {:name "human"}
                                                   {:name "now"}]
                                          :kind :OBJECT
                                          :name "Query"}
                              :types [{:description nil
                                       :kind :SCALAR
                                       :name "Boolean"}
                                      {:description nil
                                       :kind :SCALAR
                                       :name "Date"}
                                      {:description nil
                                       :kind :SCALAR
                                       :name "Float"}
                                      {:description nil
                                       :kind :SCALAR
                                       :name "ID"}
                                      {:description nil
                                       :kind :SCALAR
                                       :name "Int"}
                                      {:description  nil
                                       :kind :OBJECT
                                       :name "Mutation"}
                                      {:description  nil
                                       :kind :OBJECT
                                       :name "Query"}
                                      {:description nil
                                       :kind :SCALAR
                                       :name "String"}
                                      {:description nil
                                       :kind :INTERFACE
                                       :name "character"}
                                      {:description nil
                                       :kind :OBJECT
                                       :name "droid"}
                                      {:description nil
                                       :kind :OBJECT
                                       :name "echoArgs"}
                                      {:description "The episodes of the original Star Wars trilogy."
                                       :kind :ENUM
                                       :name "episode"}
                                      {:description nil
                                       :kind :OBJECT
                                       :name "force"}
                                      {:description nil
                                       :kind :OBJECT
                                       :name "galaxy_date"}
                                      {:description nil
                                       :kind :OBJECT
                                       :name "human"}
                                      {:description nil
                                       :kind :INPUT_OBJECT
                                       :name "nestedInputObject"}
                                      {:description nil
                                       :kind :INPUT_OBJECT
                                       :name "testInputObject"}]}}}
           (execute q)))))

(deftest graphiql-introspection-query
  (let [q "query IntrospectionQuery {
            __schema {
              queryType { name }
              mutationType { name }
              types {
                ...FullType
              }
              directives {
                name
                description
                args {
                  ...InputValue
                }
              }
            }
          }
          fragment FullType on __Type {
            kind
            name
            description
            fields(includeDeprecated: true) {
              name
              description
              args {
                ...InputValue
              }
              type {
                ...TypeRef
              }
              isDeprecated
              deprecationReason
            }
            inputFields {
              ...InputValue
            }
            interfaces {
              ...TypeRef
            }
            enumValues(includeDeprecated: true) {
              name
              description
              isDeprecated
              deprecationReason
            }
            possibleTypes {
              ...TypeRef
            }
          }
          fragment InputValue on __InputValue {
            name
            description
            type { ...TypeRef }
            defaultValue
          }
          fragment TypeRef on __Type {
            kind
            name
            ofType {
              kind
              name
              ofType {
                kind
                name
                ofType {
                  kind
                  name
                }
              }
            }
          }"]
    ;; This giant test is difficult to maintain, and very subject to breakage if anyone
    ;; adds anything to the test-schema; perhaps we can change it to just ensure that
    ;; there are no errors, and break it up into smaller tests against small, ad-hoc
    ;; features?
    (is (= {:directives [{:args [{:defaultValue nil
                                  :description "Triggering argument for skip directive."
                                  :name "if"
                                  :type {:kind :NON_NULL
                                         :name nil
                                         :ofType {:kind :SCALAR
                                                  :name "Boolean"
                                                  :ofType nil}}}]
                          :description "Skip the selection only when the `if` argument is true."
                          :name "skip"}
                         {:args [{:defaultValue nil
                                  :description "Triggering argument for include directive."
                                  :name "if"
                                  :type {:kind :NON_NULL
                                         :name nil
                                         :ofType {:kind :SCALAR
                                                  :name "Boolean"
                                                  :ofType nil}}}]
                          :description "Include the selection only when the `if` argument is true."
                          :name "include"}
                         {:args [{:defaultValue "\"No longer supported\""
                                  :description "Reason for deprecation."
                                  :name "reason"
                                  :type {:kind :SCALAR
                                         :name "String"
                                         :ofType nil}}]
                          :description "Marks an element of a GraphQL schema as no longer supported."
                          :name "deprecated"}
                         {:args [{:defaultValue nil
                                  :description "The URL that specifies the behavior of this scalar."
                                  :name "url"
                                  :type {:kind :NON_NULL
                                         :name nil
                                         :ofType {:kind :SCALAR
                                                  :name "String"
                                                  :ofType nil}}}]
                          :description "Exposes a URL that specifies the behavior of this scalar."
                          :name "specifiedBy"}]
            :mutationType {:name "Mutation"}
            :queryType {:name "Query"}
            :types [{:description nil
                     :enumValues nil
                     :fields nil
                     :inputFields nil
                     :interfaces nil
                     :kind :SCALAR
                     :name "Boolean"
                     :possibleTypes nil}
                    {:description nil
                     :enumValues nil
                     :fields nil
                     :inputFields nil
                     :interfaces nil
                     :kind :SCALAR
                     :name "Date"
                     :possibleTypes nil}
                    {:description nil
                     :enumValues nil
                     :fields nil
                     :inputFields nil
                     :interfaces nil
                     :kind :SCALAR
                     :name "Float"
                     :possibleTypes nil}
                    {:description nil
                     :enumValues nil
                     :fields nil
                     :inputFields nil
                     :interfaces nil
                     :kind :SCALAR
                     :name "ID"
                     :possibleTypes nil}
                    {:description nil
                     :enumValues nil
                     :fields nil
                     :inputFields nil
                     :interfaces nil
                     :kind :SCALAR
                     :name "Int"
                     :possibleTypes nil}
                    {:description  nil
                     :enumValues nil
                     :fields [{:args [{:defaultValue nil
                                       :description nil
                                       :name "does_nothing"
                                       :type {:kind :SCALAR
                                              :name "String"
                                              :ofType nil}}
                                      {:defaultValue nil
                                       :description nil
                                       :name "episodes"
                                       :type {:kind :NON_NULL
                                              :name nil
                                              :ofType {:kind :LIST
                                                       :name nil
                                                       :ofType {:kind :ENUM
                                                                :name "episode"
                                                                :ofType nil}}}}
                                      {:defaultValue nil
                                       :description nil
                                       :name "id"
                                       :type {:kind :NON_NULL
                                              :name nil
                                              :ofType {:kind :SCALAR
                                                       :name "String"
                                                       :ofType nil}}}]
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "addHeroEpisodes"
                               :type {:kind :INTERFACE
                                      :name "character"
                                      :ofType nil}}
                              {:args [{:defaultValue nil
                                       :description nil
                                       :name "id"
                                       :type {:kind :NON_NULL
                                              :name nil
                                              :ofType {:kind :SCALAR
                                                       :name "String"
                                                       :ofType nil}}}
                                      {:defaultValue nil
                                       :description nil
                                       :name "newHomePlanet"
                                       :type {:kind :SCALAR
                                              :name "String"
                                              :ofType nil}}]
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "changeHeroHomePlanet"
                               :type {:kind :OBJECT
                                      :name "human"
                                      :ofType nil}}
                              {:args [{:defaultValue nil
                                       :description nil
                                       :name "from"
                                       :type {:kind :SCALAR
                                              :name "String"
                                              :ofType nil}}
                                      {:defaultValue "\"Rey\""
                                       :description nil
                                       :name "to"
                                       :type {:kind :SCALAR
                                              :name "String"
                                              :ofType nil}}]
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "changeHeroName"
                               :type {:kind :INTERFACE
                                      :name "character"
                                      :ofType nil}}]
                     :inputFields nil
                     :interfaces []
                     :kind :OBJECT
                     :name "Mutation"
                     :possibleTypes nil}
                    {:description nil
                     :enumValues nil
                     :fields [{:args [{:defaultValue "\"2001\""
                                       :description nil
                                       :name "id"
                                       :type {:kind :SCALAR
                                              :name "String"
                                              :ofType nil}}]
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "droid"
                               :type {:kind :OBJECT
                                      :name "droid"
                                      :ofType nil}}
                              {:args [{:defaultValue nil
                                       :description nil
                                       :name "inputObject"
                                       :type {:kind :INPUT_OBJECT
                                              :name "testInputObject"
                                              :ofType nil}}
                                      {:defaultValue nil
                                       :description nil
                                       :name "integer"
                                       :type {:kind :SCALAR
                                              :name "Int"
                                              :ofType nil}}
                                      {:defaultValue nil
                                       :description nil
                                       :name "integerArray"
                                       :type {:kind :LIST
                                              :name nil
                                              :ofType {:kind :SCALAR
                                                       :name "Int"
                                                       :ofType nil}}}]
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "echoArgs"
                               :type {:kind :OBJECT
                                      :name "echoArgs"
                                      :ofType nil}}
                              {:args [{:defaultValue nil
                                       :description nil
                                       :name "episode"
                                       :type {:kind :ENUM
                                              :name "episode"
                                              :ofType nil}}]
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "hero"
                               :type {:kind :NON_NULL
                                      :name nil
                                      :ofType {:kind :INTERFACE
                                               :name "character"
                                               :ofType nil}}}
                              {:args [{:defaultValue "\"1001\""
                                       :description nil
                                       :name "id"
                                       :type {:kind :SCALAR
                                              :name "String"
                                              :ofType nil}}]
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "human"
                               :type {:kind :NON_NULL
                                      :name nil
                                      :ofType {:kind :OBJECT
                                               :name "human"
                                               :ofType nil}}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "now"
                               :type {:kind :OBJECT
                                      :name "galaxy_date"
                                      :ofType nil}}]
                     :inputFields nil
                     :interfaces []
                     :kind :OBJECT
                     :name "Query"
                     :possibleTypes nil}
                    {:description nil
                     :enumValues nil
                     :fields nil
                     :inputFields nil
                     :interfaces nil
                     :kind :SCALAR
                     :name "String"
                     :possibleTypes nil}
                    {:description nil
                     :enumValues nil
                     :fields [{:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "appears_in"
                               :type {:kind :LIST
                                      :name nil
                                      :ofType {:kind :ENUM
                                               :name "episode"
                                               :ofType nil}}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "arch_enemy"
                               :type {:kind :NON_NULL
                                      :name nil
                                      :ofType {:kind :INTERFACE
                                               :name "character"
                                               :ofType nil}}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "bar"
                               :type {:kind :INTERFACE
                                      :name "character"
                                      :ofType nil}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "best_friend"
                               :type {:kind :INTERFACE
                                      :name "character"
                                      :ofType nil}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "droids"
                               :type {:kind :NON_NULL
                                      :name nil
                                      :ofType {:kind :LIST
                                               :name nil
                                               :ofType {:kind :NON_NULL
                                                        :name nil
                                                        :ofType {:kind :INTERFACE
                                                                 :name "character"}}}}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "enemies"
                               :type {:kind :LIST
                                      :name nil
                                      :ofType {:kind :NON_NULL
                                               :name nil
                                               :ofType {:kind :INTERFACE
                                                        :name "character"
                                                        :ofType nil}}}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "family"
                               :type {:kind :NON_NULL
                                      :name nil
                                      :ofType {:kind :LIST
                                               :name nil
                                               :ofType {:kind :INTERFACE
                                                        :name "character"
                                                        :ofType nil}}}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "foo"
                               :type {:kind :NON_NULL
                                      :name nil
                                      :ofType {:kind :SCALAR
                                               :name "String"
                                               :ofType nil}}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "forceSide"
                               :type {:kind :OBJECT
                                      :name "force"
                                      :ofType nil}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "friends"
                               :type {:kind :LIST
                                      :name nil
                                      :ofType {:kind :INTERFACE
                                               :name "character"
                                               :ofType nil}}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "id"
                               :type {:kind :SCALAR
                                      :name "String"
                                      :ofType nil}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "name"
                               :type {:kind :SCALAR
                                      :name "String"
                                      :ofType nil}}]
                     :inputFields nil
                     :interfaces nil
                     :kind :INTERFACE
                     :name "character"
                     :possibleTypes [{:kind :OBJECT
                                      :name "droid"
                                      :ofType nil}
                                     {:kind :OBJECT
                                      :name "human"
                                      :ofType nil}]}
                    {:description nil
                     :enumValues nil
                     :fields [{:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "accessories"
                               :type {:kind :LIST
                                      :name nil
                                      :ofType {:kind :SCALAR
                                               :name "String"
                                               :ofType nil}}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "appears_in"
                               :type {:kind :LIST
                                      :name nil
                                      :ofType {:kind :ENUM
                                               :name "episode"
                                               :ofType nil}}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "arch_enemy"
                               :type {:kind :NON_NULL
                                      :name nil
                                      :ofType {:kind :INTERFACE
                                               :name "character"
                                               :ofType nil}}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "bar"
                               :type {:kind :INTERFACE
                                      :name "character"
                                      :ofType nil}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "best_friend"
                               :type {:kind :INTERFACE
                                      :name "character"
                                      :ofType nil}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "droids"
                               :type {:kind :NON_NULL
                                      :name nil
                                      :ofType {:kind :LIST
                                               :name nil
                                               :ofType {:kind :NON_NULL
                                                        :name nil
                                                        :ofType {:kind :INTERFACE
                                                                 :name "character"}}}}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "enemies"
                               :type {:kind :LIST
                                      :name nil
                                      :ofType {:kind :NON_NULL
                                               :name nil
                                               :ofType {:kind :INTERFACE
                                                        :name "character"
                                                        :ofType nil}}}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "family"
                               :type {:kind :NON_NULL
                                      :name nil
                                      :ofType {:kind :LIST
                                               :name nil
                                               :ofType {:kind :INTERFACE
                                                        :name "character"
                                                        :ofType nil}}}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "foo"
                               :type {:kind :NON_NULL
                                      :name nil
                                      :ofType {:kind :SCALAR
                                               :name "String"
                                               :ofType nil}}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "forceSide"
                               :type {:kind :OBJECT
                                      :name "force"
                                      :ofType nil}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "friends"
                               :type {:kind :LIST
                                      :name nil
                                      :ofType {:kind :INTERFACE
                                               :name "character"
                                               :ofType nil}}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "id"
                               :type {:kind :SCALAR
                                      :name "String"
                                      :ofType nil}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "incept_date"
                               :type {:kind :SCALAR
                                      :name "Int"
                                      :ofType nil}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "name"
                               :type {:kind :SCALAR
                                      :name "String"
                                      :ofType nil}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "primary_function"
                               :type {:kind :LIST
                                      :name nil
                                      :ofType {:kind :SCALAR
                                               :name "String"
                                               :ofType nil}}}]
                     :inputFields nil
                     :interfaces [{:kind :INTERFACE
                                   :name "character"
                                   :ofType nil}]
                     :kind :OBJECT
                     :name "droid"
                     :possibleTypes nil}
                    {:description nil
                     :enumValues nil
                     :fields [{:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "inputObject"
                               :type {:kind :SCALAR
                                      :name "String"
                                      :ofType nil}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "integer"
                               :type {:kind :SCALAR
                                      :name "Int"
                                      :ofType nil}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "integerArray"
                               :type {:kind :LIST
                                      :name nil
                                      :ofType {:kind :SCALAR
                                               :name "Int"
                                               :ofType nil}}}]
                     :inputFields nil
                     :interfaces []
                     :kind :OBJECT
                     :name "echoArgs"
                     :possibleTypes nil}
                    {:description "The episodes of the original Star Wars trilogy."
                     :enumValues [{:deprecationReason nil
                                   :description nil
                                   :isDeprecated false
                                   :name "NEWHOPE"}
                                  {:deprecationReason nil
                                   :description nil
                                   :isDeprecated false
                                   :name "EMPIRE"}
                                  {:deprecationReason nil
                                   :description nil
                                   :isDeprecated false
                                   :name "JEDI"}]
                     :fields nil
                     :inputFields nil
                     :interfaces nil
                     :kind :ENUM
                     :name "episode"
                     :possibleTypes nil}
                    {:description nil
                     :enumValues nil
                     :fields [{:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "id"
                               :type {:kind :SCALAR
                                      :name "String"
                                      :ofType nil}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "members"
                               :type {:kind :LIST
                                      :name nil
                                      :ofType {:kind :INTERFACE
                                               :name "character"
                                               :ofType nil}}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "name"
                               :type {:kind :SCALAR
                                      :name "String"
                                      :ofType nil}}]
                     :inputFields nil
                     :interfaces []
                     :kind :OBJECT
                     :name "force"
                     :possibleTypes nil}
                    {:description nil
                     :enumValues nil
                     :fields [{:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "date"
                               :type {:kind :SCALAR
                                      :name "Date"
                                      :ofType nil}}]
                     :inputFields nil
                     :interfaces []
                     :kind :OBJECT
                     :name "galaxy_date"
                     :possibleTypes nil}
                    {:description nil
                     :enumValues nil
                     :fields [{:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "appears_in"
                               :type {:kind :LIST
                                      :name nil
                                      :ofType {:kind :ENUM
                                               :name "episode"
                                               :ofType nil}}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "arch_enemy"
                               :type {:kind :NON_NULL
                                      :name nil
                                      :ofType {:kind :INTERFACE
                                               :name "character"
                                               :ofType nil}}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "bar"
                               :type {:kind :INTERFACE
                                      :name "character"
                                      :ofType nil}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "best_friend"
                               :type {:kind :INTERFACE
                                      :name "character"
                                      :ofType nil}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "droids"
                               :type {:kind :NON_NULL
                                      :name nil
                                      :ofType {:kind :LIST
                                               :name nil
                                               :ofType {:kind :NON_NULL
                                                        :name nil
                                                        :ofType {:kind :INTERFACE
                                                                 :name "character"}}}}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "enemies"
                               :type {:kind :LIST
                                      :name nil
                                      :ofType {:kind :NON_NULL
                                               :name nil
                                               :ofType {:kind :INTERFACE
                                                        :name "character"
                                                        :ofType nil}}}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "family"
                               :type {:kind :NON_NULL
                                      :name nil
                                      :ofType {:kind :LIST
                                               :name nil
                                               :ofType {:kind :INTERFACE
                                                        :name "character"
                                                        :ofType nil}}}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "foo"
                               :type {:kind :NON_NULL
                                      :name nil
                                      :ofType {:kind :SCALAR
                                               :name "String"
                                               :ofType nil}}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "forceSide"
                               :type {:kind :OBJECT
                                      :name "force"
                                      :ofType nil}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "friends"
                               :type {:kind :LIST
                                      :name nil
                                      :ofType {:kind :INTERFACE
                                               :name "character"
                                               :ofType nil}}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "homePlanet"
                               :type {:kind :SCALAR
                                      :name "String"
                                      :ofType nil}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "id"
                               :type {:kind :SCALAR
                                      :name "String"
                                      :ofType nil}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "name"
                               :type {:kind :SCALAR
                                      :name "String"
                                      :ofType nil}}
                              {:args []
                               :deprecationReason nil
                               :description nil
                               :isDeprecated false
                               :name "primary_function"
                               :type {:kind :LIST
                                      :name nil
                                      :ofType {:kind :SCALAR
                                               :name "String"
                                               :ofType nil}}}]
                     :inputFields nil
                     :interfaces [{:kind :INTERFACE
                                   :name "character"
                                   :ofType nil}]
                     :kind :OBJECT
                     :name "human"
                     :possibleTypes nil}
                    {:description nil
                     :enumValues nil
                     :fields nil
                     :inputFields [{:defaultValue nil
                                    :description nil
                                    :name "date"
                                    :type {:kind :SCALAR
                                           :name "Date"
                                           :ofType nil}}
                                   {:defaultValue nil
                                    :description nil
                                    :name "integerArray"
                                    :type {:kind :LIST
                                           :name nil
                                           :ofType {:kind :SCALAR
                                                    :name "Int"
                                                    :ofType nil}}}
                                   {:defaultValue nil
                                    :description nil
                                    :name "name"
                                    :type {:kind :SCALAR
                                           :name "String"
                                           :ofType nil}}]
                     :interfaces nil
                     :kind :INPUT_OBJECT
                     :name "nestedInputObject"
                     :possibleTypes nil}
                    {:description nil
                     :enumValues nil
                     :fields nil
                     :inputFields [{:defaultValue nil
                                    :description nil
                                    :name "integer"
                                    :type {:kind :SCALAR
                                           :name "Int"
                                           :ofType nil}}
                                   {:defaultValue nil
                                    :description nil
                                    :name "nestedInputObject"
                                    :type {:kind :INPUT_OBJECT
                                           :name "nestedInputObject"
                                           :ofType nil}}
                                   {:defaultValue nil
                                    :description nil
                                    :name "string"
                                    :type {:kind :SCALAR
                                           :name "String"
                                           :ofType nil}}]
                     :interfaces nil
                     :kind :INPUT_OBJECT
                     :name "testInputObject"
                     :possibleTypes nil}]}
           (-> (execute q) :data :__schema)))))

(deftest mixed-use-of-keywords
  (let [schema (schema/compile {:queries {:search {:type '(non-null :ID)
                                                   :resolve identity
                                                   :args {:term {:type '(non-null String)}}}}})
        q "
        query { __schema { queryType {
          name
          fields {
            name
            args { ...InputValue }
            type { ...TypeRef }
          }
         }}}

          fragment InputValue on __InputValue {
            name
            type { ...TypeRef }
          }

          fragment TypeRef on __Type {
            name
            kind
            ofType { name kind }
          }"]
    (is (= {:data {:__schema {:queryType {:fields [{:args [{:name "term"
                                                            :type {:kind :NON_NULL
                                                                   :name nil
                                                                   :ofType {:kind :SCALAR
                                                                            :name "String"}}}]
                                                    :name "search"
                                                    :type {:kind :NON_NULL
                                                           :name nil
                                                           :ofType {:kind :SCALAR
                                                                    :name "ID"}}}]
                                          :name "Query"}}}}
           (utils/execute schema q nil nil)))))

(deftest described-enums
  (let [schema (schema/compile {:enums
                                {:status
                                 {:description "Possible operation results."
                                  :values [{:enum-value :OK :description "No problems."}
                                           {:enum-value :WARN :description "Completed with some warnings."}
                                           {:enum-value :FAIL :description "Some or all of the operation failed."}]}}})]
    (is (= {:data {:__type {:description "Possible operation results."
                            :enumValues [{:description "No problems."
                                          :name "OK"}
                                         {:description "Completed with some warnings."
                                          :name "WARN"}
                                         {:description "Some or all of the operation failed."
                                          :name "FAIL"}]}}}
           (utils/execute schema
                          "{ __type(name: \"status\") { description enumValues { name description }}}")))))

(deftest deprecated-fields
  (let [schema (utils/compile-schema "deprecated-fields-schema.edn" {})]
    (is (= {:data {:__type {:fields [{:deprecationReason nil
                                      :description nil
                                      :isDeprecated false
                                      :name "honorific"}
                                     {:deprecationReason "Out of fashion."
                                      :description "Used by poets."
                                      :isDeprecated true
                                      :name "nomdeplume"}
                                     {:deprecationReason nil
                                      :description "Replaced by honorific."
                                      :isDeprecated true
                                      :name "title"}]}}}
           (utils/execute schema
                          "{ __type(name: \"user\") {
                                fields(includeDeprecated: true) {
                                  name description isDeprecated deprecationReason
                                }
                              }
                           }")))
    ;; Deprecated are ignored by default:

    (is (= {:data {:__type {:fields [{:deprecationReason nil
                                      :description nil
                                      :isDeprecated false
                                      :name "honorific"}]}}}
           (utils/execute schema
                          "{ __type(name: \"user\") {
                                fields {
                                  name description isDeprecated deprecationReason
                                }
                              }
                           }")))))

(def ^:private fields-and-args-query "{
                             __type(name: \"Query\") {
                              fields {
                                name
                                args { name defaultValue }
                              }
                            }
                          }")

(deftest non-string-default-value
  (let [schema (utils/compile-schema "non-string-default-value-schema.edn"
                                     {:placeholder identity})]

    (is (= {:data
            {:__type
             {:fields
              [{:args [{:name "checked"
                        :defaultValue "true"}
                       {:name "count"
                        :defaultValue "10"}
                       {:name "has_no_default"
                        :defaultValue nil}
                       {:name "mood"
                        :defaultValue "happy"}
                       {:name "target"
                        :defaultValue "3.2"}
                       {:name "title"
                        :defaultValue "\"columbia\""}]
                :name "search"}]}}}
           (utils/execute schema fields-and-args-query)))))

(deftest list-default-value
  (let [schema (utils/compile-schema "list-default-value-schema.edn"
                                     {:placeholder identity})]

    (is (= {:data
            {:__type
             {:fields
              [{:args [{:name "terms"
                        :defaultValue "[\"columbia\",\"river\",\"gorge\"]"}]
                :name "search"}]}}}
           (utils/execute schema fields-and-args-query)))))

(deftest input-object-default-value
  (let [schema (utils/compile-schema "input-object-default-value-schema.edn"
                                     {:placeholder identity})]

    (is (= {:data
            {:__type
             {:fields
              [{:args [{:defaultValue "{checked:false,count:20,target:3.14,title:\"gorge\"}"
                        :name "filter"}]
                :name "search"}]}}}
           (utils/execute schema fields-and-args-query)))

    ;; And check that individual defaults on an InputObject are exposed
    (is (= {:data
            {:__type
             {:inputFields
              [{:name "checked"
                :defaultValue "true"}
               {:name "count"
                :defaultValue "10"}
               {:name "has_no_default"
                :defaultValue nil}
               {:name "mood"
                :defaultValue "happy"}
               {:name "target"
                :defaultValue "3.2"}
               {:name "title"
                :defaultValue "\"columbia\""}]}}}
           (utils/execute schema "{
             __type(name: \"Filter\") { inputFields { name defaultValue } }
           }")))))

(deftest enum-transformer-default-value
  (let [;; Create kebab-cased namespaced keywords for enum values
        parse-country     (fn [code] (keyword :country-code (str/lower-case (name code))))
        ;; upper case, non namespaced keywords are in the schema
        serialize-country (fn [code] (str/upper-case (name code)))
        schema (-> (io/resource "enum-default-value-with-transformer-schema.edn")
                   slurp
                   edn/read-string
                   (util/attach-resolvers {:placeholder identity})
                   (util/inject-enum-transformers {:CountryCode {:parse     parse-country
                                                                 :serialize serialize-country}})
                   (schema/compile))]
    (is (= {:data
            {:__type
             {:fields
              [{:args [{:defaultValue "US"
                        :name "code"}]
                :name "countryByCode"}]}}}
           (utils/execute schema fields-and-args-query)))))

(deftest query-with-introspection-disabled
  (let [schema (schema/compile test-schema {:enable-introspection? false})
        q "{ __type(name: \"human\") { kind name fields { name }}}"]
    (is (= {:errors [{:extensions {:field-name :__type
                                   :type-name :Query}
                      :locations [{:column 3
                                   :line 1}]
                      :message "Cannot query field `__type' on type `Query'."}]}
           (execute schema q)))))

(def ^:private specified-by-url-query 
  "{
     __type(name: \"DateTime\") {
       name
       kind
       specifiedByUrl
     }
   }")

(deftest scalar-specified-by-url
  (let [schema (schema/compile {:scalars {:DateTime {:parse identity
                                                     :serialize identity
                                                     :specified-by "https://scalars.graphql.org/andimarek/date-time.html"}}})]
    (is (= {:data
            {:__type
             {:kind :SCALAR
              :name "DateTime"
              :specifiedByUrl "https://scalars.graphql.org/andimarek/date-time.html"}}}
           (utils/execute schema specified-by-url-query)))))
