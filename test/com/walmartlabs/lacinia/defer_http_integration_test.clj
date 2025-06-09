(ns com.walmartlabs.lacinia.defer-http-integration-test
  "HTTP integration tests for @defer directive with real streaming responses."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as async :refer [<!! chan go]]
            [clojure.string :as str]
            [com.walmartlabs.lacinia :as lacinia]
            [com.walmartlabs.lacinia.schema :as lacinia.schema]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [cheshire.core :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]
           [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [java.io ByteArrayOutputStream]))

(defn slow-resolver
  "Creates a resolver with configurable delay to simulate real-world latency."
  [delay-ms value]
  (fn [context args parent]
    (resolve/resolve-promise
     (async/go
       (async/<! (async/timeout delay-ms))
       value))))

;; E-commerce test schema with slow resolvers
(def ecommerce-schema
  {:objects
   {:Product {:fields {:id {:type '(non-null String)}
                       :name {:type 'String
                              :resolve (slow-resolver 100 "Wireless Headphones")}
                       :price {:type 'Float
                               :resolve (slow-resolver 200 99.99)}
                       :inventory {:type :Inventory
                                   :resolve (slow-resolver 300 {:quantity 50
                                                                :warehouse "West Coast"})}
                       :reviews {:type '(list :Review)
                                 :resolve (slow-resolver 400 [{:id "r1" :rating 5 :comment "Great!" :user "Alice"}
                                                              {:id "r2" :rating 4 :comment "Good quality" :user "Bob"}])}}}

    :Inventory {:fields {:quantity {:type 'Int
                                    :resolve (slow-resolver 150 50)}
                         :warehouse {:type 'String
                                     :resolve (slow-resolver 100 "West Coast")}}}

    :Review {:fields {:id {:type '(non-null String)}
                      :rating {:type 'Int
                               :resolve (slow-resolver 50 5)}
                      :comment {:type 'String
                                :resolve (slow-resolver 75 "Great!")}
                      :user {:type 'String
                             :resolve (slow-resolver 25 "Alice")}}}}

   :queries
   {:product {:type :Product
              :args {:id {:type '(non-null String)}}
              :resolve (fn [context args _parent]
                         {:id (:id args)
                          :name "Wireless Headphones"
                          :price 99.99})}

    :products {:type '(list :Product)
               :resolve (fn [context args _parent]
                          [{:id "p1" :name "Headphones" :price 99.99}
                           {:id "p2" :name "Keyboard" :price 149.99}])}}})

(def compiled-ecommerce-schema
  (lacinia.schema/compile ecommerce-schema))

(defn create-defer-http-handler
  "Creates an HTTP handler that streams GraphQL responses with @defer."
  [schema]
  (reify HttpHandler
    (handle [_ exchange]
      (try
        (let [request-method (.getRequestMethod exchange)]
          (if (= "POST" request-method)
            (let [;; Read request body
                  input-stream (.getRequestBody exchange)
                  body-bytes (.readAllBytes input-stream)
                  body-str (String. body-bytes "UTF-8")
                  request-data (json/parse-string body-str true)
                  query (:query request-data)
                  variables (:variables request-data)]

              ;; Execute query with defer support
              (let [{:keys [initial-result deferred-stream]} (lacinia/execute-with-defer schema query variables nil)
                    response-headers (.getResponseHeaders exchange)]

                ;; Set headers for streaming response
                (.set response-headers "Content-Type" "application/json")
                (.set response-headers "Transfer-Encoding" "chunked")
                (.set response-headers "Cache-Control" "no-cache")

                ;; Start response with 200 OK
                (.sendResponseHeaders exchange 200 0)

                ;; Stream responses
                (let [output-stream (.getResponseBody exchange)]
                  (go
                    (try
                      ;; Send initial response
                      (let [p (promise)
                            _ (resolve/on-deliver! initial-result #(deliver p %))
                            initial @p
                            json-response (json/generate-string initial)
                            response-line (str json-response "\\n")]
                        (.write output-stream (.getBytes response-line "UTF-8"))
                        (.flush output-stream))
                      
                      ;; Stream deferred responses
                      (loop []
                        (when-let [response (<!! deferred-stream)]
                          (let [json-response (json/generate-string response)
                                response-line (str json-response "\\n")]
                            (.write output-stream (.getBytes response-line "UTF-8"))
                            (.flush output-stream))
                          (recur)))
                      (finally
                        (.close output-stream)))))))

            ;; Handle non-POST requests
            (do
              (.sendResponseHeaders exchange 405 0)
              (.close (.getResponseBody exchange)))))

        (catch Exception e
          (println "Error handling request:" (.getMessage e))
          (.sendResponseHeaders exchange 500 0)
          (.close (.getResponseBody exchange)))))))

(defn start-test-server
  "Starts a test HTTP server on an available port."
  [handler]
  (let [server (HttpServer/create (InetSocketAddress. 0) 0)]
    (.createContext server "/graphql" handler)
    (.start server)
    server))

(defn send-graphql-request
  "Sends a GraphQL request and returns the streaming response lines."
  [port query]
  (let [client (HttpClient/newHttpClient)
        request-body (json/generate-string {:query query})
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI. (str "http://localhost:" port "/graphql")))
                    (.header "Content-Type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString request-body))
                    (.timeout (Duration/ofSeconds 30))
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))]

    (if (= 200 (.statusCode response))
      (str/split-lines (.body response))
      (throw (Exception. (str "HTTP " (.statusCode response) ": " (.body response)))))))

(deftest test-defer-http-streaming-integration
  (testing "HTTP streaming integration with @defer directive"
    (let [handler (create-defer-http-handler compiled-ecommerce-schema)
          server (start-test-server handler)
          port (.getPort (.getAddress server))]

      (try
        (let [query "{ product(id: \"p1\") { 
                        id 
                        name 
                        price @defer(label: \"pricing\")
                        inventory @defer(label: \"stock\") { 
                          quantity 
                          warehouse 
                        }
                      } }"
              start-time (System/currentTimeMillis)
              response-lines (send-graphql-request port query)
              end-time (System/currentTimeMillis)
              total-time (- end-time start-time)]

          (println (str "Total HTTP streaming request time: " total-time "ms"))

          ;; Should receive multiple JSON responses
          (is (>= (count response-lines) 2) "Should receive at least 2 streaming responses")

          ;; Parse each response line
          (let [responses (map #(json/parse-string % true) response-lines)
                initial-response (first responses)
                deferred-responses (rest responses)]

            ;; Initial response should have immediate fields
            (is (= "p1" (get-in initial-response [:data :product :id])))
            (is (= "Wireless Headphones" (get-in initial-response [:data :product :name])))

            ;; Price and inventory should be deferred
            (is (= "deferred" (name (get-in initial-response [:data :product :price]))))
            (is (= "deferred" (name (get-in initial-response [:data :product :inventory]))))

            ;; Should receive deferred responses
            (is (>= (count deferred-responses) 2) "Should receive deferred responses")

            ;; Verify deferred response structure
            (let [pricing-response (first (filter #(= "pricing" (:label %)) deferred-responses))
                  stock-response (first (filter #(= "stock" (:label %)) deferred-responses))]

              (is (some? pricing-response) "Should receive pricing response")
              (is (= [:product :price] (:path pricing-response)))
              (is (= 99.99 (get-in pricing-response [:data :product :price])))

              (is (some? stock-response) "Should receive stock response")
              (is (= [:product :inventory] (:path stock-response)))
              (is (= 50 (get-in stock-response [:data :product :inventory :quantity]))))))

        (finally
          (.stop server 0))))))

(deftest test-defer-http-performance-comparison
  (testing "Performance comparison: normal vs defer execution"
    (let [handler (create-defer-http-handler compiled-ecommerce-schema)
          server (start-test-server handler)
          port (.getPort (.getAddress server))]

      (try
        ;; Test normal query (all fields together)
        (let [normal-query "{ product(id: \"p1\") { id name price inventory { quantity warehouse } } }"
              start-time (System/currentTimeMillis)
              normal-responses (send-graphql-request port normal-query)
              normal-time (- (System/currentTimeMillis) start-time)]

          (println (str "Normal query time: " normal-time "ms"))
          (is (= 1 (count normal-responses)) "Normal query should return single response")

          ;; Test deferred query
          (let [defer-query "{ product(id: \"p1\") { 
                                id 
                                name 
                                price @defer(label: \"pricing\")
                                inventory @defer(label: \"stock\") { 
                                  quantity 
                                  warehouse 
                                } 
                              } }"
                start-time (System/currentTimeMillis)
                defer-responses (send-graphql-request port defer-query)
                defer-time (- (System/currentTimeMillis) start-time)]

            (println (str "Deferred query time: " defer-time "ms"))
            (is (>= (count defer-responses) 2) "Deferred query should return multiple responses")

            ;; With defer, initial response should come faster than full normal response
            ;; (though total time may be similar due to network overhead)
            (println (str "Defer provided " (count defer-responses) " streaming responses"))))

        (finally
          (.stop server 0))))))

(deftest test-defer-http-error-handling
  (testing "HTTP error handling with @defer directive"
    (let [;; Create schema with resolver that throws error
                                    error-schema (lacinia.schema/compile
                        {:objects
                         {:User {:fields {:id {:type 'String}
                                          :name {:type 'String}
                                          :error_field {:type 'String
                                                        :resolve (fn [_ _ _]
                                                                   (throw (Exception. "Simulated error")))}}}}
                         :queries
                         {:user {:type :User
                                 :resolve (fn [_ _ _] {:id "1" :name "John"})}}})
          handler (create-defer-http-handler error-schema)
          server (start-test-server handler)
          port (.getPort (.getAddress server))]

      (try
        (let [query "{ user { id name error_field @defer(label: \"error\") } }"
              response-lines (send-graphql-request port query)]

          ;; Should still receive responses even with errors in deferred fields
          (is (>= (count response-lines) 1) "Should receive at least initial response")

          (let [responses (map #(json/parse-string % true) response-lines)
                initial-response (first responses)]

            ;; Initial response should have valid fields
            (is (= "1" (get-in initial-response [:data :user :id])))
            (is (= "John" (get-in initial-response [:data :user :name])))

            ;; Error field should be deferred
            (is (= "deferred" (name (get-in initial-response [:data :user :error_field]))))))

        (finally
          (.stop server 0))))))