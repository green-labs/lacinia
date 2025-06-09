(ns com.walmartlabs.lacinia.defer-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.walmartlabs.lacinia.schema :as schema]
   [com.walmartlabs.lacinia :refer [execute]]))

(def test-schema
  {:objects
   {:User
    {:fields
     {:id {:type :ID}
      :name {:type :String}
      :email {:type :String
              :resolve (fn [_ _ _]
                         ;; Simulate a slow operation
                         (Thread/sleep 100)
                         "user@example.com")}
      :profile {:type :UserProfile}}}

    :UserProfile
    {:fields
     {:bio {:type :String}
      :avatar {:type :String}}}}

   :queries
   {:user {:type :User
           :args {:id {:type :ID}}
           :resolve (fn [_ {:keys [id]} _]
                      {:id id
                       :name "John Doe"
                       :profile {:bio "Software Developer"
                                 :avatar "avatar.jpg"}})}}})

(def compiled-schema (schema/compile test-schema))

(deftest defer-directive-test
  (testing "basic @defer functionality"
    (let [query "query {
                   user(id: \"123\") {
                     id
                     name
                     email @defer(label: \"userEmail\")
                     profile {
                       bio
                       avatar @defer(label: \"userAvatar\")
                     }
                   }
                 }"
          result (execute compiled-schema query {} nil)]

      ;; Check that the main data is present
      (is (= "123" (get-in result [:data :user :id])))
      (is (= "John Doe" (get-in result [:data :user :name])))
      (is (= "Software Developer" (get-in result [:data :user :profile :bio])))

      ;; Check that deferred fields are marked as deferred
      (is (= :com.walmartlabs.lacinia.executor/deferred
             (get-in result [:data :user :email])))
      (is (= :com.walmartlabs.lacinia.executor/deferred
             (get-in result [:data :user :profile :avatar])))

      ;; Check that deferred results are included
      (is (contains? result :deferred))
      (is (= 2 (count (:deferred result))))

      ;; Check deferred result structure
      (let [deferred-results (:deferred result)
            email-result (first (filter #(= "userEmail" (:label %)) deferred-results))
            avatar-result (first (filter #(= "userAvatar" (:label %)) deferred-results))]

        (is (some? email-result))
        (is (= [:user :email] (:path email-result)))

        (is (some? avatar-result))
        (is (= [:user :profile :avatar] (:path avatar-result)))))))

(deftest defer-without-label-test
  (testing "@defer without label argument"
    (let [query "query {
                   user(id: \"123\") {
                     id
                     name
                     email @defer
                   }
                 }"
          result (execute compiled-schema query {} nil)]

      ;; Check that the main data is present
      (is (= "123" (get-in result [:data :user :id])))
      (is (= "John Doe" (get-in result [:data :user :name])))

      ;; Check that deferred field is marked as deferred
      (is (= :com.walmartlabs.lacinia.executor/deferred
             (get-in result [:data :user :email])))

      ;; Check that deferred results are included
      (is (contains? result :deferred))
      (is (= 1 (count (:deferred result))))

      ;; Check deferred result has nil label
      (let [deferred-result (first (:deferred result))]
        (is (nil? (:label deferred-result)))
        (is (= [:user :email] (:path deferred-result)))))))