(ns com.tickloom.jepsen.jepsencaller
  (:require [clojure.edn :as edn]
            [knossos.model :as km]
            [knossos.competition :as kc]
            [jepsen.independent :as ind]
            [jepsen.checker :as checker]
            [jepsen.history :as h]))

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


;; Utility: ensure every op has an :index; return EDN string
(defn ensure-indexed-edn ^String [history-edn]
  (let [ops  (edn/read-string history-edn)
        ops' (map-indexed (fn [i op]
                            (if (contains? op :index)
                              op
                              (assoc op :index i)))
                          ops)]
    (pr-str (vec ops'))))


;; Sequential (ignoring real-time): collapse intervals to single events and lift to independent tuples
(defn analyze-kv-seq? ^Boolean [history-edn opts-edn]
  (let [history  (edn/read-string history-edn)
        ;; erase real-time precedence by setting all times to 0
        no-rt    (mapv #(assoc % :time 0) history)
        ;; normalize [k v] values into independent tuples (retain full invoke/ok history)
        lifted   (mapv (fn [op]
                         (if (and (vector? (:value op)) (= 2 (count (:value op))))
                           (let [[k v] (:value op)]
                             (assoc op :value (ind/tuple k v)))
                           op))
                       no-rt)
        opts     (edn/read-string (or opts-edn "{:time-limit 60000}"))
        base     (checker/linearizable {:model (km/register)})
        kv       (ind/checker base)
        jhist    (h/history lifted)
        test-map {:name "kv-sequential"
                  :start-time (System/currentTimeMillis)
                  :model (km/register)}
        result   (checker/check kv test-map jhist opts)]
    (true? (:valid? result))))


(defn ->sc-history
  "Turn a Jepsen history into an SC-checkable one:
   - keep only :ok events (optional but recommended),
   - remove all realtime fields so no RT edges remain."
  [ops]
  (->> ops
       (mapv #(-> %
                  (dissoc :time :start :end :wall-time)
                  (assoc  :time 0 :start 0 :end 1)))))

(defn explain! [res]
  (if (:valid? res)
    (println "OK")
    (do
      (println "SC VIOLATION")
      (when-let [fp (first (:final-paths res))]
        (println "  Witness prefix:")
        (doseq [step fp]
          (println "   " (:op step) "=> model" (:model step))))
      (when-let [op (:op res)]
        (println "  Offending op:" (select-keys op [:process :f :value :index])))
      (when-let [cfg (first (:configs res))]
        (println "  Stuck at model:" (:model cfg)
                 " last-op:" (select-keys (:last-op cfg) [:process :f :value :index])
                 " pending:" (:pending cfg))))))

(defn complete-ops
  "From a full paired history H, produce one completed map per client :ok op.
   IMPORTANT: H must be the unfiltered history that still contains both halves."
  [H]
  (into []
        (map (fn [ok]                      ; ok belongs to H (via filtered view)
               (let [inv (h/invocation H ok)]
                 ;; If inv is nil, that means the invoke isn't in H -> assertion you saw.
                 (assert inv (str "No invocation for completion: " ok))
                 ;; Merge what you need; keep :process/:f from the invoke,
                 ;; and :type/:value/:index from the completion.
                 (merge (select-keys inv [:process :f])
                        (select-keys ok  [:type :value :index])))))
        (h/oks (h/client-ops H))))

(defn erase-rt [ops]
  (mapv #(dissoc % :time :start :end :wall-time) ops))

(defn analyze-register-seq
  "SC on single-key register: complete -> erase RT -> analyze."
  [history-edn opts-edn]
  (let [raw   (edn/read-string history-edn)
        ;; 1) Build Jepsen history from the FULL raw vector (do NOT pre-filter)
        H     (h/history raw {:have-indices? true})
        ;; 2) Pair :invoke/:ok into completed ops using the same H
        done  (complete-ops H)
        ;; 3) Remove RT fields so only per-client program order remains
        no-rt (erase-rt done)
        ;; 4) Analyze
        opts  (merge {:time-limit 60000} (some-> opts-edn edn/read-string))]
    (println (complete-ops H))
    (println no-rt)
    (kc/analysis (km/register) no-rt opts)))       ; 3) analyze with register model
