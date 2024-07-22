(ns com.walmartlabs.lacinia.complexity-analysis-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.walmartlabs.lacinia :refer [execute]]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.test-utils :refer [expect-exception]]
            [com.walmartlabs.test-schema :refer [test-schema]]))

;;-------------------------------------------------------------------------------
;; ## Tests

(def compiled-schema (schema/compile test-schema))

(deftest temp-test
  (testing "All leafs are scalar types or enums"
    (let [q "{ hero }"]
      (is (= {:errors [{:message "Field `Query/hero' must have at least one selection.",
                        :locations [{:line 1
                                     :column 3}]}]}
             (execute compiled-schema q {} nil))))
    (let [q "{ hero { name friends } }"]
      (is (= {:errors [{:message "Field `character/friends' must have at least one selection.",
                        :locations [{:line 1
                                     :column 15}]}]}
             (execute compiled-schema q {} nil))))
    (let [q "query NestedQuery {
             hero {
               name
               friends {
                 name
                 appears_in
                 friends
               }
             }
            }"]
      (is (= {:errors [{:message "Field `character/friends' must have at least one selection.",
                        :locations [{:column 18
                                     :line 7}]}]}
             (execute compiled-schema q {} nil))))
    (let [q "query NestedQuery {
             hero {
               name
               friends {
                 name
                 appears_in
                 friends { name
                           friends
                 }
               }
             }
            }"]
      (is (= {:errors [{:message "Field `character/friends' must have at least one selection."
                        :locations [{:column 28
                                     :line 8}]}]}
             (execute compiled-schema q {} nil))))
    (let [q "query NestedQuery {
             hero {
               name
               forceSide
               friends {
                 friends
                 name
                 appears_in
                 forceSide
               }
             }
            }"]
      (is (= {:errors [{:locations [{:column 16
                                     :line 4}]
                        :message "Field `character/forceSide' must have at least one selection."}
                       {:locations [{:column 18
                                     :line 6}]
                        :message "Field `character/friends' must have at least one selection."}
                       {:locations [{:column 18
                                     :line 9}]
                        :message "Field `character/forceSide' must have at least one selection."}]}
             (execute compiled-schema q {} nil))))
    (let [q "query NestedQuery {
             hero {
               name
               forceSide
               friends {
                 friends
                 name
                 appears_in
                 forceSide { name
                             members
                 }
               }
             }
            }"]
      (is (= {:errors [{:locations [{:column 16
                                     :line 4}]
                        :message "Field `character/forceSide' must have at least one selection."}
                       {:locations [{:column 18
                                     :line 6}]
                        :message "Field `character/friends' must have at least one selection."}
                       {:locations [{:column 30
                                     :line 10}]
                        :message "Field `force/members' must have at least one selection."}]}
             (execute compiled-schema q {} nil))))
    (let [q "query NestedQuery {
             hero {
               name
               forceSide
               friends {
                 friends
                 name
                 appears_in
                 forceSide { name
                             members { name }
                 }
               }
             }
            }"]
      (is (= {:errors [{:locations [{:column 16
                                     :line 4}]
                        :message "Field `character/forceSide' must have at least one selection."}
                       {:locations [{:column 18
                                     :line 6}]
                        :message "Field `character/friends' must have at least one selection."}]}
             (execute compiled-schema q {} nil))))
    (let [q "{ hero { name { id } } }"]
      (is (= {:errors [{:extensions {:field-name :id}
                        :locations [{:column 17
                                     :line 1}]
                        :message "Path de-references through a scalar type."}]}
             (execute compiled-schema q {} nil))))))

(deftest fragment-names-validations
  (let [q "query UseFragment {
             luke: human(id: \"1000\") {
               ...HumanFragment
             }
             leia: human(id: \"1003\") {
               ...HumanFragment
             }
           }
           fragment HumanFragment on human {
             name
             homePlanet
           }"]
    (is (nil? (:errors (execute compiled-schema q {} nil)))))
  (let [q "query UseFragment {
             luke: human(id: \"1000\") {
               ...FooFragment
             }
             leia: human(id: \"1003\") {
               ...HumanFragment
             }
           }
           fragment HumanFragment on human {
             name
             homePlanet
           }"]
    (is (= {:errors [{:message "Unknown fragment `FooFragment'. Fragment definition is missing."
                      :locations [{:line 3
                                   :column 19}]}]}
           (execute compiled-schema q {} nil))))
  (let [q "query UseFragment {
             luke: human(id: \"1000\") {
               ...FooFragment
             }
             leia: human(id: \"1003\") {
               ...BarFragment
             }
           }
           fragment HumanFragment on human {
             name
             homePlanet
           }"]
    (is (= {:errors [{:locations [{:column 19
                                   :line 3}]
                      :message "Unknown fragment `FooFragment'. Fragment definition is missing."}
                     {:locations [{:column 19
                                   :line 6}]
                      :message "Unknown fragment `BarFragment'. Fragment definition is missing."}
                     {:locations [{:column 21
                                   :line 9}]
                      :message "Fragment `HumanFragment' is never used."}]}
           (execute compiled-schema q {} nil))))
  (let [q "query withNestedFragments {
             luke: human(id: \"1000\") {
               friends {
                 ...friendFieldsFragment
               }
             }
           }
           fragment friendFieldsFragment on human {
             id
             name
             ...appearsInFragment
           }
           fragment appearsInFragment on human {
             appears_in
           }"]
    (is (nil? (:errors (execute compiled-schema q {} nil)))))
  (let [q "query withNestedFragments {
             luke: human(id: \"1000\") {
               friends {
                 ...friendFieldsFragment
               }
             }
           }
           fragment friendFieldsFragment on human {
             id
             name
             ...appearsInFragment
           }"]
    (is (= {:errors [{:message "Unknown fragment `appearsInFragment'. Fragment definition is missing."
                      :locations [{:line 11 :column 17}]}]}
           (execute compiled-schema q {} nil)))))


