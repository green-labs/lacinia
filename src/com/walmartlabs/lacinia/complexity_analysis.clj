(ns com.walmartlabs.lacinia.complexity-analysis
  (:require [com.walmartlabs.lacinia.selection :as selection]))

(defn^:private summarize-query
  "- leaf field -> nil
   - field & selection -> arguments 있으면 추가로 파싱해서 세팅 후 selections 재귀 호출
   - inline fragment -> selections를 재귀 호출
   - named fragment -> fragment-name으로 fragment-map 조회 후 해당 fragment의 selections를 재귀 호출"
  [{:keys [arguments fragment-name selections field-name leaf?]
    :as   selection} fragment-map]
  (cond
   leaf? nil
   (= :named-fragment (selection/selection-kind selection)) (let [{fragment-selections :selections} (fragment-name fragment-map)]
                                                              (mapcat #(summarize-query % fragment-map) fragment-selections))
   (= :inline-fragment (selection/selection-kind selection)) (mapcat #(summarize-query % fragment-map) selections)
   :else (cond-> {:field-name field-name}
           selections (assoc :selections (mapcat #(summarize-query % fragment-map) selections))
           arguments (assoc :arguments arguments)
           true vector)))

(defn^:private calculate-complexity
  "- pageInfo -> 0 반환
   - leaf-node -> n-nodes(pagination arg를 통해 조회될 resource 갯수) 반환
   - edges -> selections에 대해 재귀호출 후 합연산
   - connection o -> connection은 resource가 아니므로 연산에서 제외되어야 합니다.
                  -> selections에 대해 재귀호출 후 합연산 후 n-nodes 만큼 곱해줍니다.
   - connection x -> selections에 대해 재귀호출 후 합연산 후 n-nodes 만큼 곱해준 결과에 n-nodes를 더해줍니다"
  [{:keys [field-name selections arguments]}]
  (let [{:keys [first last limit]} arguments
        n-nodes                    (or first last limit 1)
        leaf-node                  (seq selections)
        connection?                (->> selections
                                        (remove (fn [{:keys [field-name]}] (#{:edges :pageInfo} field-name)))
                                        empty?)]
    (cond
      (= field-name :pageInfo) 0
      (nil? leaf-node) n-nodes
      (= field-name :edges) (->> selections
                                 (map #(calculate-complexity %))
                                 (reduce +))
      connection? (->> selections
                       (map #(calculate-complexity %))
                       (reduce +)
                       (* n-nodes))
      (false? connection?) (->> selections
                                (map #(calculate-complexity %))
                                (reduce +)
                                (* n-nodes)
                                (+ n-nodes)))))

(defn complexity-analysis
  [query {:keys [max-complexity] :as _options}]
  (let [{:keys [fragments selections]} query
        pq (mapcat #(summarize-query % fragments) selections)
        complexity (calculate-complexity pq)]
    (when (> complexity max-complexity)
      {:message (format "Over max complexity! Current number of resources to be queried: %s" complexity)})))
