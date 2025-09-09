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
  "Check KV linearizability using jepsen.independent with register model per key"
  [history opts]
  (let [;; Convert [k v] vectors to independent tuples
        tuple-history (core/prepare-kv-history history)
        ;; Create independent checker with register model per key
        base-checker (checker/linearizable {:model (model/register)})
        kv-checker (ind/checker base-checker)
        ;; Convert to jepsen history format
        jhist (hist/history tuple-history)
        ;; Create test context
        test-map {:name "kv-linearizable" :model (model/register)}
        ;; Run the check
        result (checker/check kv-checker test-map jhist opts)]
    {:valid? (:valid? result)
     :count (count history)
     :analysis result}))

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
