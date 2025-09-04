(ns couchbase.sc
  (:require [knossos.history :as history])
  (:import (clojure.lang ExceptionInfo)))

;; ## Predicate Helpers (Correct) ##

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

;; CORRECTED: Fixed syntax error by comparing symbol to keyword.
(defn invoke-write? [op]
  (and (= :write (:f op))
       (= :invoke (:type op))))

;; ## Preprocessing and Validation (Corrected) ##

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

;; IDIOMATIC: Replaced custom `count-occurrences` with built-in `frequencies`.
(defn validate-duplicate-write-attempts
  [history]
  (let [invoked-write-vals (->> history
                                (filter invoke-write?)
                                (map :value))
        val-counts (frequencies invoked-write-vals)]
    (when (some (fn [[_ count]] (> count 1)) val-counts)
      (throw (ex-info "Duplicate write values found" {:type :invalid-history})))
    history))

;; CORRECTED: This function now correctly returns the filtered_history.
(defn preprocess-history
  [history]
  (let [history (history/ensure-indexed history)]
    (validate-duplicate-write-attempts history)
    (let [filtered-history (discard-invokes-and-fails history)]
      (validate-history filtered-history)
      filtered-history))) ; <-- Returns the correct value now.

;; ## Core Checker Logic (Corrected) ##

;; IDIOMATIC: Uses `juxt` for a more concise way to create pairs.
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

;; This helper is required for serializable-write? to provide detailed errors.
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

;; CORRECTED: This now uses `process-not-ready?` to get conflict details,
;; which fixes the crash and allows it to return a detailed error message.
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

(defn check
  "Checks if a history is sequentially consistent. The main driver for the checker."
  [raw-history]
  ;; Initial pipeline to prepare the history for checking.
  (let [;; 1. Preprocess the raw history (this step now also handles indexing).
        processed-history (preprocess-history raw-history)
        scan-to-map       (determine-last-ok-read processed-history)
        scan-fn           (fn [v] (get scan-to-map v -1))
        rewritten-history (rewrite-history processed-history)
        initial-threads   (group-by :process rewritten-history)
        initial-last-seen (into {} (map (fn [pid] [pid :nil])
                                        (keys initial-threads)))]

    ;; Main simulation loop.
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