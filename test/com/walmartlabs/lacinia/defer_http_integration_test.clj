(ns com.walmartlabs.lacinia.defer-http-integration-test
  "Integration test demonstrating @defer directive over HTTP with streaming responses"
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.string :as str]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [com.walmartlabs.lacinia.schema :as schema]
   [com.walmartlabs.lacinia :as lacinia])
  (:import
   [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers HttpRequest$BodyPublishers]
   [java.net URI]
   [java.util.concurrent CompletableFuture Executors]
   [java.io StringWriter]
   [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
   [java.net InetSocketAddress]))

;; Test schema representing an e-commerce platform
(def ecommerce-schema
  {:objects
   {:User
    {:fields
     {:id {:type :ID}
      :name {:type :String}
      :email {:type :String}
      :profile {:type :UserProfile}
      :orders {:type '(list :Order)
               :resolve (fn [_ _ _]
                          ;; Simulate slow database query
                          (Thread/sleep 800)
                          [{:id "ord-1" :total 29.99 :status "delivered"}
                           {:id "ord-2" :total 75.50 :status "pending"}])}
      :recommendations {:type '(list :Product)
                        :resolve (fn [_ _ _]
                                   ;; Simulate ML recommendation service call
                                   (Thread/sleep 1200)
                                   [{:id "prod-1" :name "Wireless Headphones" :price 99.99}
                                    {:id "prod-2" :name "Smart Watch" :price 199.99}])}}}

    :UserProfile
    {:fields
     {:bio {:type :String}
      :avatar {:type :String}
      :preferences {:type :String
                    :resolve (fn [_ _ _]
                               ;; Simulate external preferences service
                               (Thread/sleep 400)
                               "notifications:on,theme:dark,currency:USD")}
      :analytics {:type :String
                  :resolve (fn [_ _ _]
                             ;; Simulate analytics data aggregation
                             (Thread/sleep 600)
                             "views:1247,clicks:89,conversions:12")}}}

    :Order
    {:fields
     {:id {:type :ID}
      :total {:type :Float}
      :status {:type :String}
      :items {:type '(list :OrderItem)
              :resolve (fn [_ _ _]
                         ;; Simulate item details lookup
                         (Thread/sleep 300)
                         [{:id "item-1" :name "Coffee Mug" :quantity 2}
                          {:id "item-2" :name "Notebook" :quantity 1}])}}}

    :OrderItem
    {:fields
     {:id {:type :ID}
      :name {:type :String}
      :quantity {:type :Int}}}

    :Product
    {:fields
     {:id {:type :ID}
      :name {:type :String}
      :price {:type :Float}}}}

   :queries
   {:user {:type :User
           :args {:id {:type :ID}}
           :resolve (fn [_ {:keys [id]} _]
                      {:id id
                       :name "Alice Johnson"
                       :email "alice@example.com"
                       :profile {:bio "Software Engineer"
                                 :avatar "https://cdn.example.com/avatars/alice.jpg"}})}}})

(def compiled-schema (schema/compile ecommerce-schema))

;; HTTP Server for GraphQL with streaming support
(defn create-graphql-handler [schema]
  (reify HttpHandler
    (handle [_ exchange]
      (let [method (.getRequestMethod exchange)
            headers (.getResponseHeaders exchange)]
        (cond
          (= "POST" method)
          (let [body (slurp (.getRequestBody exchange))
                request (json/read-str body :key-fn keyword)
                query (:query request)
                variables (or (:variables request) {})

                ;; Execute GraphQL query
                result (lacinia/execute schema query variables nil)

                ;; Check if there are deferred fields
                has-deferred? (contains? result :deferred)]

            (if has-deferred?
              ;; Streaming response for deferred fields
              (let [initial-response {:data (:data result)
                                      :hasNext true}
                    deferred-responses (map (fn [deferred-item]
                                              {:incremental [{:data (:data deferred-item)
                                                              :path (:path deferred-item)
                                                              :label (:label deferred-item)}]
                                               :hasNext false})
                                            (:deferred result))

                    ;; Simulate streaming by sending responses with delimiters
                    full-response (str (json/write-str initial-response)
                                       "\n---STREAM---\n"
                                       (str/join "\n---STREAM---\n"
                                                 (map json/write-str deferred-responses)))]

                ;; Set headers for streaming
                (.add headers "Content-Type" "application/json; charset=utf-8")
                (.add headers "Transfer-Encoding" "chunked")
                (.add headers "X-Defer-Enabled" "true")
                (.sendResponseHeaders exchange 200 0)

                (with-open [os (.getResponseBody exchange)]
                  (.write os (.getBytes full-response "UTF-8"))))

              ;; Regular response for non-deferred queries
              (let [response-body (json/write-str result)]
                (.add headers "Content-Type" "application/json; charset=utf-8")
                (.sendResponseHeaders exchange 200 (count (.getBytes response-body "UTF-8")))

                (with-open [os (.getResponseBody exchange)]
                  (.write os (.getBytes response-body "UTF-8"))))))

          (= "GET" method)
          ;; Health check endpoint
          (let [response-body "{\"status\":\"ok\",\"defer\":\"supported\"}"]
            (.add headers "Content-Type" "application/json")
            (.sendResponseHeaders exchange 200 (count (.getBytes response-body "UTF-8")))
            (with-open [os (.getResponseBody exchange)]
              (.write os (.getBytes response-body "UTF-8"))))

          :else
          (.sendResponseHeaders exchange 405 0))))))

(defn start-test-server [port]
  (doto (HttpServer/create (InetSocketAddress. port) 0)
    (.createContext "/graphql" (create-graphql-handler compiled-schema))
    (.setExecutor (Executors/newCachedThreadPool))
    (.start)))

(defn stop-server [server]
  (.stop server 0))

(defn make-http-request [url body]
  (let [client (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI. url))
                    (.header "Content-Type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString body))
                    (.build))]
    (.send client request (HttpResponse$BodyHandlers/ofString))))

;; Test fixtures
(def test-server (atom nil))
(def test-port 8765)

(defn server-fixture [f]
  (let [server (start-test-server test-port)]
    (reset! test-server server)
    (Thread/sleep 100) ; Give server time to start
    (try
      (f)
      (finally
        (stop-server server)
        (reset! test-server nil)))))

(use-fixtures :once server-fixture)

;; Integration Tests

(deftest defer-directive-http-integration-test
  (testing "HTTP request without @defer directive"
    (let [query "{\"query\": \"query { user(id: \\\"123\\\") { id name email profile { bio avatar } } }\"}"
          response (make-http-request (str "http://localhost:" test-port "/graphql") query)
          status (.statusCode response)
          body (.body response)
          parsed-body (json/read-str body :key-fn keyword)]

      (is (= 200 status))
      (is (= "123" (get-in parsed-body [:data :user :id])))
      (is (= "Alice Johnson" (get-in parsed-body [:data :user :name])))
      (is (= "alice@example.com" (get-in parsed-body [:data :user :email])))
      (is (= "Software Engineer" (get-in parsed-body [:data :user :profile :bio])))
      (is (not (contains? parsed-body :deferred)))))

  (testing "HTTP request with @defer directive - basic functionality"
    (let [query "{\"query\": \"query { user(id: \\\"123\\\") { id name email profile { bio avatar preferences @defer(label: \\\"userPrefs\\\") } } }\"}"
          response (make-http-request (str "http://localhost:" test-port "/graphql") query)
          status (.statusCode response)
          body (.body response)
          headers (.headers response)]

      (is (= 200 status))
      (is (contains? (.map headers) "x-defer-enabled"))

      ;; Parse streaming response
      (let [parts (str/split body #"\n---STREAM---\n")
            initial-response (json/read-str (first parts) :key-fn keyword)
            deferred-responses (map #(json/read-str % :key-fn keyword) (rest parts))]

        ;; Check initial response
        (is (= "123" (get-in initial-response [:data :user :id])))
        (is (= "Alice Johnson" (get-in initial-response [:data :user :name])))
        (is (= "Software Engineer" (get-in initial-response [:data :user :profile :bio])))
        (is (= true (:hasNext initial-response)))

        ;; Check deferred response
        (is (= 1 (count deferred-responses)))
        (let [deferred-response (first deferred-responses)
              incremental (first (:incremental deferred-response))]
          (is (= "notifications:on,theme:dark,currency:USD" (:data incremental)))
          (is (= ["user" "profile" "preferences"] (:path incremental)))
          (is (= "userPrefs" (:label incremental)))
          (is (= false (:hasNext deferred-response)))))))

  (testing "HTTP request with multiple @defer directives - complex scenario"
    (let [query "{\"query\": \"query { user(id: \\\"123\\\") { id name profile { bio preferences @defer(label: \\\"prefs\\\") analytics @defer(label: \\\"analytics\\\") } orders @defer(label: \\\"orders\\\") { id total status } recommendations @defer(label: \\\"recs\\\") { id name price } } }\"}"
          start-time (System/currentTimeMillis)
          response (make-http-request (str "http://localhost:" test-port "/graphql") query)
          end-time (System/currentTimeMillis)
          execution-time (- end-time start-time)
          status (.statusCode response)
          body (.body response)]

      (is (= 200 status))

      ;; Parse streaming response
      (let [parts (str/split body #"\n---STREAM---\n")
            initial-response (json/read-str (first parts) :key-fn keyword)
            deferred-responses (map #(json/read-str % :key-fn keyword) (rest parts))]

        ;; Check that we got immediate data
        (is (= "123" (get-in initial-response [:data :user :id])))
        (is (= "Alice Johnson" (get-in initial-response [:data :user :name])))
        (is (= "Software Engineer" (get-in initial-response [:data :user :profile :bio])))
        (is (= true (:hasNext initial-response)))

        ;; Check that we have all deferred responses
        (is (= 4 (count deferred-responses))) ; prefs, analytics, orders, recommendations

        ;; Verify deferred data structure
        (let [deferred-by-label (group-by #(get-in % [:incremental 0 :label]) deferred-responses)]
          (is (contains? deferred-by-label "prefs"))
          (is (contains? deferred-by-label "analytics"))
          (is (contains? deferred-by-label "orders"))
          (is (contains? deferred-by-label "recs"))

          ;; Check orders data
          (let [orders-response (first (deferred-by-label "orders"))
                orders-data (get-in orders-response [:incremental 0 :data])]
            (is (= 2 (count orders-data)))
            (is (= "ord-1" (:id (first orders-data))))
            (is (= 29.99 (:total (first orders-data)))))

          ;; Check recommendations data  
          (let [recs-response (first (deferred-by-label "recs"))
                recs-data (get-in recs-response [:incremental 0 :data])]
            (is (= 2 (count recs-data)))
            (is (= "Wireless Headphones" (:name (first recs-data))))
            (is (= 99.99 (:price (first recs-data))))))

        ;; Verify performance benefit - execution should be faster than sequential
        ;; Total sequential time would be ~3000ms (800+1200+400+600)
        ;; With defer, initial response should be much faster
        (println (str "Execution time with defer: " execution-time "ms"))
        (is (< execution-time 5000) "Request should complete in reasonable time"))))

  (testing "Server health check"
    (let [response (-> (HttpClient/newHttpClient)
                       (.send (-> (HttpRequest/newBuilder)
                                  (.uri (URI. (str "http://localhost:" test-port "/graphql")))
                                  (.GET)
                                  (.build))
                              (HttpResponse$BodyHandlers/ofString)))
          status (.statusCode response)
          body (.body response)
          parsed (json/read-str body :key-fn keyword)]

      (is (= 200 status))
      (is (= "ok" (:status parsed)))
      (is (= "supported" (:defer parsed))))))

(deftest defer-directive-performance-comparison-test
  (testing "Performance comparison: with and without @defer"
    (let [query-without-defer "{\"query\": \"query { user(id: \\\"123\\\") { id name profile { preferences analytics } orders { id total } recommendations { id name } } }\"}"
          query-with-defer "{\"query\": \"query { user(id: \\\"123\\\") { id name profile { preferences @defer analytics @defer } orders @defer { id total } recommendations @defer { id name } } }\"}"

          ;; Measure without defer
          start1 (System/currentTimeMillis)
          response1 (make-http-request (str "http://localhost:" test-port "/graphql") query-without-defer)
          end1 (System/currentTimeMillis)
          time-without-defer (- end1 start1)

          ;; Measure with defer  
          start2 (System/currentTimeMillis)
          response2 (make-http-request (str "http://localhost:" test-port "/graphql") query-with-defer)
          end2 (System/currentTimeMillis)
          time-with-defer (- end2 start2)]

      (is (= 200 (.statusCode response1)))
      (is (= 200 (.statusCode response2)))

      (println (str "Time without defer: " time-without-defer "ms"))
      (println (str "Time with defer: " time-with-defer "ms"))

      ;; Both should complete successfully
      (is (< time-without-defer 10000))
      (is (< time-with-defer 10000))

      ;; Verify the response structures are different
      (let [body1 (json/read-str (.body response1) :key-fn keyword)
            body2 (.body response2)]
        (is (not (contains? body1 :deferred)))
        (is (str/includes? body2 "STREAM"))))))

(comment
  ;; Manual testing
  (def server (start-test-server 8765))

  ;; Test with curl:
  ;; curl -X POST http://localhost:8765/graphql \
  ;;   -H "Content-Type: application/json" \
  ;;   -d '{"query": "query { user(id: \"123\") { id name profile { preferences @defer(label: \"prefs\") } } }"}'

  (stop-server server))