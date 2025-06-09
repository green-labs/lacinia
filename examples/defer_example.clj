(ns defer-example
  "Example demonstrating GraphQL @defer directive streaming functionality in Lacinia."
  (:require [clojure.core.async :as async :refer [<!!]]
            [com.walmartlabs.lacinia :as lacinia]
            [com.walmartlabs.lacinia.schema :as lacinia.schema]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [clojure.pprint :as pprint]))

;; Schema representing a social media platform
(def social-schema
  '{:objects
    {:User {:fields {:id {:type (non-null String)}
                     :username {:type String
                                :resolve :resolve-username}
                     :email {:type String
                             :resolve :resolve-email}
                     :profile {:type :UserProfile
                               :resolve :resolve-profile}
                     :posts {:type (list :Post)
                             :resolve :resolve-user-posts}
                     :followers {:type (list :User)
                                 :resolve :resolve-followers}}}

     :UserProfile {:fields {:bio {:type String
                                  :resolve :resolve-bio}
                            :avatar {:type String
                                     :resolve :resolve-avatar}
                            :location {:type String
                                       :resolve :resolve-location}
                            :website {:type String
                                      :resolve :resolve-website}}}

     :Post {:fields {:id {:type (non-null String)}
                     :title {:type String
                             :resolve :resolve-post-title}
                     :content {:type String
                               :resolve :resolve-post-content}
                     :likes {:type Int
                             :resolve :resolve-post-likes}
                     :comments {:type (list :Comment)
                                :resolve :resolve-post-comments}}}

     :Comment {:fields {:id {:type (non-null String)}
                        :text {:type String
                               :resolve :resolve-comment-text}
                        :author {:type :User
                                 :resolve :resolve-comment-author}}}}

    :queries
    {:user {:type :User
            :args {:id {:type (non-null String)}}
            :resolve :resolve-user}

     :users {:type (list :User)
             :resolve :resolve-users}}})

;; Slow resolvers to simulate real-world latency
(defn slow-resolver
  "Creates a resolver with configurable delay."
  [delay-ms value]
  (fn [context args parent]
    (resolve/resolve-promise
     (async/go
       (async/<! (async/timeout delay-ms))
       value))))

(def social-resolvers
  {:resolve-user (fn [context args _parent]
                   {:id (:id args)
                    :username "johndoe"
                    :email "john@example.com"})

   :resolve-username (slow-resolver 50 "johndoe")

   :resolve-email (slow-resolver 100 "john@example.com")

   :resolve-profile (slow-resolver 200 {:bio "Software Developer"
                                        :avatar "https://example.com/avatar.jpg"
                                        :location "San Francisco, CA"
                                        :website "https://johndoe.dev"})

   :resolve-user-posts (slow-resolver 300 [{:id "p1" :title "Hello World" :content "My first post!"}
                                           {:id "p2" :title "GraphQL is awesome" :content "Learning GraphQL..."}])

   :resolve-followers (slow-resolver 400 [{:id "u2" :username "janedoe" :email "jane@example.com"}
                                          {:id "u3" :username "bobsmith" :email "bob@example.com"}])

   :resolve-bio (slow-resolver 100 "Software Developer")

   :resolve-avatar (slow-resolver 75 "https://example.com/avatar.jpg")

   :resolve-location (slow-resolver 50 "San Francisco, CA")

   :resolve-website (slow-resolver 125 "https://johndoe.dev")

   :resolve-post-title (slow-resolver 25 "Hello World")

   :resolve-post-content (slow-resolver 50 "My first post!")

   :resolve-post-likes (slow-resolver 75 42)

   :resolve-post-comments (slow-resolver 150 [{:id "c1" :text "Great post!" :author {:id "u2"}}])

   :resolve-comment-text (slow-resolver 25 "Great post!")

   :resolve-comment-author (slow-resolver 50 {:id "u2" :username "janedoe"})

   :resolve-users (fn [context args _parent]
                    [{:id "u1" :username "johndoe"}
                     {:id "u2" :username "janedoe"}])})

(def compiled-schema (lacinia.schema/compile social-schema {:resolvers social-resolvers}))

(defn demo-basic-defer
  "Demonstrates basic @defer functionality."
  []
  (println "\n=== Basic @defer Demo ===")
  (let [query "{ 
                  user(id: \"u1\") { 
                    id 
                    username 
                    email @defer(label: \"userEmail\")
                  } 
                }"
        result-chan (lacinia/execute-query-with-defer compiled-schema query nil nil)]

    (println "Query:" query)
    (println "\nStreaming responses:")

    (loop [response-num 1]
      (when-let [response (<!! result-chan)]
        (println (str "\nResponse " response-num ":"))
        (pprint/pprint response)
        (recur (inc response-num))))))

(defn demo-multiple-defer
  "Demonstrates multiple @defer directives."
  []
  (println "\n=== Multiple @defer Demo ===")
  (let [query "{ 
                  user(id: \"u1\") { 
                    id 
                    username 
                    email @defer(label: \"email\")
                    profile @defer(label: \"profile\") {
                      bio
                      avatar
                      location
                    }
                    posts @defer(label: \"posts\") {
                      id
                      title
                      likes
                    }
                  } 
                }"
        result-chan (lacinia/execute-query-with-defer compiled-schema query nil nil)]

    (println "Query:" query)
    (println "\nStreaming responses:")

    (loop [response-num 1]
      (when-let [response (<!! result-chan)]
        (println (str "\nResponse " response-num ":"))
        (pprint/pprint response)
        (recur (inc response-num))))))

(defn demo-nested-defer
  "Demonstrates @defer on nested fields."
  []
  (println "\n=== Nested @defer Demo ===")
  (let [query "{ 
                  user(id: \"u1\") { 
                    id 
                    username 
                    profile {
                      bio
                      avatar
                      website @defer(label: \"website\")
                    }
                    posts {
                      id
                      title
                      comments @defer(label: \"comments\") {
                        id
                        text
                        author {
                          username
                        }
                      }
                    }
                  } 
                }"
        result-chan (lacinia/execute-query-with-defer compiled-schema query nil nil)]

    (println "Query:" query)
    (println "\nStreaming responses:")

    (loop [response-num 1]
      (when-let [response (<!! result-chan)]
        (println (str "\nResponse " response-num ":"))
        (pprint/pprint response)
        (recur (inc response-num))))))

(defn demo-performance-comparison
  "Compares performance between normal and deferred execution."
  []
  (println "\n=== Performance Comparison Demo ===")

  ;; Normal query (all fields together)
  (let [normal-query "{ 
                        user(id: \"u1\") { 
                          id 
                          username 
                          email 
                          profile {
                            bio
                            avatar
                            location
                            website
                          }
                          posts {
                            title
                            likes
                          }
                        } 
                      }"
        start-time (System/currentTimeMillis)
        result (lacinia/execute compiled-schema normal-query nil nil)
        normal-time (- (System/currentTimeMillis) start-time)]

    (println "Normal query time:" normal-time "ms")
    (println "Result keys:" (keys result)))

  ;; Deferred query
  (let [defer-query "{ 
                       user(id: \"u1\") { 
                         id 
                         username 
                         email @defer(label: \"email\")
                         profile @defer(label: \"profile\") {
                           bio
                           avatar
                           location
                           website
                         }
                         posts @defer(label: \"posts\") {
                           title
                           likes
                         }
                       } 
                     }"
        start-time (System/currentTimeMillis)
        result-chan (lacinia/execute-query-with-defer compiled-schema defer-query nil nil)
        first-response (<!! result-chan)
        first-response-time (- (System/currentTimeMillis) start-time)]

    (println "First response time with defer:" first-response-time "ms")
    (println "First response data:" (get-in first-response [:data :user]))

    ;; Collect remaining responses
    (let [remaining-responses (loop [responses []]
                                (if-let [response (<!! result-chan)]
                                  (recur (conj responses response))
                                  responses))
          total-time (- (System/currentTimeMillis) start-time)]

      (println "Total streaming time:" total-time "ms")
      (println "Total responses received:" (+ 1 (count remaining-responses))))))

(defn run-all-demos
  "Runs all defer demonstration examples."
  []
  (println "GraphQL @defer Directive Streaming Demo")
  (println "======================================")

  (demo-basic-defer)
  (demo-multiple-defer)
  (demo-nested-defer)
  (demo-performance-comparison)

  (println "\n=== Demo Complete ==="))

(comment
  ;; Run individual demos
  (demo-basic-defer)
  (demo-multiple-defer)
  (demo-nested-defer)
  (demo-performance-comparison)

  ;; Run all demos
  (run-all-demos))