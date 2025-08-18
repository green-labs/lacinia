(ns com.walmartlabs.lacinia.custom-directives-introspection-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [com.walmartlabs.test-utils :refer [execute]]
            [com.walmartlabs.lacinia.schema :as schema]))

(deftest custom-directives-introspection
  (let [test-schema {:directive-defs
                     {:access {:args {:role {:type 'String}}
                               :locations #{:field-definition}
                               :description "Controls field access based on user role"}
                      :custom {:locations #{:object}
                               :description "A custom directive for objects"}}
                     
                     :queries
                     {:hello {:type 'String
                             :resolve (constantly "Hello World!")}}}
        
        compiled-schema (schema/compile test-schema)
        
        
        introspection-query "
          {
            __schema {
              directives {
                name
                description
                locations
                args {
                  name
                  type {
                    name
                    kind
                  }
                }
              }
            }
          }"
        
        result (execute compiled-schema introspection-query)
        directives (get-in result [:data :__schema :directives])
        directive-names (set (map :name directives))
        access-directive (first (filter #(= "access" (:name %)) directives))
        custom-directive (first (filter #(= "custom" (:name %)) directives))]
      ;; Built-in directives should be present
      (is (contains? directive-names "skip"))
      (is (contains? directive-names "include"))
      (is (contains? directive-names "deprecated"))
      
      ;; Custom directives should be present
      (is (contains? directive-names "access"))
      (is (contains? directive-names "custom"))
      
      ;; Check access directive details
      (is (= "Controls field access based on user role" (:description access-directive)))
      (is (= [:FIELD_DEFINITION] (:locations access-directive)))
      (is (= 1 (count (:args access-directive))))
      (is (= "role" (-> access-directive :args first :name)))
      
      ;; Check custom directive details
      (is (= "A custom directive for objects" (:description custom-directive)))
      (is (= [:OBJECT] (:locations custom-directive)))
      (is (empty? (:args custom-directive)))))

(deftest directive-with-default-value-introspection
  (let [test-schema {:directive-defs
                     {:limit {:args {:max {:type 'Int
                                           :default-value 100
                                           :description "Maximum number of items"}}
                              :locations #{:field-definition}
                              :description "Limits the number of results"}}
                     
                     :queries
                     {:items {:type '(list String)
                              :resolve (constantly ["item1" "item2"])}}}
        
        compiled-schema (schema/compile test-schema)
        
        introspection-query "
          {
            __schema {
              directives {
                name
                description
                locations
                args {
                  name
                  description
                  defaultValue
                  type {
                    name
                    kind
                  }
                }
              }
            }
          }"
        
        result (execute compiled-schema introspection-query)
        directives (get-in result [:data :__schema :directives])
        limit-directive (first (filter #(= "limit" (:name %)) directives))]
    (is (some? limit-directive))
    (is (= "Limits the number of results" (:description limit-directive)))
    
    (let [max-arg (-> limit-directive :args first)]
      (is (= "max" (:name max-arg)))
      (is (= "Maximum number of items" (:description max-arg)))
      (is (= "100" (:defaultValue max-arg)))
      (is (= "Int" (get-in max-arg [:type :name]))))))

(comment
  (run-tests))
