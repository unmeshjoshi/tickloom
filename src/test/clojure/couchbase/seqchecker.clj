(ns couchbase.seqchecker
  "A sequential consistency checker for Jepsen"
  (:require [clojure.edn :as edn]))

(defn ok-read?
  "Check if an operation is an ok read operation"
  [op]
  (and (= (:f op) :read)
       (= (:type op) :ok)))

(defn info-write?
  "Check if an operation is an indeterminate write"
  [op]
  (and (= (:f op) :write)
       (= (:type op) :info)))

(defn invoke-write?
  "Check if an operation is a write invocation"
  [op]
  (and (= (:f op) :write)
       (= (:type op) :invoke)))

(defn discardable?
  "Invocations, failures and nemesis actions are irrelevant for our checker"
  [op]
  (or (contains? #{:invoke :fail} (:type op))
      (= (:process op) :nemesis)))

(defn preprocess-history
  "Check input sanity while discarding ops we won't need later"
  [input-history]
  (loop [remaining-history input-history
         filtered-history (transient [])
         written-vals (transient #{})]
    (if-let [op (first remaining-history)]
      (do
        ;; All non-nemesis ops must be :invoke :ok :info or :fail
        (when-not (or (contains? #{:invoke :ok :info :fail} (:type op))
                      (= (:process op) :nemesis))
          (throw (ex-info (str "Unknown op type: " (:type op))
                          {:invalid-op op})))
        ;; All non-nemesis ops must be :read or :write
        (when-not (or (contains? #{:read :write} (:f op))
                      (= (:process op) :nemesis))
          (throw (ex-info (str "Unknown op function: " (:f op))
                          {:invalid-op op})))
        ;; Read ops must return :ok or :fail, not :info
        (when (and (= :info (:type op))
                   (= :read (:f op))
                   (not= (:process op) :nemesis))
          (throw (ex-info "Read must not return indeterminate result"
                          {:invalid-op op})))
        ;; Writes can only be attempted once for a given value
        (when (and (invoke-write? op)
                   (contains? written-vals (:value op))
                   (not= (:process op) :nemesis))
          (throw
            (ex-info (str "Multiple attempts at writing value " (:value op))
                     {:invalid-op op})))

        (recur (rest remaining-history)
               (if-not (discardable? op)
                 (conj! filtered-history op)
                 filtered-history)
               (if (invoke-write? op)
                 (conj! written-vals (:value op))
                 written-vals)))

      (persistent! filtered-history))))

(defn determine-last-ok-read
  "Build a map with the op index of the last ok read for each write value"
  [history]
  (loop [remaining-history history
         last-ok-read (transient (hash-map))]
    (if-let [op (first remaining-history)]
      (recur (rest remaining-history)
             (if (ok-read? op)
               (assoc! last-ok-read (:value op) (:index op))
               last-ok-read))
      (persistent! last-ok-read))))

(defn rewrite-history
  "Indeterminate operations cause performance problems when trying to determine
  a single-order, so rewrite the history such that all unobserved indeterminate
  operations failed, i.e never logically happened so can be removed from the
  history. We can then assume all remaining (i.e observed) indeterminate write
  ops succeeded.

  This rewritten history is sequentially consistent if and only if the input
  history is sequentially consistent, due to the following conditions:
  1) We only attempt a write once for a given value, and we assume that value
     cannot become corrupted during reading/writing, so if we observe the
     value we know the write cannot have failed.
  2) Jepsen workers are logically single threaded: they won't service further
     operations after an indeterminate result. So even if we incorrectly
     assume an op failed, when in actuality it was successfull but unobserved,
     that op can always be serialized after all read operations such that it
     has no effect (note that this is only holds while we forbid CAS ops)."
  [history last-ok-read]
  (loop [remaining-history history
         rewritten-history (transient [])]
    (if-let [op (first remaining-history)]
      (if (info-write? op)
        ;; If indeterminate write then rewrite history
        (recur (rest remaining-history)
               (if-let [last-ok-read-for-value (last-ok-read (:value op))]
                 ;; If observed: tag the indeterminate op with the index of an
                 ;; op that observed it to aid debugging
                 (conj! rewritten-history
                        (assoc op :observed-by last-ok-read-for-value))
                 ;; If op was not observed: discard it
                 rewritten-history))
        ;; If op is not an indeterminate write just pass it through
        (recur (rest remaining-history)
               (conj! rewritten-history op)))
      (persistent! rewritten-history))))

(defn serializable-read?
  "Check if the given op is a read op return with the current register value"
  [op reg-val]
  (if (= (:value op) reg-val)
    {:ok? true :op op}
    {:ok? false
     :msg {:msg "Read candidate value doesn't match register value"
           :register-value reg-val
           :candidate op}}))

(defn thread-not-ready?
  "Check if the thread requires a different value to be read/written before
  a read for the current candidate. If so, return that operation, else return
  nil"
  [write-value max-index thread-history]
  (loop [history thread-history
         may-still-read-value true
         prev-op nil]
    (if-let [op (first history)]
      (let [op-value (:value op)
            op-index (:index op)]
        ;; If we see op-value == write-value, after having previously seen an
        ;; op where op-value != write-value we can't serialize the write until
        ;; those previous ops have been serialized (or ever if we also need to
        ;; serialize this op before those, but we don't need to handle such
        ;; circular dependency here)
        (if (and (= op-value write-value) (not may-still-read-value))
          [prev-op op]
          ;; Keep scanning until we reach/exceed max-index
          (if (< op-index max-index)
            (recur (rest history) (= op-value write-value) op)))))))

(defn serializable-write?
  "Check if the given op is a write op that can be serialized"
  [op thread-histories scan-to-index]
  ;; Op might be serializable, we need to look ahead until the last observed
  ;; read to see if we can serialize all reads yet, or if some other value
  ;; needs to be written first
  (let [op-val (:value op)
        scan-to (scan-to-index op-val -1)
        not-ready (some (partial thread-not-ready? op-val scan-to)
                        (vals thread-histories))]
    (if not-ready
      {:ok? false
       :msg {:msg (str "Cannot serialize write candidate until future1 has "
                       "been serialized, else future2 cannot be serialized.")
             :candidate op
             :future1 (first not-ready)
             :future2 (second not-ready)}}
      {:ok? true :op op})))

(defn determine-next-op
  "Determine and return the next op in the single-order if one exists. If no
  op can be inserted return a map listing why each candidate was rejected.

  Note that for a given observed history there may exist multiple potential
  single-orders. This function must always pick an op such that if a
  single-order exists, then there exists a single-order with the returned op
  serialized before all other pending ops. Since we only care whether such an
  order exists, the particular chosen order is irrelevant"
  [reg-val thread-histories scan-to-index last-seen]
  (loop [candidates (sort-by :f (keep first (vals thread-histories)))
         reject-msg []]
    (if-let [candidate (first candidates)]
      (let [proc (:process candidate)
            last-val (last-seen proc)
            op-type (:f candidate)]
        (case op-type
          :read (let [candidate-val (:value candidate)
                      check (cond
                             ;; If this is the first read for this process, just check register value
                             (nil? last-val)
                             (if (= candidate-val reg-val)
                               {:ok? true :op candidate}
                               {:ok? false
                                :msg {:msg "First read doesn't match register value"
                                      :register-value reg-val
                                      :candidate candidate}})

                             ;; If we've seen a value before, make sure we're not going backwards
                             (and (number? last-val) (number? candidate-val))
                             (if (>= candidate-val last-val)
                               (if (= candidate-val reg-val)
                                 {:ok? true :op candidate}
                                 {:ok? false
                                  :msg {:msg "Read candidate value doesn't match register value"
                                        :register-value reg-val
                                        :candidate candidate}})
                               {:ok? false
                                :msg "Read would go back in time for numeric values"})

                             ;; For non-numeric values, just check equality with register value
                             :else
                             (if (= candidate-val reg-val)
                               {:ok? true :op candidate}
                               {:ok? false
                                :msg {:msg "Read candidate value doesn't match register value"
                                      :register-value reg-val
                                      :candidate candidate}}))]
                  (if (and (map? check) (:ok? check))
                    check
                    (let [msg (if (map? check) (:msg check) check)]
                      (recur (rest candidates)
                             (conj reject-msg msg)))))

          :write (let [check (serializable-write? candidate thread-histories scan-to-index)]
                   (if (:ok? check)
                     check
                     (recur (rest candidates)
                            (conj reject-msg (:msg check)))))

          (recur (rest candidates) (conj reject-msg (str "Unknown operation type: " op-type)))))

      {:ok? false :failures reject-msg})))

(defn sequential
  "Return a sequential consistency checker"
  [history-edn opts]
      (let [history (edn/read-string history-edn)
            history (preprocess-history history)
            scan-to (determine-last-ok-read history)
            history (rewrite-history history scan-to)]
        ;; Loop over all observed operations to build a single-order that obeys
        ;; PRAM and RVal
        (loop [thread-histories (group-by :process history)
               register-value :nil
               last-seen (zipmap (keys thread-histories) (repeat :nil))]
          ;; Are there any ops left?
          (if-not (every? empty? (vals thread-histories))
            ;; Determine which op to serialize next
            (let [next-op (determine-next-op register-value
                                           thread-histories
                                           scan-to
                                           last-seen)]
              (if (:ok? next-op)
                ;; determine-next-op returns a map with :ok? true if it found
                ;; an op that can be serialized next. If so, we can discard
                ;; that op from the thread-history, update the register value,
                ;; and loop.
                (let [next-op (:op next-op)
                      op-proc (:process next-op)
                      op-val (:value next-op)
                      op-type (:f next-op)]
                  (recur (update thread-histories op-proc rest)
                         (if (= op-type :write) op-val register-value)
                         (if (= op-type :read)
                           (assoc last-seen op-proc op-val)
                           last-seen)))
                ;; determine-next-op returns a map with :ok? false if no next
                ;; op could be determined. Assuming correct implementation,
                ;; this means that no single-order can exist. Return an :valid?
                ;; false and pass through the candidate failure reasons to aid
                ;; debugging
                {:valid? false
                 :msg "All candidates are invalid"
                 :failures (:failures next-op)}))
            ;; If all ops have successfully been inserted into a single-order
            ;; then the history is sequentially consistent
            {:valid? true}))))

