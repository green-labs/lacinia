; Copyright (c) 2017-present Walmart, Inc.
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

(ns com.walmartlabs.lacinia
  (:require [com.walmartlabs.lacinia.parser :as parser]
            [com.walmartlabs.lacinia.constants :as constants]
            [com.walmartlabs.lacinia.executor :as executor]
            [com.walmartlabs.lacinia.validator :as validator]
            [com.walmartlabs.lacinia.internal-utils :refer [cond-let]]
            [com.walmartlabs.lacinia.util :refer [as-error-map]]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [com.walmartlabs.lacinia.tracing :as tracing]
            [com.walmartlabs.lacinia.query-analyzer :as query-analyzer])
  (:import (clojure.lang ExceptionInfo)))

(defn ^:private as-errors
  [exception]
  {:errors [(as-error-map exception)]})

(defn ^:private contains-defer?
  "Recursively checks if a parsed query contains any @defer directives."
  [parsed-query]
  (letfn [(has-defer-in-selections? [selections]
            (some (fn [selection]
                    (or (:deferred? selection)
                        (and (:selections selection)
                             (has-defer-in-selections? (:selections selection)))))
                  selections))]
    (let [selections (get parsed-query :selections [])]
      (has-defer-in-selections? selections))))

(defn ^:private strip-defer-flags
  "Recursively removes :deferred? and :defer-label flags from a parsed query."
  [parsed-query]
  (letfn [(strip-from-selections [selections]
            (map (fn [selection]
                   (cond-> (dissoc selection :deferred? :defer-label)
                     (:selections selection)
                     (update :selections strip-from-selections)))
                 selections))]
    (update parsed-query :selections strip-from-selections)))

(defn execute-parsed-query-async
  "Prepares a query, by applying query variables to it, resulting in a prepared
  query which is then executed.

  Returns a [[ResolverResult]] that will deliver the result map, or an exception."
  {:added "0.16.0"}
  [parsed-query variables context]
  {:pre [(map? parsed-query)
         (or (nil? context)
             (map? context))]}
  (cond-let
   :let [{:keys [::tracing/timing-start]} parsed-query
            ;; Validation phase encompasses preparing with query variables and actual validation.
            ;; It's somewhat all mixed together.
         start-offset (tracing/offset-from-start timing-start)
         start-nanos (System/nanoTime)
         [prepared error-result] (try
                                   [(parser/prepare-with-query-variables parsed-query variables)]
                                   (catch Exception e
                                     [nil (as-errors e)]))]

   (some? error-result)
   (resolve/resolve-as error-result)

   :let [validation-errors (validator/validate prepared)]

   (seq validation-errors)
   (resolve/resolve-as {:errors validation-errors})

   :else (executor/execute-query (assoc context constants/parsed-query-key prepared
                                        ::tracing/validation {:start-offset start-offset
                                                              :duration (tracing/duration start-nanos)}))))

(defn execute-parsed-query
  "Prepares a query, by applying query variables to it, resulting in a prepared
  query which is then executed.

  Returns a result map (with :data and/or :errors keys), or an exception if
  execution failed.

  Options as per [[execute]]."
  ([parsed-query variables context]
   (execute-parsed-query parsed-query variables context nil))
  ([parsed-query variables context options]
   (let [*result (promise)
         {:keys [timeout-ms timeout-error prepared-override]
          :or {timeout-ms 0
               timeout-error {:message "Query execution timed out."}}} options
         context' (cond-> context
                    (:analyze-query options) query-analyzer/enable-query-analyzer)
         ;; Use prepared-override if provided, otherwise prepare normally
         execution-result (if prepared-override
                            ;; When using prepared-override, still need to validate
                            (let [validation-errors (validator/validate prepared-override)]
                              (if (seq validation-errors)
                                (resolve/resolve-as {:errors validation-errors})
                                (executor/execute-query (assoc context' constants/parsed-query-key prepared-override
                                                               ::tracing/validation {:start-offset 0 :duration 0}))))
                            (execute-parsed-query-async parsed-query variables context'))
         result (do
                  (resolve/on-deliver! execution-result *result)
                  ;; Block on that deliver, then return the final result.
                  (if (pos? timeout-ms)
                    (deref *result
                           timeout-ms
                           {:errors [timeout-error]})
                    @*result))]
     (when (instance? Throwable result)
       (throw result))

     result)))

(defn execute
  "Given a compiled schema and a query string, attempts to execute it.

  Returns a result map with up-to two keys:  :data is the main result of the
  execution, and :errors are any errors generated during execution.

  In the case where there's a parse or validation problem for the query,
  just the :errors key will be present.

  When a query contains @defer directives, the behavior depends on the :stream-defer option:
  - :stream-defer true: Returns {:initial-result ResolverResult, :deferred-stream Channel}
  - :stream-defer false: Ignores @defer directives and executes synchronously
  - :stream-defer :auto (default): Auto-detects @defer and streams if present, otherwise synchronous

  schema
  : GraphQL schema (as compiled by [[com.walmartlabs.lacinia.schema/compile]]).

  query
  : Input query string to be parsed and executed.

  variables
  : compile-time variables that can be referenced inside the query using the
    `$variable-name` production.

  context (optional)
  : Additional data that will ultimately be passed to resolver functions.

  options (optional)
  : Additional options to control execution.

  Options:

  :operation-name
  : Identifies which operation to execute, when the query specifies more than one.

  :timeout-ms
  : Timeout for the operation.  Defaults to 0, for no timeout at all.

  :timeout-error
  : Error map used if a timeout occurs.
  : Default is `{:message \"Query execution timed out.\"}`.

  :stream-defer
  : Controls @defer directive handling. Values:
    - :auto (default): Auto-detect @defer and stream if present
    - true: Force streaming mode (returns {:initial-result, :deferred-stream})
    - false: Ignore @defer directives, execute synchronously

  This function parses the query and invokes [[execute-parsed-query]].

  When a GraphQL query contains variables, the values for those variables
  arrive seperately; for example, a JSON request may have the query
  in the \"query\" property, and the variables in the \"variables\" property.

  The values for those variables are provided in the variables parameter.
  The keys are keyword-ized names of the variable (without the leading
  '$'); if a variable is named `$user_id` in the query, the corresponding
  key should be `:user_id`.

  The values in the variables map should be of a type matching the
  variable's declaration in the query; typically a string or other scalar value,
  or a map for a variable of type InputObject."
  ([schema query variables context]
   (execute schema query variables context {}))
  ([schema query variables context options]
   {:pre [(string? query)]}
   (let [{:keys [operation-name stream-defer]
          :or {stream-defer :auto}} options
         [parsed error-result] (try
                                 [(parser/parse-query schema query operation-name)]
                                 (catch ExceptionInfo e
                                   [nil (as-errors e)]))]
     (if (some? error-result)
       error-result
       (let [;; Need to prepare the query to check for defer since @defer is processed during preparation
             [prepared prepare-error] (try
                                        [(parser/prepare-with-query-variables parsed variables)]
                                        (catch Exception e
                                          [nil (as-errors e)]))
             should-stream? (if prepare-error
                              false ; If preparation failed, can't stream
                              (case stream-defer
                                true true
                                false false
                                :auto (contains-defer? prepared)
                                (contains-defer? prepared)))]
         (if prepare-error
           prepare-error
           (if should-stream?
             ;; Use defer streaming execution
             (let [context' (assoc context constants/schema-key schema
                                   constants/parsed-query-key parsed)
                   validation-errors (validator/validate prepared)]
               (if (seq validation-errors)
                 {:initial-result (resolve/resolve-as {:errors validation-errors})
                  :deferred-stream nil}
                 (executor/execute-query-with-defer (assoc context' constants/parsed-query-key prepared))))
             ;; Use traditional synchronous execution - strip defer flags to ignore @defer
             (let [prepared-for-sync (if (= stream-defer false)
                                       (strip-defer-flags prepared)
                                       prepared)]
               (execute-parsed-query parsed variables context (assoc options :prepared-override prepared-for-sync))))))))))

(defn execute-with-defer
  "Execute a GraphQL query with true @defer streaming support.

  DEPRECATED: Use `execute` with `:stream-defer true` option instead.
  This function is maintained for backward compatibility and will be removed in a future version.

  This function supports the @defer directive by returning results in two parts:
  1. An initial response with deferred fields as placeholders
  2. A stream of subsequent responses containing the deferred field values

  Arguments:
  - compiled-schema: A compiled schema (from schema/compile)
  - query-string: The GraphQL query string  
  - variables: A map of variable values (optional)
  - context: Additional context map (optional)

  Returns a map with:
  - :initial-result - A ResolverResult that delivers the initial response
  - :deferred-stream - A core.async channel that streams deferred results

  Example usage:
    (let [{:keys [initial-result deferred-stream]} 
          (execute-with-defer schema query variables context)
          initial @initial-result]
      (println \"Initial:\" initial)
      (async/go-loop []
        (when-let [deferred (async/<! deferred-stream)]
          (println \"Deferred:\" deferred)
          (recur))))

  The initial response will include :hasNext true if there are deferred fields.
  Each deferred result follows the GraphQL spec format with :incremental and :hasNext."
  {:deprecated "Use execute with :stream-defer true option instead"}
  ([compiled-schema query-string]
   (execute compiled-schema query-string nil nil {:stream-defer true}))
  ([compiled-schema query-string variables-map]
   (execute compiled-schema query-string variables-map nil {:stream-defer true}))
  ([compiled-schema query-string variables-map context]
   (execute compiled-schema query-string variables-map context {:stream-defer true})))
