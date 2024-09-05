(ns com.walmartlabs.lacinia.query-analyzer
  (:require [com.walmartlabs.lacinia.selection :as selection]))

(defn ^:private list-args? [arguments]
  (some? (or (:first arguments)
             (:last arguments))))

(defn ^:private summarize-selection
  "Recursively summarizes the selection, handling field, inline fragment, and named fragment."
  [{:keys [arguments selections field-name leaf? fragment-name] :as selection} fragment-map]
  (let [selection-kind (selection/selection-kind selection)]
    (cond
      ;; If it's a leaf or `pageInfo`, return nil.
      (or leaf? (= :pageInfo field-name))
      nil

      ;; If it's a named fragment, look it up in the fragment-map and process its selections.
      (= :named-fragment selection-kind)
      (let [sub-selections (:selections (fragment-map fragment-name))]
        (mapcat #(summarize-selection % fragment-map) sub-selections))

      ;; If it's an inline fragment or  `edges` field, process its selections.
      (or (= :inline-fragment selection-kind) (= field-name :edges))
      (mapcat #(summarize-selection % fragment-map) selections)

      ;; Otherwise, handle a regular field with potential nested selections.
      :else
      (let [n-nodes (or (-> arguments (select-keys [:first :last]) vals first) 1)]
        [{:field-name field-name
          :selections (mapcat #(summarize-selection % fragment-map) selections)
          :list-args? (list-args? arguments)
          :n-nodes n-nodes}]))))

(defn ^:private calculate-complexity
  [{:keys [selections list-args? n-nodes]}]
  (let [children-complexity (apply + (map calculate-complexity selections))]
    (if list-args?
      (* n-nodes children-complexity)
      (+ n-nodes children-complexity))))

(defn complexity-analysis
  [query]
  (let [{:keys [fragments selections]} query
        summarized-selections (mapcat #(summarize-selection % fragments) selections)
        complexity (apply + (map calculate-complexity summarized-selections))]
    {:complexity complexity}))

(defn enable-query-analyzer
  [context]
  (assoc context ::enable? true))
