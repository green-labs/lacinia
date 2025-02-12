; Copyright (c) 2021-present Walmart, Inc.
;
; Licensed under the Apache License, Version 2.0 (the "License")
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;     http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.

;; clj -T:build <var>

(ns build
  (:require [clojure.string :as string]
            [clojure.tools.build.api :as build]
            [deps-deploy.deps-deploy :as dd]
            #_[net.lewisship.build :as b]))

;; (def lib 'org.clojars.greenlabs/lacinia)
;; (def version (-> "VERSION.txt" slurp string/trim))
;; (def class-dir "target/classes")

;; (def jar-params {:project-name lib
;;                  :version version
;;                  :class-dir class-dir})

;; (defn clean
;;   [_params]
;;   (build/delete {:path "target"}))

;; (defn compile-java [_]
;;   (build/javac {:src-dirs ["java"]
;;                 :class-dir class-dir
;;                 :basis (build/create-basis)
;;                 :javac-opts ["--release" "11"]}))

;; (defn jar
;;   [_params]
;;   (compile-java nil)
;;   (b/create-jar jar-params))

;; (defn deploy
;;   [_params]
;;   (clean nil)
;;   (b/deploy-jar (jar nil)))

;; (defn codox
;;   [_params]
;;   (b/generate-codox {:project-name lib
;;                      :version version
;;                      :aliases [:dev]}))

;; (def publish-dir "../apidocs/lacinia")

;; (defn publish
;;   "Generate Codox documentation and publish via a GitHub push."
;;   [_params]
;;   (println "Generating Codox documentation")
;;   (codox nil)
;;   (println "Copying documentation to" publish-dir "...")
;;   (build/copy-dir {:target-dir publish-dir
;;                    :src-dirs ["target/doc"]})
;;   (println "Committing changes ...")
;;   (build/process {:dir publish-dir
;;                   :command-args ["git" "commit" "-a" "-m" (str "lacinia " version)]})
;;   (println "Pushing changes ...")
;;   (build/process {:dir publish-dir
;;                   :command-args ["git" "push"]}))

(def lib 'org.clojars.greenlabs/lacinia)
(def version (format "1.0.%s" (build/git-count-revs nil)))
(def class-dir "target/classes")

(def ^:private basis
  (delay (build/create-basis {})))

(defn- pom-template
  [version]
  [[:description "A fork of lacinia developed and maintained by Greenlabs "]
   [:url "https://github.com/green-labs/lacinia"]
   [:licenses
    [:license
     [:name "Apache License, Version 2.0"]
     [:url "http://www.apache.org/licenses/LICENSE-2.0"]]]
   [:scm
    [:url "https://github.com/green-labs/lacinia"]
    [:connection "scm:git:https://github.com/green-labs/lacinia.git"]
    [:developerConnection "scm:git:ssh:git@github.com:green-labs/lacinia.git"]
    [:tag (str "v" version)]]])

(defn- jar-opts
  [opts]
  (println "Version:" version)
  (assoc opts
         :lib lib   :version version
         :jar-file  (format "target/%s-%s.jar" lib version)
         :basis     @basis
         :class-dir class-dir
         :target    "target"
         :src-dirs  ["src"]
         :pom-data  (pom-template version)))

(defn clean
  [_]
  (println "Removing target/ ...")
  (build/delete {:path "target"}))

(defn build-jar
  [opts]
  (let [j-opts (jar-opts opts)]
    (println "Writing pom.xml ...")
    (build/write-pom j-opts)
    (println "Copying source ...")
    (build/copy-dir {:src-dirs ["src"] :target-dir class-dir})
    (println "Building" (:jar-file j-opts) "...")
    (build/jar j-opts)))

(defn deploy
  "Build the jar and deploy it to Clojars. Expects env vars CLOJARS_USERNAME &
  CLOJARS_PASSWORD."
  [opts]
  (clean opts)
  (build-jar opts)
  (let [{:keys [jar-file] :as opts} (jar-opts opts)]
    (dd/deploy {:installer :remote
                :artifact  (build/resolve-path jar-file)
                :pom-file  (build/pom-path (select-keys opts [:lib :class-dir]))}))
  opts)
