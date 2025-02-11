(ns com.walmartlabs.lacinia.query-analyzer
  (:require [com.walmartlabs.lacinia.selection :as selection]))

(defn ^:private list-args? [arguments]
  (some? (or (:first arguments)
             (:last arguments))))

(declare summarize-selection)

(defn ^:private summarize-sub-selections
  "여러 하위 선택들을 요약합니다."
  [fragment-map sub-selections]
  (mapcat #(summarize-selection % fragment-map) sub-selections))

(defn ^:private summarize-selection
  "Recursively summarizes the selection, handling field, inline fragment, and named fragment."
  [{:keys [arguments selections field-name leaf? fragment-name depth] :as selection :or {depth 0}} fragment-map]
  (let [selection-kind (selection/selection-kind selection)]
    (cond
      ;; If it's a leaf or `pageInfo`, return nil.
      (or leaf? (= :pageInfo field-name))
      nil

      ;; If it's a named fragment, look it up in the fragment-map and process its selections.
      (= :named-fragment selection-kind)
      (let [sub-selections (:selections (fragment-map fragment-name))]
        (summarize-sub-selections fragment-map (map #(assoc % :depth depth) sub-selections)))

      ;; If it's an inline fragment or  `edges` field, process its selections.
      (or (= :inline-fragment selection-kind) (= field-name :edges))
      (summarize-sub-selections fragment-map (map #(assoc % :depth depth) selections))

      ;; Otherwise, handle a regular field with potential nested selections.
      :else
      (let [depth' (inc depth)
            n-nodes (or (-> arguments (select-keys [:first :last]) vals first) 1)]
        [{:field-name field-name
          :selections (summarize-sub-selections fragment-map (map #(assoc % :depth depth') selections))
          :list-args? (list-args? arguments)
          :depth depth'
          :n-nodes n-nodes}]))))

(defn ^:private calculate-complexity
  [{:keys [selections list-args? n-nodes]}]
  (let [children-complexity (apply + (map calculate-complexity selections))]
    (if list-args?
      (* n-nodes children-complexity)
      (+ n-nodes children-complexity))))

(defn ^:private get-max-depth
  "선택 요약 정보에서 최대 depth를 계산합니다."
  [{:keys [selections depth] :or {depth 0}}]
  (if (empty? selections)
    depth
    (max depth (apply max (map get-max-depth selections)))))

(defn complexity-analysis
  [query]
  (let [{:keys [fragments selections]} query
        summarized-selections (mapcat #(summarize-selection % fragments) selections)
        complexity (apply + (map calculate-complexity summarized-selections))
        max-depth (get-max-depth summarized-selections)]
    {:complexity complexity
     :max-depth max-depth}))

(defn enable-query-analyzer
  [context]
  (assoc context ::enable? true))
