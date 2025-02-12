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
  (:require [clojure.tools.build.api :as build]
            [deps-deploy.deps-deploy :as dd]
            [net.lewisship.build :as b]))

(def lib 'org.clojars.greenlabs/lacinia)
(def version (format "1.0.%s" (build/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (build/create-basis {:project "deps.edn"}))

(defn clean [_]
  (println "Cleaning target directory...")
  (build/delete {:path "target"}))

(defn compile-java [_]
  (println "Compiling Java sources...")
  (build/javac {:src-dirs ["java"]
                :class-dir class-dir
                :basis basis
                :javac-opts ["--release" "11"]}))

(defn jar [_]
  (println "Building jar...")
  (build/write-pom {:class-dir class-dir
                    :lib lib
                    :version version
                    :basis basis
                    :src-dirs ["src"]
                    :scm {:url "https://github.com/green-labs/lacinia"
                          :connection "scm:git:https://github.com/green-labs/lacinia.git"
                          :tag (str "v" version)}
                    :pom-data [[:licenses
                                [:license
                                 [:name "Apache License, Version 2.0"]
                                 [:url "http://www.apache.org/licenses/LICENSE-2.0"]
                                 [:distribution "repo"]]]]
                    :description "A fork of lacinia developed and maintained by Greenlabs"})
  (build/jar {:class-dir class-dir
              :jar-file (format "target/%s-%s.jar" lib version)}))

(defn deploy [_]
  (println "Starting deployment process...")
  (clean nil)
  ;; prep-lib이 자동으로 compile-java를 실행합니다
  (jar nil)
  (println "Deploying to Clojars...")
  (dd/deploy {:installer :remote
              :artifact (format "target/%s-%s.jar" lib version)
              :pom-file (str class-dir "/META-INF/maven/" lib "/pom.xml")}))

(defn codox
  [_params]
  (b/generate-codox {:project-name lib
                     :version version
                     :aliases [:dev]}))

(def publish-dir "../apidocs/lacinia")

(defn publish
  "Generate Codox documentation and publish via a GitHub push."
  [_params]
  (println "Generating Codox documentation")
  (codox nil)
  (println "Copying documentation to" publish-dir "...")
  (build/copy-dir {:target-dir publish-dir
                   :src-dirs ["target/doc"]})
  (println "Committing changes ...")
  (build/process {:dir publish-dir
                  :command-args ["git" "commit" "-a" "-m" (str "lacinia " version)]})
  (println "Pushing changes ...")
  (build/process {:dir publish-dir
                  :command-args ["git" "push"]}))
