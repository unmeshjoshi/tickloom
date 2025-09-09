(ns com.tickloom.checkers.sequential
  "Sequential consistency checking for register and KV models - migrated from couchbase.sc"
  (:require [com.tickloom.checkers.core :as core]
            [knossos.history :as history]
            [jepsen.independent :as ind]
            [jepsen.checker :as checker]
            [jepsen.history :as hist]
            [knossos.competition :as competition])
  (:import (clojure.lang ExceptionInfo)))

;; === EXISTING FUNCTIONS FROM couchbase.sc (preserved exactly) ===

(defn discardable?
  [op]
  (contains? #{:invoke :fail} (:type op)))

(defn- valid-f?
  "Is the operation's function valid for our checker?"
  [op]
  (contains? #{:read :write} (:f op)))

(defn- valid-type?
  "Is the operation's type valid?"
  [op]
  (contains? #{:ok :invoke :fail :info} (:type op)))

(defn- indeterminate-read?
  "Is the operation an indeterminate read?"
  [op]
  (and (= :info (:type op))
       (= :read (:f op))))

(defn- ok-read?
  "Is the operation an ok read?"
  [op]
  (and (= :ok (:type op))
       (= :read (:f op))))

(defn info-write? [op]
  (and (= :write (:f op))
       (= :info (:type op))))

(defn invoke-write? [op]
  (and (= :write (:f op))
       (= :invoke (:type op))))

(defn validate-history
  [filtered-history]
  (when-not (every? valid-f? filtered-history)
    (throw (ex-info "History must only contain :read or :write operations"
                    {:type :invalid-history})))
  (when-not (every? valid-type? filtered-history)
    (throw (ex-info "History contains invalid operation types"
                    {:type :invalid-history})))
  (when (some indeterminate-read? filtered-history)
    (throw (ex-info ":read should not have indeterminate results"
                    {:type :invalid-history}))))

(defn discard-invokes-and-fails
  [history]
  (filter (complement discardable?) history))

(defn validate-duplicate-write-attempts
  [history]
  (let [invoked-write-vals (->> history
                                (filter invoke-write?)
                                (map :value))
        val-counts (frequencies invoked-write-vals)]
    (when (some (fn [[_ count]] (> count 1)) val-counts)
      (throw (ex-info "Duplicate write values found" {:type :invalid-history})))
    history))

(defn preprocess-history
  [history]
  (let [history (history/ensure-indexed history)]
    (validate-duplicate-write-attempts history)
    (let [filtered-history (discard-invokes-and-fails history)]
      (validate-history filtered-history)
      filtered-history)))

(defn determine-last-ok-read [history]
  (->> history
       (filter ok-read?)
       (map (juxt :value :index))
       (into {})))

(defn rewrite-history
  [history]
  (let [last-ok-read (determine-last-ok-read history)]
    (remove (fn [op] (and (info-write? op)
                          (not (last-ok-read (:value op)))))
            history)))

(defn serializable-read?
  "Check if the given op is a read op return with the current register value"
  [op reg-val last-seen-for-proc]
  (let [op-val (:value op)]
    (cond
      (not= op-val reg-val)
      {:ok? false
       :msg {:msg           "Read candidate value doesn't match register value"
             :register-value     reg-val
             :candidate          op
             :last-seen-for-proc last-seen-for-proc}}

      (and (number? op-val)
           (number? last-seen-for-proc)
           (< op-val last-seen-for-proc))
      {:ok? false,
       :msg "Read would go back in time"}

      :else
      {:ok? true, :op op})))

(defn process-not-ready?
  "Finds a `read A -> read B` conflict. If found, returns the `[A B]` pair."
  [write-value max-index process-queue]
  (loop [queue           process-queue
         may-still-read? true
         prev-op         nil]
    (if-let [op (first queue)]
      (cond
        (and (>= max-index 0) (>= (:index op) max-index))
        nil

        (and (= (:value op) write-value) (false? may-still-read?))
        [prev-op op]

        :else
        (recur (rest queue)
               (= (:value op) write-value)
               op))
      nil)))

(defn serializable-write?
  "Checks if a candidate write is valid by verifying that no process's read
  queue is blocked by it."
  [candidate thread-histories scan-to-index]
  (let [v                (:value candidate)
        scan-to          (scan-to-index v)
        queues-to-check  (vals thread-histories)
        first-conflict   (some #(process-not-ready? v scan-to %) queues-to-check)]

    (if-let [[future1 future2] first-conflict]
      {:ok? false
       :msg {:msg "Write blocked by pending reads"
             :candidate candidate
             :future1   future1
             :future2   future2}}
      {:ok? true
       :op  candidate})))

(defn determine-next-op
  "Determines the next valid operation to be serialized. Tries all available
  reads first. If no read is valid, tries all available writes. If none are
  valid, returns a collection of all failure reasons."
  [reg-val thread-histories scan-fn last-seen]
  (let [heads       (keep first (vals thread-histories))
        read-heads  (filter #(= :read (:f %)) heads)
        write-heads (sort-by :value (filter #(= :write (:f %)) heads)) ; sort for determinism

        ;; Step 1: Try all reads first, collecting their results.
        read-results (map (fn [op]
                            (serializable-read? op
                                                reg-val
                                                (get last-seen (:process op))))
                          read-heads)]

    ;; Step 2: Did we find a successful read?
    (if-let [successful-read (first (filter :ok? read-results))]
      successful-read ; If so, we're done. Return it immediately.

      ;; Step 3: If not, try all the writes.
      (let [write-results (map (fn [op]
                                 (serializable-write? op thread-histories scan-fn))
                               write-heads)]
        (if-let [successful-write (first (filter :ok? write-results))]
          successful-write ; A write is valid. Return it.

          ;; Step 4: If no read OR write was valid, we are stuck.
          (let [read-failures  (map :msg (remove :ok? read-results))
                write-failures (map :msg (remove :ok? write-results))]
            {:ok?      false
             :failures (concat read-failures write-failures)}))))))

;; === REGISTER CHECKING (renamed from original 'check') ===

(defn check-register
  "Checks if a history is sequentially consistent for single register. 
   This is the original couchbase.sc/check function."
  [raw-history]
  (let [processed-history (preprocess-history raw-history)
        scan-to-map       (determine-last-ok-read processed-history)
        scan-fn           (fn [v] (get scan-to-map v -1))
        rewritten-history (rewrite-history processed-history)
        initial-threads   (group-by :process rewritten-history)
        initial-last-seen (into {} (map (fn [pid] [pid :nil])
                                        (keys initial-threads)))]

    (loop [reg-val          :nil
           thread-histories initial-threads
           last-seen        initial-last-seen]

      (if (every? empty? (vals thread-histories))
        {:valid? true}

        (let [next-op-res (determine-next-op reg-val
                                             thread-histories
                                             scan-fn
                                             last-seen)]
          (if (:ok? next-op-res)
            (let [op      (:op next-op-res)
                  proc-id (:process op)
                  op-val  (:value op)]
              (recur (if (= :write (:f op)) op-val reg-val)
                     (update thread-histories proc-id rest)
                     (if (= :read (:f op)) (assoc last-seen proc-id op-val) last-seen)))

            {:valid? false :failures (:failures next-op-res)}))))))

;; === NEW KV SUPPORT using jepsen.independent ===

(defrecord PerKeySequentialChecker []
  checker/Checker
  (check [this test history opts]
    ;; Use existing register sequential consistency logic for each key
    (let [result (check-register history)]
      {:valid? (:valid? result)
       :count (count history)
       :failures (when-not (:valid? result) (:failures result))})))

(defn per-key-sequential-checker []
  (PerKeySequentialChecker.))

(defn check-kv
  "Check KV sequential consistency by grouping operations by key and checking each independently"
  [history opts]
  (let [;; Group operations by key
        grouped-ops (group-by (fn [op]
                               (if (vector? (:value op))
                                 (first (:value op))  ; [key value] format
                                 (:key op)))          ; {:key k :value v} format
                             history)
        ;; Check each key independently for sequential consistency
        key-results (into {} 
                         (map (fn [[k ops]]
                                [k (check-register ops)])
                              grouped-ops))
        ;; All keys must be sequentially consistent
        all-valid? (every? :valid? (vals key-results))
        invalid-keys (keep (fn [[k result]] 
                            (when-not (:valid? result) k))
                          key-results)]
    {:valid? all-valid?
     :count (count history)
     :key-results key-results
     :invalid-keys invalid-keys
     :failures (when-not all-valid?
                 (mapcat :failures (vals key-results)))}))

;; === CUSTOM MODEL SUPPORT ===

(defn check-with-model
  "Check sequential consistency using a custom model by dropping real-time constraints"
  [history custom-model opts]
  (let [;; Remove real-time constraints for sequential consistency
        sequential-history (mapv #(assoc % :time 0) history)
        result (competition/analysis custom-model sequential-history opts)]
    {:valid? (:valid? result)
     :count (count history)
     :failures (when-not (:valid? result) [(:error result)])}))

;; === MAIN DISPATCHER ===

(defn check
  "Main entry point for sequential consistency checking.
   Supports: 'register', 'kv', or custom model objects"
  [history-edn model-type-or-object opts]
  (let [history (core/parse-history history-edn)]
    (core/validate-history history)
    
    (cond
      ;; Built-in models
      (= model-type-or-object "register") 
      (check-register history)
      
      (= model-type-or-object "kv") 
      (check-kv history (or opts (core/default-opts)))
      
      ;; Custom model object (implements knossos.model.Model)
      (and (not (string? model-type-or-object))
           model-type-or-object)
      (check-with-model history model-type-or-object (or opts (core/default-opts)))
      
      :else 
      (throw (ex-info "Unknown model type" {:model model-type-or-object})))))
