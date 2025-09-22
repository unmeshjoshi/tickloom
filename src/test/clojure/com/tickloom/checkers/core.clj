(ns com.tickloom.checkers.core
  "Shared utilities for consistency checking"
  (:require [jepsen.independent :as ind]
            [clojure.edn :as edn]))


(defn parse-history
  "Parse EDN string to Clojure data structure"
  [history-edn]
  (cond
    (string? history-edn)                 (edn/read-string history-edn)     ; or (core/parse-history x)
    :else                       history-edn))                     ; already EDN data



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