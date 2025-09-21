(ns com.tickloom.jepsen.jepsencaller
  (:require [clojure.edn :as edn]
            [jepsen.checker :as checker]
            [jepsen.independent :as independent]
            [knossos.model :as model]
            [jepsen.history :as h]
            [jepsen.checker.timeline :as timeline]
            [com.tickloom.checkers.sequential :as seq]))

(defn value-tuple
  [history]
  (map (fn [v] (update v :value (fn [vv] (independent/tuple (first vv) (second vv)))))
       history))


(def model-map {
                "register"     (model/register)
                "cas-register" (model/cas-register)
                "set"          (model/set)
                })
(defn get-checker [mode model]
  (let [m (get model-map model)]
    (cond
      (= mode "linearizable")
      (checker/compose
        {:linearizable (checker/linearizable
                         {:model m})
         :timeline     (timeline/html)})

      (= mode "sequential")
      (checker/compose
        {:sequential (seq/checker "register")
         :timeline   (timeline/html)})

      :else
      (throw (ex-info "Unknown checker mode" {:mode mode})))))

(defn analyze
  "Generic entry point.
   Args map:
     :history-edn  - string (EDN vector of op maps)
     :mode         - :linearizable (default) | :sequential
     :model        - builtin keyword (e.g. :register) OR a Java Model instance
     :opts-edn     - optional EDN map string for kc/analysis, e.g. {:time-limit 60000}

   Returns a map {:valid? boolean, :result <full-analysis-map>}."
  ;Add 'mode' to specify whether sequential consistency or linearizability check is needed.
  ;As of now doing only linearizability check for the given model.
  [{:keys [history-edn mode model opts-edn independent]
    :or   {opts-edn "{:time-limit 60000}" model "register" mode "linearizable" independent false}}]
  (let [history (h/history (value-tuple (edn/read-string history-edn)))
        opts (edn/read-string opts-edn)
        c (get-checker mode model)
        checker (if independent
                  (independent/checker c)
                  c)
        result (checker/check checker {:name "independent-checker-test", :start-time 0} history opts)]
    (println mode model result)
    {:valid? (:valid? result)
     :result result}))



;; A convenience that returns only a boolean (handy for Java)
(defn analyze? ^Boolean
  [history-edn mode model-key opts-edn]
  (let [{:keys [valid? result]} (analyze {:history-edn history-edn
                                          :mode        mode
                                          :model       model-key
                                          :opts-edn    (or opts-edn "{:time-limit 60000}")})]
    (boolean valid?)))

(defn analyze-independent? ^Boolean
  [history-edn mode model-key opts-edn]
  (let [{:keys [valid? result]} (analyze {:history-edn history-edn
                                          :mode        mode
                                          :model       model-key
                                          :independent true
                                          :opts-edn    (or opts-edn "{:time-limit 60000}")})]
    (boolean valid?)))