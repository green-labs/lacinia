(ns com.walmartlabs.lacinia.query-analyzer
  (:require [com.walmartlabs.lacinia.selection :as selection]))

(defn ^:private list-args? [arguments]
  (some? (or (:first arguments)
             (:last arguments))))

(declare summarize-selection)

(defn ^:private summarize-partial-selections
  "여러 하위 selection들을 요약하는 함수입니다.
   
   파라미터:
   - fragment-map: 프래그먼트 정의들의 맵
   - partial-selections: 처리할 하위 selection들의 시퀀스
   
   동작:
   1. 각 하위 selection에 현재 깊이 정보를 추가
   2. 각 하위 selection을 summarize-selection을 통해 재귀적으로 처리
   3. 모든 하위 selection들의 요약 정보를 하나의 시퀀스로 결합

   반환값:
   - 모든 하위 selection들의 요약 정보가 포함된 시퀀스"
  [fragment-map partial-selections]
  (mapcat #(summarize-selection % fragment-map) partial-selections))

(defn ^:private summarize-selection
  "selection을 재귀적으로 요약하며, 필드, inline fragment, named fragment를 처리합니다.
   
   파라미터:
   - selection: 처리할 노드
   - fragment-map: fragment 정의들의 맵
   
   반환값:
   - 필드인 경우: 필드 이름, selection 정보를 포함하는 맵의 벡터
   - 프래그먼트인 경우: 프래그먼트 내부 selection들의 요약 정보
   - leaf나 pageInfo인 경우: nil"
  [{:keys [arguments selections field-name leaf? fragment-name] :as selection} fragment-map]
  (let [selection-kind (selection/selection-kind selection)]
    (cond
      ;; If it's a leaf or `pageInfo`, return nil.
      (or leaf? (= :pageInfo field-name))
      nil

      ;; If it's a named fragment, look it up in the fragment-map and process its selections.
      (= :named-fragment selection-kind)
      (let [partial-selections (:selections (fragment-map fragment-name))]
        (summarize-partial-selections fragment-map partial-selections))

      ;; If it's an inline fragment or  `edges` field, process its selections.
      (or (= :inline-fragment selection-kind) (= field-name :edges))
      (summarize-partial-selections fragment-map selections)

      ;; Otherwise, handle a regular field with potential nested selections.
      :else
      (let [n-nodes (or (-> arguments (select-keys [:first :last]) vals first) 1)]
        [{:field-name field-name
          :selections (summarize-partial-selections fragment-map selections)
          :list-args? (list-args? arguments)
          :n-nodes n-nodes}]))))

(defn ^:private calculate-complexity
  "selection 요약 정보에서 complexity를 계산합니다."
  [{:keys [selections list-args? n-nodes] :or {n-nodes 0}}]
  (let [children-complexity (apply + (map calculate-complexity selections))]
    (if list-args?
      (* n-nodes children-complexity)
      (+ n-nodes children-complexity))))

(defn ^:private calculate-max-depth
  "selection 요약 정보에서 최대 depth를 계산합니다."
  [{:keys [selections]} current-depth]
  (if (empty? selections)
    current-depth
    (apply max (map #(calculate-max-depth % (inc current-depth)) selections))))

(defn complexity-analysis
  [query]
  (let [{:keys [fragments selections]} query
        summarized-selections {:selections (summarize-partial-selections fragments selections)}]
    {:complexity (calculate-complexity summarized-selections)
     :max-depth (calculate-max-depth summarized-selections 0)}))

(defn enable-query-analyzer
  [context]
  (assoc context ::enable? true))
