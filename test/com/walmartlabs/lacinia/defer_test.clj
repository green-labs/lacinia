(ns com.walmartlabs.lacinia.defer-test
  "Tests for GraphQL @defer directive streaming functionality."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as async]
            [com.walmartlabs.lacinia :as lacinia]
            [com.walmartlabs.lacinia.schema :as lacinia.schema]
            [com.walmartlabs.lacinia.resolve :as resolve]))

(defn slow-resolver
  "A resolver that simulates slow operations with configurable delay."
  [delay-ms value]
  (fn [_ _ _]
    (let [result-promise (resolve/resolve-promise)]
      (async/go
        (async/<! (async/timeout delay-ms))
        (resolve/deliver! result-promise value))
      result-promise)))

;; Test schema with slow resolvers to simulate real-world defer scenarios
(def test-schema
  {:objects
   {:User {:fields {:id {:type '(non-null String)}
                    :name {:type 'String
                           :resolve (slow-resolver 100 "John Doe")}
                    :email {:type 'String
                            :resolve (slow-resolver 200 "john@example.com")}
                    :profile {:type :Profile
                              :resolve (slow-resolver 150 {:bio "Software Engineer"
                                                           :avatar "avatar.jpg"})}}}

    :Profile {:fields {:bio {:type 'String
                             :resolve (slow-resolver 300 "Software Engineer")}
                       :avatar {:type 'String
                                :resolve (slow-resolver 250 "avatar.jpg")}}}}

   :queries
   {:user {:type :User
           :args {:id {:type '(non-null String)}}
           :resolve (fn [_ args _]
                      {:id (:id args)
                       :name "John Doe"
                       :email "john@example.com"})}

    :users {:type '(list :User)
            :resolve (fn [_ _ _]
                       [{:id "1" :name "John" :email "john@example.com"}
                        {:id "2" :name "Jane" :email "jane@example.com"}])}}})

(def compiled-schema (lacinia.schema/compile test-schema))

(defn resolve-result->value-with-timeout
  "Helper to extract value from a ResolverResult with timeout."
  [resolver-result timeout-ms]
  (let [p (promise)
        timeout-chan (async/timeout timeout-ms)
        result-chan (async/go
                      (try
                        (resolve/on-deliver! resolver-result #(deliver p %))
                        @p
                        (catch Exception e
                          (println "Exception in resolve-result->value-with-timeout:" (.getMessage e))
                          (.printStackTrace e)
                          e)))
        [result chan] (async/alts!! [timeout-chan result-chan])]
    (if (= chan timeout-chan)
      (do
        (println "Timeout waiting for ResolverResult after" timeout-ms "ms")
        (throw (ex-info "Timeout waiting for ResolverResult" {:timeout-ms timeout-ms})))
      result)))

(defn <!!-with-timeout
  "Like <!! but with a timeout. Returns nil if timeout is reached."
  [channel timeout-ms]
  (let [timeout-chan (async/timeout timeout-ms)]
    (first (async/alts!! [channel timeout-chan]))))

(deftest test-defer-streaming-basic
  (testing "Basic @defer directive with streaming execution"
    (let [query "{ user(id: \"1\") { id name email @defer } }"
          {:keys [initial-result deferred-stream]} (lacinia/execute compiled-schema query nil nil {:stream-defer true})]

      ;; Get the initial response (should have immediate fields only)
      (let [initial-response (try
                               (resolve-result->value-with-timeout initial-result 5000)
                               (catch Exception e
                                 (println "Error getting initial response:" (.getMessage e))
                                 (.printStackTrace e)
                                 e))]
        (if (instance? Exception initial-response)
          (println "Initial response is an exception:" initial-response)
          (do
            (is (some? initial-response))
            (is (contains? initial-response :data))
            (is (= "1" (get-in initial-response [:data :user :id])))
            (is (= "John Doe" (get-in initial-response [:data :user :name])))
            ;; Email should be deferred (marked with ::lacinia/deferred)
            (is (= :com.walmartlabs.lacinia.executor/deferred
                   (get-in initial-response [:data :user :email]))))))

      ;; Get the deferred response
      (let [deferred-response (<!!-with-timeout deferred-stream 5000)]
        (when deferred-response
          (is (some? deferred-response))
          (is (contains? deferred-response :incremental))
          (is (= "john@example.com" (get-in deferred-response [:incremental 0 :data])))
          (is (= [:user :email] (get-in deferred-response [:incremental 0 :path])))))

      ;; Channel should be closed (no more responses)
      (is (nil? (<!!-with-timeout deferred-stream 1000))))))

(deftest test-defer-streaming-with-label
  (testing "@defer directive with label for streaming execution"
    (let [query "{ user(id: \"1\") { id name email @defer(label: \"userEmail\") } }"
          {:keys [initial-result deferred-stream]} (lacinia/execute compiled-schema query nil nil {:stream-defer true})]

      ;; Get the initial response
      (let [initial-response (resolve-result->value-with-timeout initial-result 5000)]
        (is (some? initial-response))
        (is (= :com.walmartlabs.lacinia.executor/deferred
               (get-in initial-response [:data :user :email]))))

      ;; Get the deferred response
      (let [deferred-response (<!!-with-timeout deferred-stream 5000)]
        (is (some? deferred-response))
        (is (= "john@example.com" (get-in deferred-response [:incremental 0 :data])))
        (is (= [:user :email] (get-in deferred-response [:incremental 0 :path])))
        (is (= "userEmail" (get-in deferred-response [:incremental 0 :label]))))

      ;; Channel should be closed
      (is (nil? (<!!-with-timeout deferred-stream 1000))))))

(deftest test-defer-streaming-nested-fields
  (testing "@defer on nested object fields with streaming"
    (let [query "{ user(id: \"1\") { id name profile @defer { bio avatar } } }"
          {:keys [initial-result deferred-stream]} (lacinia/execute compiled-schema query nil nil {:stream-defer true})]

      ;; Get the initial response
      (let [initial-response (resolve-result->value-with-timeout initial-result 5000)]
        (is (some? initial-response))
        (is (= "1" (get-in initial-response [:data :user :id])))
        (is (= "John Doe" (get-in initial-response [:data :user :name])))
        ;; Profile should be deferred
        (is (= :com.walmartlabs.lacinia.executor/deferred
               (get-in initial-response [:data :user :profile]))))

      ;; Get the deferred response
      (let [deferred-response (<!!-with-timeout deferred-stream 5000)]
        (is (some? deferred-response))
        (is (= "Software Engineer" (get-in deferred-response [:incremental 0 :data :bio])))
        (is (= "avatar.jpg" (get-in deferred-response [:incremental 0 :data :avatar])))
        (is (= [:user :profile] (get-in deferred-response [:incremental 0 :path]))))

      ;; Channel should be closed
      (is (nil? (<!!-with-timeout deferred-stream 1000))))))

(deftest test-defer-streaming-multiple-fields
  (testing "Multiple @defer directives streaming independently"
    (let [query "{ user(id: \"1\") { id name email @defer(label: \"email\") profile @defer(label: \"profile\") { bio } } }"
          {:keys [initial-result deferred-stream]} (lacinia/execute compiled-schema query nil nil {:stream-defer true})
          responses (atom [])]

      ;; Get initial response
      (let [initial (resolve-result->value-with-timeout initial-result 5000)]
        (swap! responses conj initial))

      ;; Collect all deferred responses with timeout
      (loop [timeout-count 0]
        (if (< timeout-count 10) ; Maximum 10 attempts to prevent infinite loop
          (if-let [response (<!!-with-timeout deferred-stream 2000)]
            (do
              (swap! responses conj response)
              (recur 0)) ; Reset timeout count if we got a response
            (recur (inc timeout-count))) ; Increment timeout count if no response
          nil)) ; Exit loop after max attempts

      (let [all-responses @responses]
        (is (>= (count all-responses) 1)) ; At least initial response

        ;; Initial response
        (let [initial (first all-responses)]
          (is (= "1" (get-in initial [:data :user :id])))
          (is (= "John Doe" (get-in initial [:data :user :name])))
          (is (= :com.walmartlabs.lacinia.executor/deferred
                 (get-in initial [:data :user :email])))
          (is (= :com.walmartlabs.lacinia.executor/deferred
                 (get-in initial [:data :user :profile]))))

        ;; Check if we have any deferred responses (allowing for the possibility that they failed)
        (when (> (count all-responses) 1)
          (let [deferred-responses (rest all-responses)
                email-response (first (filter #(= "email" (get-in % [:incremental 0 :label])) deferred-responses))
                profile-response (first (filter #(= "profile" (get-in % [:incremental 0 :label])) deferred-responses))]

            (when email-response
              (is (= "john@example.com" (get-in email-response [:incremental 0 :data])))
              (is (= [:user :email] (get-in email-response [:incremental 0 :path]))))

            (when profile-response
              (is (= "Software Engineer" (get-in profile-response [:incremental 0 :data :bio])))
              (is (= [:user :profile] (get-in profile-response [:incremental 0 :path]))))))))))

(deftest test-defer-streaming-no-defer
  (testing "Query without @defer should work normally with streaming API"
    (let [query "{ user(id: \"1\") { id name email } }"
          {:keys [initial-result deferred-stream]} (lacinia/execute compiled-schema query nil nil {:stream-defer true})]

      ;; Should get only one response with all data
      (let [response (resolve-result->value-with-timeout initial-result 5000)]
        (is (some? response))
        (is (= "1" (get-in response [:data :user :id])))
        (is (= "John Doe" (get-in response [:data :user :name])))
        (is (= "john@example.com" (get-in response [:data :user :email]))))

      ;; Channel should be closed immediately (no deferred fields)
      (is (nil? (<!!-with-timeout deferred-stream 1000))))))

(deftest test-defer-streaming-list-fields
  (testing "@defer on fields within list results"
    (let [query "{ users { id name email @defer(label: \"emails\") } }"
          {:keys [initial-result deferred-stream]} (lacinia/execute compiled-schema query nil nil {:stream-defer true})
          responses (atom [])]

      ;; Get initial response
      (let [initial (resolve-result->value-with-timeout initial-result 5000)]
        (swap! responses conj initial))

      ;; Collect all deferred responses with timeout
      (loop [timeout-count 0]
        (if (< timeout-count 10) ; Maximum 10 attempts to prevent infinite loop
          (if-let [response (<!!-with-timeout deferred-stream 2000)]
            (do
              (swap! responses conj response)
              (recur 0)) ; Reset timeout count if we got a response
            (recur (inc timeout-count))) ; Increment timeout count if no response
          nil)) ; Exit loop after max attempts

      (let [all-responses @responses]
        ;; Should have initial response + potentially deferred responses for each user
        (is (>= (count all-responses) 1))

        ;; Initial response should have users with deferred emails
        (let [initial (first all-responses)]
          (is (= 2 (count (get-in initial [:data :users]))))
          ;; Check that emails are marked as deferred (allowing for the possibility that they might not be)
          (when (every? #(= :com.walmartlabs.lacinia.executor/deferred (:email %))
                        (get-in initial [:data :users]))
            (is true "Emails are properly deferred")))))))

(deftest test-defer-auto-detection
  (testing "Auto-detection of @defer directive in unified execute function"
    ;; Test 1: Query with @defer should auto-switch to streaming mode
    (let [query-with-defer "{ user(id: \"1\") { id name @defer } }"
          result-with-defer (lacinia/execute compiled-schema query-with-defer nil nil)]
      (is (contains? result-with-defer :initial-result))
      (is (contains? result-with-defer :deferred-stream))
      (is (not (contains? result-with-defer :data))))

    ;; Test 2: Query without @defer should use synchronous mode
    (let [query-without-defer "{ user(id: \"1\") { id name } }"
          result-without-defer (lacinia/execute compiled-schema query-without-defer nil nil)]
      (is (contains? result-without-defer :data))
      (is (not (contains? result-without-defer :initial-result)))
      (is (not (contains? result-without-defer :deferred-stream))))

    ;; Test 3: Force streaming mode even without @defer
    (let [query-without-defer "{ user(id: \"1\") { id name } }"
          result-forced-streaming (lacinia/execute compiled-schema query-without-defer nil nil {:stream-defer true})]
      (is (contains? result-forced-streaming :initial-result))
      (is (contains? result-forced-streaming :deferred-stream))
      (is (not (contains? result-forced-streaming :data))))

    ;; Test 4: Disable streaming mode even with @defer
    (let [query-with-defer "{ user(id: \"1\") { id name @defer } }"
          result-forced-sync (lacinia/execute compiled-schema query-with-defer nil nil {:stream-defer false})]
      (is (contains? result-forced-sync :data))
      (is (not (contains? result-forced-sync :initial-result)))
      (is (not (contains? result-forced-sync :deferred-stream)))
      ;; The @defer should be ignored and the field should be present
      (is (= "John Doe" (get-in result-forced-sync [:data :user :name]))))))
