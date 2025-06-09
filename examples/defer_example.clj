(ns defer-example
  "Example demonstrating the @defer directive in Lacinia GraphQL"
  (:require
   [com.walmartlabs.lacinia.schema :as schema]
   [com.walmartlabs.lacinia :as lacinia]
   [clojure.pprint :as pprint]))

(def schema
  "A simple schema demonstrating @defer directive usage"
  {:objects
   {:User
    {:fields
     {:id {:type :ID}
      :name {:type :String}
      :email {:type :String
              :resolve (fn [_ _ _]
                         ;; Simulate a slow email lookup
                         (Thread/sleep 500)
                         "user@example.com")}
      :profile {:type :UserProfile}
      :posts {:type '(list :Post)
              :resolve (fn [_ _ _]
                         ;; Simulate a slow database query
                         (Thread/sleep 1000)
                         [{:id "1" :title "First Post" :content "Hello World!"}
                          {:id "2" :title "Second Post" :content "GraphQL is awesome!"}])}}}

    :UserProfile
    {:fields
     {:bio {:type :String}
      :avatar {:type :String}
      :preferences {:type :String
                    :resolve (fn [_ _ _]
                               ;; Simulate a slow preferences lookup
                               (Thread/sleep 300)
                               "dark-theme,notifications-on")}}}

    :Post
    {:fields
     {:id {:type :ID}
      :title {:type :String}
      :content {:type :String}}}}

   :queries
   {:user {:type :User
           :args {:id {:type :ID}}
           :resolve (fn [_ {:keys [id]} _]
                      {:id id
                       :name "John Doe"
                       :profile {:bio "Software Developer"
                                 :avatar "avatar.jpg"}})}}})

(def compiled-schema (schema/compile schema))

(defn demo-defer []
  (println "=== GraphQL @defer Directive Demo ===\n")

  (println "Query with @defer directives:")
  (println "query {")
  (println "  user(id: \"123\") {")
  (println "    id")
  (println "    name")
  (println "    email @defer(label: \"userEmail\")")
  (println "    profile {")
  (println "      bio")
  (println "      avatar")
  (println "      preferences @defer(label: \"userPrefs\")")
  (println "    }")
  (println "    posts @defer(label: \"userPosts\") {")
  (println "      id")
  (println "      title")
  (println "      content")
  (println "    }")
  (println "  }")
  (println "}\n")

  (let [query "query {
                 user(id: \"123\") {
                   id
                   name
                   email @defer(label: \"userEmail\")
                   profile {
                     bio
                     avatar
                     preferences @defer(label: \"userPrefs\")
                   }
                   posts @defer(label: \"userPosts\") {
                     id
                     title
                     content
                   }
                 }
               }"
        start-time (System/currentTimeMillis)
        result (lacinia/execute compiled-schema query {} nil)
        end-time (System/currentTimeMillis)]

    (println "=== RESULT ===")
    (pprint/pprint result)
    (println (str "\nExecution time: " (- end-time start-time) "ms"))

    (println "\n=== EXPLANATION ===")
    (println "• The main response contains immediate data (id, name, bio, avatar)")
    (println "• Deferred fields are marked with :com.walmartlabs.lacinia.executor/deferred")
    (println "• The :deferred section contains the resolved values for slow fields")
    (println "• Each deferred result has a :path, :data, and :label")
    (println "• In a real streaming implementation, deferred results would be sent separately")))

(defn -main []
  (demo-defer))

(comment
  ;; Run the demo
  (demo-defer)

  ;; Test without defer - notice the longer execution time
  (time
   (lacinia/execute compiled-schema
                    "query { user(id: \"123\") { id name email profile { bio avatar preferences } posts { id title content } } }"
                    {} nil)))