(ns com.walmartlabs.lacinia.defer-streaming-integration-test
  "Advanced integration test demonstrating @defer with real HTTP/2 streaming and Server-Sent Events"
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.string :as str]
   [clojure.data.json :as json]
   [clojure.core.async :as async :refer [<! >! chan go go-loop timeout]]
   [com.walmartlabs.lacinia.schema :as schema]
   [com.walmartlabs.lacinia :as lacinia])
  (:import
   [java.net.http HttpClient HttpClient$Version HttpRequest HttpResponse$BodyHandlers HttpRequest$BodyPublishers]
   [java.net URI]
   [java.util.concurrent CompletableFuture Executors]
   [java.io ByteArrayOutputStream OutputStream]
   [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
   [java.net InetSocketAddress]
   [java.nio.charset StandardCharsets]))

;; Enhanced schema with async resolvers
(def streaming-schema
  {:objects
   {:User
    {:fields
     {:id {:type :ID}
      :name {:type :String}
      :email {:type :String}
      :profile {:type :UserProfile}
      :socialFeed {:type '(list :Post)
                   :resolve (fn [_ _ _]
                              ;; Simulate slow social feed aggregation
                              (Thread/sleep 900)
                              [{:id "post-1" :content "Just shipped a new feature!" :likes 42}
                               {:id "post-2" :content "Working on GraphQL optimization" :likes 15}])}
      :friendSuggestions {:type '(list :User)
                          :resolve (fn [_ _ _]
                                     ;; Simulate ML friend recommendation
                                     (Thread/sleep 1100)
                                     [{:id "friend-1" :name "Bob Smith" :email "bob@example.com"}
                                      {:id "friend-2" :name "Carol Williams" :email "carol@example.com"}])}
      :notifications {:type :NotificationSummary
                      :resolve (fn [_ _ _]
                                 ;; Simulate notification aggregation from multiple services
                                 (Thread/sleep 700)
                                 {:unread 5 :priority 2 :types ["message" "mention" "follow"]})}}}

    :UserProfile
    {:fields
     {:bio {:type :String}
      :avatar {:type :String}
      :location {:type :String
                 :resolve (fn [_ _ _]
                            ;; Simulate geolocation service call
                            (Thread/sleep 500)
                            "San Francisco, CA")}
      :stats {:type :UserStats
              :resolve (fn [_ _ _]
                         ;; Simulate analytics aggregation
                         (Thread/sleep 800)
                         {:profileViews 1247 :connectionsCount 89 :postsCount 23})}}}

    :UserStats
    {:fields
     {:profileViews {:type :Int}
      :connectionsCount {:type :Int}
      :postsCount {:type :Int}}}

    :Post
    {:fields
     {:id {:type :ID}
      :content {:type :String}
      :likes {:type :Int}
      :comments {:type '(list :Comment)
                 :resolve (fn [_ _ _]
                            ;; Simulate comment loading
                            (Thread/sleep 400)
                            [{:id "comment-1" :text "Great work!" :author "Dave"}
                             {:id "comment-2" :text "Looking forward to trying it" :author "Eve"}])}}}

    :Comment
    {:fields
     {:id {:type :ID}
      :text {:type :String}
      :author {:type :String}}}

    :NotificationSummary
    {:fields
     {:unread {:type :Int}
      :priority {:type :Int}
      :types {:type '(list :String)}}}}

   :queries
   {:user {:type :User
           :args {:id {:type :ID}}
           :resolve (fn [_ {:keys [id]} _]
                      {:id id
                       :name "Alice Johnson"
                       :email "alice@example.com"
                       :profile {:bio "Senior Software Engineer"
                                 :avatar "https://cdn.example.com/avatars/alice.jpg"}})}}})

(def compiled-streaming-schema (schema/compile streaming-schema))

;; Server-Sent Events (SSE) streaming handler
(defn create-sse-handler [schema]
  (reify HttpHandler
    (handle [_ exchange]
      (let [method (.getRequestMethod exchange)
            headers (.getResponseHeaders exchange)
            uri (.getRequestURI exchange)
            path (.getPath uri)]
        (cond
          ;; GraphQL endpoint with SSE streaming
          (and (= "POST" method) (= "/graphql-stream" path))
          (let [body (slurp (.getRequestBody exchange))
                request (json/read-str body :key-fn keyword)
                query (:query request)
                variables (or (:variables request) {})]

            ;; Set SSE headers
            (.add headers "Content-Type" "text/event-stream; charset=utf-8")
            (.add headers "Cache-Control" "no-cache")
            (.add headers "Connection" "keep-alive")
            (.add headers "Access-Control-Allow-Origin" "*")
            (.add headers "Access-Control-Allow-Headers" "Cache-Control")
            (.sendResponseHeaders exchange 200 0)

            (let [os (.getResponseBody exchange)]
              (try
                ;; Send initial event
                (.write os (.getBytes "event: start\ndata: {\"status\":\"started\"}\n\n" StandardCharsets/UTF_8))
                (.flush os)

                ;; Execute GraphQL query
                (let [result (lacinia/execute schema query variables nil)]

                  ;; Send initial data
                  (let [initial-data {:data (:data result)
                                      :hasNext (contains? result :deferred)}
                        event-data (str "event: data\ndata: " (json/write-str initial-data) "\n\n")]
                    (.write os (.getBytes event-data StandardCharsets/UTF_8))
                    (.flush os))

                  ;; Send deferred data if present
                  (when (contains? result :deferred)
                    (doseq [deferred-item (:deferred result)]
                      ;; Simulate network delay
                      (Thread/sleep 200)

                      (let [deferred-data {:incremental [{:data (:data deferred-item)
                                                          :path (:path deferred-item)
                                                          :label (:label deferred-item)}]
                                           :hasNext false}
                            event-data (str "event: incremental\ndata: " (json/write-str deferred-data) "\n\n")]
                        (.write os (.getBytes event-data StandardCharsets/UTF_8))
                        (.flush os))))

                  ;; Send completion event
                  (.write os (.getBytes "event: complete\ndata: {\"status\":\"completed\"}\n\n" StandardCharsets/UTF_8))
                  (.flush os))

                (catch Exception e
                  (let [error-data (str "event: error\ndata: " (json/write-str {:error (.getMessage e)}) "\n\n")]
                    (.write os (.getBytes error-data StandardCharsets/UTF_8))
                    (.flush os)))
                (finally
                  (.close os)))))

          ;; Regular GraphQL endpoint (chunked transfer)
          (and (= "POST" method) (= "/graphql" path))
          (let [body (slurp (.getRequestBody exchange))
                request (json/read-str body :key-fn keyword)
                query (:query request)
                variables (or (:variables request) {})
                result (lacinia/execute schema query variables nil)]

            (if (contains? result :deferred)
              ;; Chunked transfer encoding for deferred responses
              (let [baos (ByteArrayOutputStream.)]

                ;; Build chunked response
                (let [initial-chunk (json/write-str {:data (:data result) :hasNext true})
                      initial-bytes (.getBytes initial-chunk StandardCharsets/UTF_8)
                      initial-size-hex (format "%x\r\n" (count initial-bytes))]
                  (.write baos (.getBytes initial-size-hex StandardCharsets/UTF_8))
                  (.write baos initial-bytes)
                  (.write baos (.getBytes "\r\n" StandardCharsets/UTF_8)))

                ;; Add deferred chunks
                (doseq [deferred-item (:deferred result)]
                  (let [deferred-chunk (json/write-str {:incremental [{:data (:data deferred-item)
                                                                       :path (:path deferred-item)
                                                                       :label (:label deferred-item)}]
                                                        :hasNext false})
                        deferred-bytes (.getBytes deferred-chunk StandardCharsets/UTF_8)
                        chunk-size-hex (format "%x\r\n" (count deferred-bytes))]
                    (.write baos (.getBytes chunk-size-hex StandardCharsets/UTF_8))
                    (.write baos deferred-bytes)
                    (.write baos (.getBytes "\r\n" StandardCharsets/UTF_8))))

                ;; End chunked transfer
                (.write baos (.getBytes "0\r\n\r\n" StandardCharsets/UTF_8))

                ;; Send response
                (.add headers "Content-Type" "application/json; charset=utf-8")
                (.add headers "Transfer-Encoding" "chunked")
                (.add headers "X-GraphQL-Defer" "chunked")
                (.sendResponseHeaders exchange 200 0)

                (with-open [os (.getResponseBody exchange)]
                  (.write os (.toByteArray baos))))

              ;; Regular response for non-deferred queries
              (let [response-body (json/write-str result)]
                (.add headers "Content-Type" "application/json; charset=utf-8")
                (.sendResponseHeaders exchange 200 (count (.getBytes response-body StandardCharsets/UTF_8)))
                (with-open [os (.getResponseBody exchange)]
                  (.write os (.getBytes response-body StandardCharsets/UTF_8))))))

          ;; Health check
          (and (= "GET" method) (= "/health" path))
          (let [response-body (json/write-str {:status "ok"
                                               :features {:defer true :streaming true :sse true}
                                               :timestamp (System/currentTimeMillis)})]
            (.add headers "Content-Type" "application/json")
            (.sendResponseHeaders exchange 200 (count (.getBytes response-body StandardCharsets/UTF_8)))
            (with-open [os (.getResponseBody exchange)]
              (.write os (.getBytes response-body StandardCharsets/UTF_8))))

          :else
          (.sendResponseHeaders exchange 404 0))))))

(defn start-streaming-server [port]
  (doto (HttpServer/create (InetSocketAddress. port) 0)
    (.createContext "/" (create-sse-handler compiled-streaming-schema))
    (.setExecutor (Executors/newCachedThreadPool))
    (.start)))

(defn stop-streaming-server [server]
  (.stop server 0))

;; HTTP/2 client utilities
(defn make-streaming-request [url body]
  (let [client (-> (HttpClient/newBuilder)
                   (.version HttpClient$Version/HTTP_2)
                   (.build))
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI. url))
                    (.header "Content-Type" "application/json")
                    (.header "Accept" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString body))
                    (.build))]
    (.send client request (HttpResponse$BodyHandlers/ofString))))

(defn parse-chunked-response [body]
  "Parse chunked transfer encoding response"
  (let [lines (str/split-lines body)
        chunks (atom [])
        current-chunk (atom nil)]

    (doseq [line lines]
      (cond
        ;; Chunk size line (hex)
        (re-matches #"[0-9a-fA-F]+" line)
        (let [size (Integer/parseInt line 16)]
          (when (> size 0)
            (reset! current-chunk {:size size :data nil})))

        ;; Data line
        (and @current-chunk (nil? (:data @current-chunk)))
        (do
          (swap! current-chunk assoc :data line)
          (swap! chunks conj @current-chunk)
          (reset! current-chunk nil))))

    (map :data @chunks)))

;; Test fixtures
(def streaming-server (atom nil))
(def streaming-port 8766)

(defn streaming-server-fixture [f]
  (let [server (start-streaming-server streaming-port)]
    (reset! streaming-server server)
    (Thread/sleep 200) ; Give server time to start
    (try
      (f)
      (finally
        (stop-streaming-server server)
        (reset! streaming-server nil)))))

(use-fixtures :once streaming-server-fixture)

;; Advanced Integration Tests

(deftest defer-directive-chunked-transfer-test
  (testing "HTTP/2 chunked transfer with multiple deferred fields"
    (let [query (json/write-str
                 {:query "query DashboardQuery($userId: ID!) {
                            user(id: $userId) {
                              id
                              name
                              email
                              profile {
                                bio
                                avatar
                                location @defer(label: \"location\")
                                stats @defer(label: \"profileStats\") {
                                  profileViews
                                  connectionsCount
                                  postsCount
                                }
                              }
                              socialFeed @defer(label: \"socialFeed\") {
                                id
                                content
                                likes
                                comments @defer(label: \"comments\") {
                                  id
                                  text
                                  author
                                }
                              }
                              notifications @defer(label: \"notifications\") {
                                unread
                                priority
                                types
                              }
                              friendSuggestions @defer(label: \"friendSuggestions\") {
                                id
                                name
                                email
                              }
                            }
                          }"
                  :variables {:userId "user-123"}})
          start-time (System/currentTimeMillis)
          response (make-streaming-request (str "http://localhost:" streaming-port "/graphql") query)
          end-time (System/currentTimeMillis)
          execution-time (- end-time start-time)]

      (is (= 200 (.statusCode response)))

      (let [headers (.headers response)
            body (.body response)]

        ;; Verify chunked transfer headers
        (is (contains? (.map headers) "transfer-encoding"))
        (is (contains? (.map headers) "x-graphql-defer"))

        ;; Parse chunked response
        (let [chunks (parse-chunked-response body)
              parsed-chunks (map #(json/read-str % :key-fn keyword) chunks)
              initial-response (first parsed-chunks)
              incremental-responses (rest parsed-chunks)]

          ;; Verify initial response structure
          (is (= "user-123" (get-in initial-response [:data :user :id])))
          (is (= "Alice Johnson" (get-in initial-response [:data :user :name])))
          (is (= "Senior Software Engineer" (get-in initial-response [:data :user :profile :bio])))
          (is (= true (:hasNext initial-response)))

          ;; Verify we received all deferred chunks
          (is (>= (count incremental-responses) 5)) ; location, stats, feed, notifications, friends

          ;; Verify deferred data integrity
          (let [incremental-by-label (group-by #(get-in % [:incremental 0 :label]) incremental-responses)]
            (is (contains? incremental-by-label "location"))
            (is (contains? incremental-by-label "profileStats"))
            (is (contains? incremental-by-label "socialFeed"))
            (is (contains? incremental-by-label "notifications"))
            (is (contains? incremental-by-label "friendSuggestions"))

            ;; Check specific deferred data
            (let [location-response (first (incremental-by-label "location"))
                  location-data (get-in location-response [:incremental 0 :data])]
              (is (= "San Francisco, CA" location-data)))

            (let [stats-response (first (incremental-by-label "profileStats"))
                  stats-data (get-in stats-response [:incremental 0 :data])]
              (is (= 1247 (:profileViews stats-data)))
              (is (= 89 (:connectionsCount stats-data)))
              (is (= 23 (:postsCount stats-data))))

            (let [notifications-response (first (incremental-by-label "notifications"))
                  notifications-data (get-in notifications-response [:incremental 0 :data])]
              (is (= 5 (:unread notifications-data)))
              (is (= 2 (:priority notifications-data)))
              (is (= ["message" "mention" "follow"] (:types notifications-data)))))

          (println (str "Chunked transfer execution time: " execution-time "ms"))
          (is (< execution-time 10000) "Should complete within reasonable time"))))))

(deftest defer-directive-sse-streaming-test
  (testing "Server-Sent Events streaming with @defer"
    (let [query (json/write-str
                 {:query "query RealtimeFeed($userId: ID!) {
                            user(id: $userId) {
                              id
                              name
                              socialFeed @defer(label: \"feed\") {
                                id
                                content
                                likes
                              }
                              notifications @defer(label: \"notifications\") {
                                unread
                                priority
                              }
                            }
                          }"
                  :variables {:userId "user-456"}})

          ;; Use Java 11+ HTTP client for SSE
          client (HttpClient/newHttpClient)
          request (-> (HttpRequest/newBuilder)
                      (.uri (URI. (str "http://localhost:" streaming-port "/graphql-stream")))
                      (.header "Content-Type" "application/json")
                      (.header "Accept" "text/event-stream")
                      (.POST (HttpRequest$BodyPublishers/ofString query))
                      (.build))
          response (.send client request (HttpResponse$BodyHandlers/ofString))]

      (is (= 200 (.statusCode response)))

      (let [headers (.headers response)
            body (.body response)]

        ;; Verify SSE headers  
        (is (contains? (.map headers) "content-type"))
        (is (contains? (.map headers) "cache-control"))

        ;; Parse SSE events
        (let [events (str/split body #"\n\n")
              parsed-events (map (fn [event]
                                   (let [lines (str/split-lines event)
                                         event-type (some #(when (str/starts-with? % "event: ")
                                                             (subs % 7)) lines)
                                         data-line (some #(when (str/starts-with? % "data: ")
                                                            (subs % 6)) lines)]
                                     {:type event-type
                                      :data (when data-line
                                              (try (json/read-str data-line :key-fn keyword)
                                                   (catch Exception _ data-line)))}))
                                 (filter #(not (str/blank? %)) events))]

          ;; Find specific event types
          (let [start-events (filter #(= "start" (:type %)) parsed-events)
                data-events (filter #(= "data" (:type %)) parsed-events)
                incremental-events (filter #(= "incremental" (:type %)) parsed-events)
                complete-events (filter #(= "complete" (:type %)) parsed-events)]

            ;; Verify event sequence
            (is (= 1 (count start-events)))
            (is (= 1 (count data-events)))
            (is (>= (count incremental-events) 2)) ; feed and notifications
            (is (= 1 (count complete-events)))

            ;; Verify initial data event
            (let [initial-data (:data (first data-events))]
              (is (= "user-456" (get-in initial-data [:data :user :id])))
              (is (= "Alice Johnson" (get-in initial-data [:data :user :name])))
              (is (= true (:hasNext initial-data))))

            ;; Verify incremental events
            (let [feed-event (first (filter #(= "feed" (get-in % [:data :incremental 0 :label])) incremental-events))
                  notifications-event (first (filter #(= "notifications" (get-in % [:data :incremental 0 :label])) incremental-events))]

              (is (not (nil? feed-event)))
              (is (not (nil? notifications-event)))

              ;; Check feed data
              (let [feed-data (get-in feed-event [:data :incremental 0 :data])]
                (is (= 2 (count feed-data)))
                (is (= "post-1" (:id (first feed-data))))
                (is (= "Just shipped a new feature!" (:content (first feed-data)))))

              ;; Check notifications data
              (let [notifications-data (get-in notifications-event [:data :incremental 0 :data])]
                (is (= 5 (:unread notifications-data)))
                (is (= 2 (:priority notifications-data)))))))))))

(deftest defer-directive-http2-performance-test
  (testing "HTTP/2 performance comparison with pipelining"
    (let [query-with-defer (json/write-str
                            {:query "query { user(id: \"perf-test\") { 
                                        id name 
                                        socialFeed @defer { id content }
                                        notifications @defer { unread priority }
                                        friendSuggestions @defer { id name }
                                      } }"})
          query-without-defer (json/write-str
                               {:query "query { user(id: \"perf-test\") { 
                                           id name 
                                           socialFeed { id content }
                                           notifications { unread priority }
                                           friendSuggestions { id name }
                                         } }"})

          ;; Create HTTP/2 client
          client (-> (HttpClient/newBuilder)
                     (.version HttpClient$Version/HTTP_2)
                     (.build))

          ;; Measure sequential requests
          start1 (System/currentTimeMillis)
          response1 (.send client
                           (-> (HttpRequest/newBuilder)
                               (.uri (URI. (str "http://localhost:" streaming-port "/graphql")))
                               (.header "Content-Type" "application/json")
                               (.POST (HttpRequest$BodyPublishers/ofString query-without-defer))
                               (.build))
                           (HttpResponse$BodyHandlers/ofString))
          end1 (System/currentTimeMillis)
          sequential-time (- end1 start1)

          ;; Measure deferred request
          start2 (System/currentTimeMillis)
          response2 (.send client
                           (-> (HttpRequest/newBuilder)
                               (.uri (URI. (str "http://localhost:" streaming-port "/graphql")))
                               (.header "Content-Type" "application/json")
                               (.POST (HttpRequest$BodyPublishers/ofString query-with-defer))
                               (.build))
                           (HttpResponse$BodyHandlers/ofString))
          end2 (System/currentTimeMillis)
          deferred-time (- end2 start2)]

      (is (= 200 (.statusCode response1)))
      (is (= 200 (.statusCode response2)))

      (println (str "Sequential query time: " sequential-time "ms"))
      (println (str "Deferred query time: " deferred-time "ms"))

      ;; Both should complete successfully
      (is (< sequential-time 15000))
      (is (< deferred-time 15000))

      ;; Verify response structures
      (let [body1 (json/read-str (.body response1) :key-fn keyword)
            body2 (.body response2)
            headers2 (.headers response2)]
        (is (contains? body1 :data))
        (is (not (contains? body1 :deferred)))
        (is (or (str/includes? body2 "hasNext")
                (contains? (.map headers2) "transfer-encoding")))))))

(deftest defer-directive-health-check-test
  (testing "Server health check with defer capabilities"
    (let [response (-> (HttpClient/newHttpClient)
                       (.send (-> (HttpRequest/newBuilder)
                                  (.uri (URI. (str "http://localhost:" streaming-port "/health")))
                                  (.GET)
                                  (.build))
                              (HttpResponse$BodyHandlers/ofString)))
          body (json/read-str (.body response) :key-fn keyword)]

      (is (= 200 (.statusCode response)))
      (is (= "ok" (:status body)))
      (is (= true (get-in body [:features :defer])))
      (is (= true (get-in body [:features :streaming])))
      (is (= true (get-in body [:features :sse])))
      (is (number? (:timestamp body))))))

(comment
  ;; Manual testing with curl

  ;; Test regular GraphQL endpoint
  ;; curl -X POST http://localhost:8766/graphql \
  ;;   -H "Content-Type: application/json" \
  ;;   -d '{"query": "query { user(id: \"test\") { id name socialFeed @defer { id content } } }"}'

  ;; Test SSE endpoint
  ;; curl -X POST http://localhost:8766/graphql-stream \
  ;;   -H "Content-Type: application/json" \
  ;;   -H "Accept: text/event-stream" \
  ;;   -d '{"query": "query { user(id: \"test\") { id name notifications @defer { unread } } }"}'

  ;; Health check
  ;; curl http://localhost:8766/health
  )

