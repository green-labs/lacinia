(ns defer-http-demo
  "Demonstration of @defer directive with HTTP integration"
  (:require
   [clojure.data.json :as json]
   [clojure.string :as str]
   [com.walmartlabs.lacinia.schema :as schema]
   [com.walmartlabs.lacinia :as lacinia])
  (:import
   [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers HttpRequest$BodyPublishers]
   [java.net URI]
   [java.util.concurrent Executors]
   [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
   [java.net InetSocketAddress]
   [java.nio.charset StandardCharsets]))

;; Demo schema for HTTP @defer functionality
(def demo-schema
  {:objects
   {:User
    {:fields
     {:id {:type :ID}
      :name {:type :String}
      :email {:type :String}
      :profile {:type :UserProfile}
      :analytics {:type :AnalyticsData
                  :resolve (fn [_ _ _]
                             (println "  📊 Loading analytics data...")
                             (Thread/sleep 1000)
                             {:pageViews 12847
                              :sessionDuration 345
                              :bounceRate 0.23})}
      :recommendations {:type '(list :Product)
                        :resolve (fn [_ _ _]
                                   (println "  🤖 Computing AI recommendations...")
                                   (Thread/sleep 1500)
                                   [{:id "rec-1" :name "Smart Watch" :price 299.99}
                                    {:id "rec-2" :name "Bluetooth Headphones" :price 149.99}])}
      :socialConnections {:type '(list :User)
                          :resolve (fn [_ _ _]
                                     (println "  👥 Fetching social connections...")
                                     (Thread/sleep 800)
                                     [{:id "friend-1" :name "Bob Smith" :email "bob@example.com"}
                                      {:id "friend-2" :name "Carol Williams" :email "carol@example.com"}])}}}

    :UserProfile
    {:fields
     {:bio {:type :String}
      :location {:type :String}
      :preferences {:type :String
                    :resolve (fn [_ _ _]
                               (println "  ⚙️  Loading user preferences...")
                               (Thread/sleep 600)
                               "theme:dark,notifications:enabled,currency:USD")}
      :privacySettings {:type :String
                        :resolve (fn [_ _ _]
                                   (println "  🔒 Checking privacy settings...")
                                   (Thread/sleep 400)
                                   "profile:private,location:hidden,activity:visible")}}}

    :AnalyticsData
    {:fields
     {:pageViews {:type :Int}
      :sessionDuration {:type :Int}
      :bounceRate {:type :Float}}}

    :Product
    {:fields
     {:id {:type :ID}
      :name {:type :String}
      :price {:type :Float}}}}

   :queries
   {:user {:type :User
           :args {:id {:type :ID}}
           :resolve (fn [_ {:keys [id]} _]
                      (println "  👤 Loading user profile...")
                      {:id id
                       :name "Alice Johnson"
                       :email "alice@example.com"
                       :profile {:bio "Software Engineer at TechCorp"
                                 :location "San Francisco, CA"}})}}})

(def compiled-demo-schema (schema/compile demo-schema))

;; HTTP Server for demo
(defn create-demo-handler [schema]
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

                start-time (System/currentTimeMillis)
                result (lacinia/execute schema query variables nil)
                end-time (System/currentTimeMillis)
                execution-time (- end-time start-time)]

            (println (str "\n🕒 Query executed in " execution-time "ms"))

            (if (contains? result :deferred)
              ;; Streaming response for deferred fields
              (let [initial-response {:data (:data result)
                                      :hasNext true
                                      :extensions {:executionTime execution-time}}
                    deferred-responses (map (fn [deferred-item]
                                              {:incremental [{:data (:data deferred-item)
                                                              :path (:path deferred-item)
                                                              :label (:label deferred-item)}]
                                               :hasNext false})
                                            (:deferred result))

                    full-response (str (json/write-str initial-response)
                                       "\n---DEFER-STREAM---\n"
                                       (str/join "\n---DEFER-STREAM---\n"
                                                 (map json/write-str deferred-responses)))]

                (.add headers "Content-Type" "application/json; charset=utf-8")
                (.add headers "X-GraphQL-Defer" "enabled")
                (.add headers "X-Execution-Time" (str execution-time))
                (.sendResponseHeaders exchange 200 0)

                (with-open [os (.getResponseBody exchange)]
                  (.write os (.getBytes full-response StandardCharsets/UTF_8))))

              ;; Regular response
              (let [response-body (json/write-str (assoc result
                                                         :extensions {:executionTime execution-time}))]
                (.add headers "Content-Type" "application/json; charset=utf-8")
                (.add headers "X-Execution-Time" (str execution-time))
                (.sendResponseHeaders exchange 200 (count (.getBytes response-body StandardCharsets/UTF_8)))

                (with-open [os (.getResponseBody exchange)]
                  (.write os (.getBytes response-body StandardCharsets/UTF_8))))))

          :else
          (.sendResponseHeaders exchange 405 0))))))

(defn start-demo-server [port]
  (println (str "🚀 Starting @defer demo server on port " port))
  (doto (HttpServer/create (InetSocketAddress. port) 0)
    (.createContext "/graphql" (create-demo-handler compiled-demo-schema))
    (.setExecutor (Executors/newFixedThreadPool 4))
    (.start)))

(defn stop-demo-server [server]
  (println "🛑 Stopping demo server")
  (.stop server 0))

(defn make-demo-request [url body description]
  (println (str "\n📡 " description))
  (println "─────────────────────────────────────────")
  (let [client (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI. url))
                    (.header "Content-Type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString body))
                    (.build))
        start-time (System/currentTimeMillis)
        response (.send client request (HttpResponse$BodyHandlers/ofString))
        end-time (System/currentTimeMillis)
        total-time (- end-time start-time)]

    (println (str "📊 Total HTTP request time: " total-time "ms"))
    (println (str "📈 Status: " (.statusCode response)))

    ;; Parse and display response
    (let [body (.body response)]
      (if (str/includes? body "DEFER-STREAM")
        (let [parts (str/split body #"\n---DEFER-STREAM---\n")]
          (println "\n🎯 Initial Response:")
          (println (json/write-str (json/read-str (first parts) :key-fn keyword) :indent true))
          (println "\n⏳ Deferred Responses:")
          (doseq [part (rest parts)]
            (println (json/write-str (json/read-str part :key-fn keyword) :indent true))))
        (do
          (println "\n💾 Response:")
          (println (json/write-str (json/read-str body :key-fn keyword) :indent true)))))

    (println "\n")))

(defn demo-defer-functionality []
  (println "🌟 GraphQL @defer Directive HTTP Integration Demo")
  (println "═══════════════════════════════════════════════")

  (let [server (start-demo-server 8767)]
    (try
      (Thread/sleep 500) ; Give server time to start

      ;; Demo 1: Query without @defer
      (make-demo-request
       "http://localhost:8767/graphql"
       (json/write-str
        {:query "query UserDashboard($userId: ID!) {
                     user(id: $userId) {
                       id
                       name
                       email
                       profile {
                         bio
                         location
                         preferences
                         privacySettings
                       }
                       analytics {
                         pageViews
                         sessionDuration
                         bounceRate
                       }
                       recommendations {
                         id
                         name
                         price
                       }
                       socialConnections {
                         id
                         name
                         email
                       }
                     }
                   }"
         :variables {:userId "demo-user"}})
       "Demo 1: Traditional Query (No @defer)")

      (Thread/sleep 1000)

      ;; Demo 2: Query with @defer - basic usage
      (make-demo-request
       "http://localhost:8767/graphql"
       (json/write-str
        {:query "query UserDashboardDeferred($userId: ID!) {
                     user(id: $userId) {
                       id
                       name
                       email
                       profile {
                         bio
                         location
                         preferences @defer(label: \"userPrefs\")
                         privacySettings @defer(label: \"privacy\")
                       }
                       analytics @defer(label: \"analytics\") {
                         pageViews
                         sessionDuration
                         bounceRate
                       }
                       recommendations @defer(label: \"recommendations\") {
                         id
                         name
                         price
                       }
                       socialConnections @defer(label: \"socialConnections\") {
                         id
                         name
                         email
                       }
                     }
                   }"
         :variables {:userId "demo-user"}})
       "Demo 2: Optimized Query with @defer directives")

      (Thread/sleep 1000)

      ;; Demo 3: Mixed defer strategy
      (make-demo-request
       "http://localhost:8767/graphql"
       (json/write-str
        {:query "query SmartDashboard($userId: ID!) {
                     user(id: $userId) {
                       id
                       name
                       email
                       profile {
                         bio
                         location
                         preferences @defer(label: \"preferences\")
                       }
                       recommendations @defer(label: \"aiRecommendations\") {
                         id
                         name
                         price
                       }
                     }
                   }"
         :variables {:userId "demo-user"}})
       "Demo 3: Strategic @defer usage (mixed critical/non-critical data)")

      (finally
        (stop-demo-server server)))

    (println "✅ Demo completed! Key observations:")
    (println "   • @defer allows fast loading of critical data")
    (println "   • Non-critical data loads asynchronously")
    (println "   • Total response time may be similar, but user experience improves")
    (println "   • Labels help track which deferred fields are returning")
    (println "   • Streaming responses enable progressive UI updates")))

(defn -main []
  (demo-defer-functionality))

(comment
  ;; Interactive demo - run this in REPL
  (demo-defer-functionality)

  ;; Test individual queries with curl:

  ;; Start server
  (def demo-server (start-demo-server 8767))

  ;; Test query without defer
  ;; curl -X POST http://localhost:8767/graphql \
  ;;   -H "Content-Type: application/json" \
  ;;   -d '{
  ;;     "query": "query { user(id: \"test\") { id name profile { bio preferences } analytics { pageViews } } }",
  ;;     "variables": {}
  ;;   }'

  ;; Test query with defer
  ;; curl -X POST http://localhost:8767/graphql \
  ;;   -H "Content-Type: application/json" \
  ;;   -d '{
  ;;     "query": "query { user(id: \"test\") { id name profile { bio preferences @defer(label: \"prefs\") } analytics @defer(label: \"analytics\") { pageViews } } }",
  ;;     "variables": {}
  ;;   }'

  ;; Stop server
  (stop-demo-server demo-server))