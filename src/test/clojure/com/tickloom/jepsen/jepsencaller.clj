(ns com.tickloom.jepsen.jepsencaller
  (:require [clojure.edn :as edn]
            [knossos.model :as km]
            [knossos.competition :as kc]
            [jepsen.independent :as ind]
            [jepsen.checker :as checker]
            [jepsen.history :as h]
            [com.tickloom.checkers.sequential :as seq]
            [com.tickloom.checkers.linearizable :as lin]))

;; Built-in model ctors we’ll expose by keyword
(def ^:private builtin-models
  {:register        km/register
   :cas-register    km/cas-register
   :mutex           km/mutex
   :fifo-queue      km/fifo-queue
   :unordered-queue km/unordered-queue
   :set             km/set})

(defn- choose-model
  "Return a knossos.model/Model instance from:
   - a keyword naming a builtin (e.g. :register),
   - or a Java object that already implements knossos.model.Model."
  [m]
  (cond
    (instance? knossos.model.Model m)
    m

    (keyword? m)
    (if-let [ctor (builtin-models m)]
      (ctor)
      (throw (ex-info (str "Unknown builtin model: " m) {:model m})))

    :else
    (throw (ex-info (str "Unsupported model argument: " (type m)) {:model m}))))

(defn- drop-realtime
  "Erase real-time precedence by making all ops 'overlap' (sets :time to 0)."
  [history]
  (mapv #(assoc % :time 0) history))

(defn analyze
  "Generic entry point.
   Args map:
     :history-edn  - string (EDN vector of op maps)
     :mode         - :linearizable (default) | :sequential
     :model        - builtin keyword (e.g. :register) OR a Java Model instance
     :opts-edn     - optional EDN map string for kc/analysis, e.g. {:time-limit 60000}

   Returns a map {:valid? boolean, :result <full-analysis-map>}."
  [{:keys [history-edn mode model opts-edn]
    :or   {mode :linearizable
           opts-edn "{:time-limit 60000}"}}]
  (let [history (edn/read-string history-edn)
        model   (choose-model model)
        opts    (edn/read-string opts-edn)
        history' (case mode
                   :sequential   (drop-realtime history)
                   :linearizable history
                   history)
        result  (kc/analysis model history' opts)]
    {:valid? (:valid? result)
     :result result}))

;; A convenience that returns only a boolean (handy for Java)
(defn analyze? ^Boolean [history-edn mode model-key opts-edn]
  (let [mode      (if (or (nil? mode) (= "" mode)) :linearizable (keyword mode))
        model-key (when model-key (keyword model-key))
        {:keys [valid?]} (analyze {:history-edn history-edn
                                   :mode        mode
                                   :model       model-key
                                   :opts-edn    (or opts-edn "{:time-limit 60000}")})]
    (boolean valid?)))

;; Accept a concrete knossos.model.Model instance directly from Java interop
(defn analyze-with-model? ^Boolean [history-edn mode model-object opts-edn]
  (let [mode   (if (or (nil? mode) (= "" mode)) :linearizable (keyword mode))
        {:keys [valid?]} (analyze {:history-edn history-edn
                                   :mode        mode
                                   :model       model-object
                                   :opts-edn    (or opts-edn "{:time-limit 60000}")})]
    (boolean valid?)))

;; Independent KV: values must be [k v] tuples. We split into per-key histories
;; and run knossos.competition/analysis with a register model per key.
(defn analyze-kv? ^Boolean [history-edn mode opts-edn]
  (let [mode         (if (or (nil? mode) (= "" mode)) :linearizable (keyword mode))
        history      (edn/read-string history-edn)
        history'     (case mode
                       :sequential (mapv #(assoc % :time 0) history)
                       :linearizable history
                       history)
        ;; Normalize [k v] values into independent tuples
        history''    (mapv (fn [op]
                             (if (and (vector? (:value op)) (= 2 (count (:value op))))
                               (let [[k v] (:value op)]
                                 (assoc op :value (ind/tuple k v)))
                               op))
                           history')
        opts         (edn/read-string (or opts-edn "{:time-limit 60000}"))
        base-checker (checker/linearizable {:model (km/register)})
        kv-checker   (ind/checker base-checker)
        jhist        (h/history history'')
        test-map     {:name "kv-independent"
                      :start-time (System/currentTimeMillis)
                      :model (km/register)}
        result       (checker/check kv-checker test-map jhist opts)]
    (true? (:valid? result))))

(defn check-register-seq
  [history-edn]
  (let [history (edn/read-string history-edn)]
    (seq/check-register history)))

