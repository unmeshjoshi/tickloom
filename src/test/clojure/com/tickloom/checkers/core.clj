(ns com.tickloom.checkers.core
  "Shared utilities for consistency checking"
  (:require [jepsen.independent :as ind]
            [clojure.edn :as edn]))

(defn prepare-kv-history
  "Convert [k v] vectors to independent tuples for jepsen.independent"
  [history]
  (mapv (fn [op]
          (if (and (vector? (:value op)) (= 2 (count (:value op))))
            (let [[k v] (:value op)]
              (assoc op :value (ind/tuple k v)))
            op))
        history))

(defn parse-history
  "Parse EDN string to Clojure data structure"
  [history-edn]
  (edn/read-string history-edn))

(defn detect-model
  "Auto-detect model type based on history format
   Returns :register if operations have no keys, :kv if operations have [k v] format"
  [history]
  (if (some (fn [op]
              (and (vector? (:value op)) (= 2 (count (:value op)))))
            history)
    :kv
    :register))

(defn validate-history
  "Basic validation of history format"
  [history]
  (when-not (sequential? history)
    (throw (ex-info "History must be a sequence" {:history history})))
  
  (doseq [op history]
    (when-not (map? op)
      (throw (ex-info "Each operation must be a map" {:operation op})))
    
    (when-not (contains? op :type)
      (throw (ex-info "Operation must have :type" {:operation op})))
    
    (when-not (contains? op :f)
      (throw (ex-info "Operation must have :f (function)" {:operation op}))))
  
  true)

(defn default-opts
  "Default options for consistency checking"
  []
  {:time-limit 60000})
