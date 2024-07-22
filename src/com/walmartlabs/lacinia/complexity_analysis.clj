(ns com.walmartlabs.lacinia.complexity-analysis
  (:require [com.walmartlabs.lacinia.selection :as selection]
            [com.walmartlabs.lacinia.internal-utils :refer [cond-let]]))

(declare ^:private summarize-selection)

(defn ^:private summarize-selections
  [selections fragment-map]
  (mapcat #(summarize-selection % fragment-map) selections))

(defn ^:private summarize-field
  "- leaf field -> nil
   - pageInfo -> nil
   - edges -> 
   - else -> "
  [{:keys [arguments selections field-name leaf?]} fragment-map]
  (cond-let
   leaf? nil
   (= :pageInfo field-name) nil
   (= :edges field-name) (summarize-field (first selections) fragment-map)
   :let [n-nodes (-> arguments
                     (select-keys [:first :last :take :limit])
                     vals
                     first)
         n-nodes' (or n-nodes 1)]

   :else (-> {:field-name field-name}
             (assoc :selections (summarize-selections selections fragment-map))
             (assoc :list-args? (some? n-nodes))
             (assoc :n-nodes n-nodes')
             vector)))

(defn ^:private summarize-selection
  "- field -> summarize-field 에서 처리
   - inline fragment -> selections를 재귀 호출
   - named fragment -> fragment-name으로 fragment-map 조회 후 해당 fragment의 selections를 재귀 호출 "
  [{:keys [fragment-name selections]
    :as   selection} fragment-map]
  (case (selection/selection-kind selection)
    :field (summarize-field selection fragment-map)
    :named-fragment (let [{fragment-selections :selections} (fragment-name fragment-map)]
                      (summarize-selections fragment-selections fragment-map))
    :inline-fragment (summarize-selections selections fragment-map)))

(defn ^:private calculate-complexity
  [{:keys [selections list-args? n-nodes]}]
  (let [children-complexity (apply + (map calculate-complexity selections))]
    (if list-args?
      (* n-nodes children-complexity)
      (+ n-nodes children-complexity))))

(defn complexity-analysis
  [query {:keys [max-complexity] :as _options}]
  (let [{:keys [fragments selections]} query
        summarized-selections (summarize-selections selections fragments)
        complexity (calculate-complexity (first summarized-selections))] 
    (when (> complexity max-complexity)
      {:message (format "Over max complexity! Current number of resources to be queried: %s" complexity)})))
