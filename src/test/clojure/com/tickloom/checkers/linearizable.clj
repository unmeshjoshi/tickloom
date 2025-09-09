(ns com.tickloom.checkers.linearizable
  "Linearizability checking for register and KV models - thin wrapper over Jepsen/Knossos"
  (:require [com.tickloom.checkers.core :as core]
            [knossos.model :as model]
            [knossos.competition :as competition]
            [jepsen.independent :as ind]
            [jepsen.checker :as checker]
            [jepsen.history :as hist]
            [clojure.edn :as edn]))

(defn check-register
  "Check register linearizability using Knossos directly"
  [history opts]
  (let [result (competition/analysis (model/register) history opts)]
    {:valid? (:valid? result)
     :count (count history)
     :analysis result}))

(defn check-kv
  "Check KV linearizability by grouping operations by key and checking each independently"
  [history opts]
  (let [;; Group operations by key
        grouped-ops (group-by (fn [op]
                               (if (vector? (:value op))
                                 (first (:value op))  ; [key value] format
                                 (:key op)))          ; {:key k :value v} format
                             history)
        ;; Check each key independently for linearizability
        ;; Convert KV operations to register operations for each key
        key-results (into {} 
                         (map (fn [[k ops]]
                                (let [register-ops (map (fn [op]
                                                         (if (vector? (:value op))
                                                           (let [[_ v] (:value op)]
                                                             (assoc op :value v))
                                                           op))
                                                       ops)]
                                  [k (check-register register-ops opts)]))
                              grouped-ops))
        ;; All keys must be linearizable
        all-valid? (every? :valid? (vals key-results))
        invalid-keys (keep (fn [[k result]] 
                            (when-not (:valid? result) k))
                          key-results)]
    {:valid? all-valid?
     :count (count history)
     :key-results key-results
     :invalid-keys invalid-keys
     :analysis {:per-key key-results
                :overall-valid all-valid?}}))

(defn check-with-model
  "Check linearizability using a custom model"
  [history custom-model opts]
  (let [result (competition/analysis custom-model history opts)]
    {:valid? (:valid? result)
     :count (count history)
     :analysis result}))

(defn check
  "Main entry point for linearizability checking.
   Supports: 'register', 'kv', or custom model objects"
  [history-edn model-type-or-object opts]
  (let [history (core/parse-history history-edn)]
    (core/validate-history history)
    
    (cond
      ;; Built-in models
      (= model-type-or-object "register") 
      (check-register history (or opts (core/default-opts)))
      
      (= model-type-or-object "kv") 
      (check-kv history (or opts (core/default-opts)))
      
      ;; Custom model object (implements knossos.model.Model)
      (and (not (string? model-type-or-object))
           model-type-or-object)
      (check-with-model history model-type-or-object (or opts (core/default-opts)))
      
      :else 
      (throw (ex-info "Unknown model type" {:model model-type-or-object})))))
