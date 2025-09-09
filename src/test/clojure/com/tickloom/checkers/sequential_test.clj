(ns com.tickloom.checkers.sequential-test
  (:require [clojure.test :refer :all]
            [knossos.op :as op]
            [knossos.history :refer [index]]
            [com.tickloom.checkers.sequential :as seq])
  (:import (clojure.lang ExceptionInfo)))

(deftest discardable-test
  (is (seq/discardable? {:type :invoke}))
  (is (seq/discardable? {:type :fail}))
  (is (not (seq/discardable? {:type :ok})))
  (is (not (seq/discardable? {:type :info}))))

(deftest preprocess-validation-throws-test
  (testing "preprocess throws on invalid op type"
    (is (thrown? ExceptionInfo
                 (seq/preprocess-history [(assoc (op/ok 0 :read 1) :type :weird)]))))

  (testing "preprocess throws on invalid op function"
    (is (thrown? ExceptionInfo
                 (seq/preprocess-history
                   [(assoc (op/ok 0 :read 1) :f :badf)]))))

  (testing "preprocess throws on indeterminate read"
    (is (thrown? ExceptionInfo
                 (seq/preprocess-history [(op/info 0 :read nil)]))))

  (testing "preprocess throws on duplicate write attempts"
    (is (thrown? ExceptionInfo
                 (seq/preprocess-history [(op/invoke 0 :write 5)
                                         (op/invoke 1 :write 5)])))))

(deftest determine-last-ok-read-test
  ;; CORRECTED: This test now uses knossos helpers for consistency and the
  ;; assertion syntax is fixed. It correctly identifies that only the read
  ;; of value 2 should be in the final map.
  (let [raw-history [(op/ok 0 :write 1)
                     (op/ok 0 :read 2)]
        history (index raw-history)]
    (is (= {2 1} (seq/determine-last-ok-read history)))))

(deftest rewrite-history-test
  (testing "unobserved info writes are discarded"
    (let [raw-history [(op/invoke 0 :write 1)
                       (op/ok 0 :write 1)
                       (op/invoke 1 :write 42)
                       (op/info 1 :write 42)                ; Observed, should be kept
                       (op/invoke 1 :write 99)
                       (op/info 1 :write 99)                ; Unobserved, should be removed
                       (op/invoke 2 :read 42)
                       (op/ok 2 :read 42)
                       (op/invoke 3 :read 1)
                       (op/ok 3 :read 1)]
          history (index raw-history)
          rewritten-history (seq/rewrite-history history)]

      ;; CORRECTED ASSERTION 1:
      ;; The count should be 9 because exactly one operation was removed.
      (is (= 9 (count rewritten-history)))

      ;; CORRECTED ASSERTION 2:
      ;; The most precise check: verify that no operation in the final
      ;; history is an :info write of 99.
      (is (not (some (fn [op]
                       (and (= (:value op) 99)
                            (= (:type op) :info)))
                     rewritten-history))))))

(deftest serializable-read-test
  (testing "first read must equal register value"
    (is (:ok? (seq/serializable-read? (op/ok 1 :read 5) 5 :nil)))
    (let [res (seq/serializable-read? (op/ok 1 :read 6) 5 :nil)]
      (is (false? (:ok? res)))
      (is (= 5 (:register-value (:msg res))))))

  (testing "numeric reads must be monotonic and equal register"
    (is (:ok? (seq/serializable-read? (op/ok 1 :read 6) 6 5)))
    (let [res (seq/serializable-read? (op/ok 1 :read 4) 4 5)]
      (is (false? (:ok? res)))
      (is (= "Read would go back in time" (:msg res)))))

  (testing "non-numeric reads must equal register"
    (is (false? (:ok? (seq/serializable-read? (op/ok 2 :read :bar) :foo :nil))))))

(deftest process-not-ready-test
  (let [opA (op/ok 0 :read 'A')
        opB (op/ok 0 :read 'B')]

    (testing "Process is NOT ready when a different value (A) is read before the write-value (B)"
      (let [history (index [opA opB])]
        ;; Should return the conflicting pair [A B]
        (is (some? (seq/process-not-ready? 'B' 999 history)))))

    (testing "Process IS ready when the write-value (B) is read before a different value (A)"
      (let [history (index [opB opA])]
        (is (nil? (seq/process-not-ready? 'B' 999 history)))))

    (testing "Process IS ready when only the write-value (B) is read"
      (let [history (index [(op/ok 0 :read 'B') opB])]
        (is (nil? (seq/process-not-ready? 'B' 999 history)))))

    (testing "Process IS ready when the write-value (B) is never read"
      (let [history (index [opA])]
        (is (nil? (seq/process-not-ready? 'B' 999 history)))))

    (testing "Process IS ready when the conflicting read is at or after max-index"
      (let [history (index [opA opB])]
        ;; max-index of 1 excludes opB, which will be at index 1
        (is (nil? (seq/process-not-ready? 'B' 1 history)))))))


(deftest serializable-write-tests
  (testing "write is accepted when no other processes have conflicting reads"
    (let [raw-history [(op/ok 0 :write 2)]
          history (index raw-history)
          candidate (first history)]
      (is (:ok? (seq/serializable-write? candidate {} (constantly -1))))))

  (testing "write is rejected when another process has a conflicting read queue (read A -> read B)"
    (let [raw-history [(op/ok 1 :read 1)
                       (op/ok 1 :read 2)
                       (op/ok 0 :write 2)]
          history (index raw-history)
          candidate (first (filter #(= :write (:f %)) history))
          thread-histories (group-by :process (filter #(= :read (:f %)) history))
          res (seq/serializable-write? candidate thread-histories (constantly 999))]

      (is (false? (:ok? res)))
      (is (= candidate (get-in res [:msg :candidate])))
      (is (= (get-in thread-histories [1 0]) (get-in res [:msg :future1])))
      (is (= (get-in thread-histories [1 1]) (get-in res [:msg :future2])))))

  (testing "write is accepted when a conflict exists but is outside the scan-limit"
    (let [raw-history [(op/ok 1 :read 1)
                       (op/ok 1 :read 2)                    ; This op will have index 1
                       (op/ok 0 :write 2)]
          history (index raw-history)
          candidate (first (filter #(= :write (:f %)) history))
          thread-histories (group-by :process (filter #(= :read (:f %)) history))
          scan-fn (constantly 1)]
      (is (:ok? (seq/serializable-write? candidate thread-histories scan-fn))))))


(deftest determine-next-op-test
  (testing "next-op picks a valid read when one is available"
    (let [raw-history [(op/ok 0 :read 5)
                       (op/ok 1 :write 6)]
          history (index raw-history)
          reg 5
          ths (group-by :process history)
          scan-fn (constantly -1)
          last-seen {0 :nil, 1 :nil}
          res (seq/determine-next-op reg ths scan-fn last-seen)]
      (is (:ok? res))
      (is (= :read (:f (:op res))))))

  (testing "next-op picks a write when no reads are valid"
    (let [raw-history [(op/ok 0 :read 2)
                       (op/ok 1 :write 2)]
          history (index raw-history)
          reg 1
          ths (group-by :process history)
          scan-fn (constantly -1)
          last-seen {0 :nil, 1 :nil}
          res (seq/determine-next-op reg ths scan-fn last-seen)]
      (is (:ok? res))
      (is (= :write (:f (:op res))))
      (is (= 2 (:value (:op res))))))

  (testing "next-op returns all failures when stuck"
    ;; Make write(5) blocked by P0’s [read 2, read 5] sequence.
    (let [raw-history [(op/ok 0 :read 2)                    ; head read mismatches reg=1 -> read fails
                       (op/ok 0 :read 5)                    ; creates blocker for write(5) in same thread
                       (op/ok 1 :write 5)]                  ; candidate write that will now be blocked
          history (index raw-history)                       ; make sure this adds :index 0,1,2...
          reg 1
          ths (group-by :process history)
          scan-fn (constantly 999)                          ; scan far enough to see the blocker
          last-seen {0 :nil, 1 :nil}
          res (seq/determine-next-op reg ths scan-fn last-seen)]
      (println res)
      (is (false? (:ok? res)))
      ;; Two head candidates: the read (fails) and the write (blocked) -> 2 failures
      (is (= 2 (count (:failures res)))))))


(deftest check-driver-test
  (testing "check returns valid for a simple, correct history"
    (let [;; Create the history using knossos helpers, without indices.
          raw-history [(op/ok 0 :write 1)
                       (op/ok 1 :read 1)
                       (op/ok 0 :write 2)
                       (op/ok 1 :read 2)]]
      ;; Pass the raw history directly to check.
      (is (= {:valid? true} (seq/check-register raw-history)))))

  (testing "check returns invalid and failure reasons for a stuck history"
    ;; Heads: several reads that don't match reg=:nil, and two writes (1,2).
    ;; Blockers:
    ;;  - For write(1): in proc 2 we have read 0 then read 1 (before scan-to),
    ;;    and proc 5 has a later read 1 to push scan-to for value 1 high.
    ;;  - For write(2): in proc 3 we have read 0 then read 2 (before scan-to),
    ;;    and proc 6 has a later read 2.
    (let [raw-history [(op/ok 2 :read 0)                    ; i=0  head read (≠ :nil) -> read mismatch
                       (op/ok 2 :read 1)                    ; i=1  creates (other -> 1) blocker for write(1)
                       (op/ok 3 :read 0)                    ; i=2  head read (≠ :nil) -> read mismatch
                       (op/ok 3 :read 2)                    ; i=3  creates (other -> 2) blocker for write(2)
                       (op/ok 1 :write 1)                   ; i=4  candidate write(1)
                       (op/ok 4 :write 2)                   ; i=5  candidate write(2)
                       (op/ok 5 :read 1)                    ; i=6  pushes last-ok-read(1) to 6
                       (op/ok 6 :read 2)]                   ; i=7  pushes last-ok-read(2) to 7
          res (seq/check-register raw-history)]
      (is (false? (:valid? res)))
      (is (seq (:failures res)))))
  )


(deftest sequential-ryw-fail
  (is (false? (:valid? (seq/check-register
                         [(op/invoke 1 :write 1) (op/ok 1 :write 1)
                          (op/invoke 1 :write 2) (op/ok 1 :write 2)
                          (op/invoke 1 :read nil) (op/ok 1 :read 1)])))))

(deftest sequential-monotonic-read-fail
  (is (false? (:valid? (seq/check-register
                         [(op/invoke 1 :write 1) (op/ok 1 :write 1)
                          (op/invoke 1 :write 2) (op/ok 1 :write 2)
                          (op/invoke 2 :read nil) (op/ok 2 :read 1)
                          (op/invoke 2 :read nil) (op/ok 2 :read 2)
                          (op/invoke 2 :read nil) (op/ok 2 :read 1)])))))

(deftest sequential-monotonic-write-fail
  (is (false? (:valid? (seq/check-register
                         [(op/invoke 1 :write 1) (op/ok 1 :write 1)
                          (op/invoke 1 :write 2) (op/ok 1 :write 2)
                          (op/invoke 2 :read nil) (op/ok 2 :read 2)
                          (op/invoke 2 :read nil) (op/ok 2 :read 1)])))))

(deftest sequential-ok
  (is (:valid? (seq/check-register
                 [(op/invoke 1 :write 1) (op/ok 1 :write 1)
                  (op/invoke 1 :write 2) (op/ok 1 :write 2)
                  (op/invoke 2 :read nil) (op/ok 2 :read 1)
                  (op/invoke 2 :read nil) (op/ok 2 :read 2)
                  (op/invoke 3 :read nil) (op/ok 3 :read 1)]))))

(deftest sequential-single-order-fail
  (is (false? (:valid? (seq/check-register
                         [(op/invoke 1 :write 1) (op/ok 1 :write 1)
                          (op/invoke 2 :write 2) (op/ok 2 :write 2)
                          (op/invoke 3 :read nil) (op/ok 3 :read 2)
                          (op/invoke 3 :read nil) (op/ok 3 :read 1)
                          (op/invoke 4 :read nil) (op/ok 4 :read 1)
                          (op/invoke 4 :read nil) (op/ok 4 :read 2)])))))

;; ---------------------------
;; Illegal / validation cases
;; ---------------------------

(deftest illegal-op-type
  (is (thrown? clojure.lang.ExceptionInfo
               (seq/check-register [(op/invoke 1 :write 1)
                          (assoc (op/ok 1 :write 1) :type :something-strange)]))))

(deftest illegal-op-function
  (is (thrown? clojure.lang.ExceptionInfo
               (seq/check-register [(op/invoke 1 :write 1)
                          (op/ok 1 :write 1)
                          (assoc (op/ok 1 :read nil) :f :cas)]))))

(deftest illegal-op-indeterminate-read
  (is (thrown? clojure.lang.ExceptionInfo
               (seq/check-register [(op/invoke 1 :read nil)
                          (op/info 1 :read nil)]))))

(deftest illegal-non-unique-write-value
  (is (thrown? clojure.lang.ExceptionInfo
               (seq/check-register [(op/invoke 1 :write 1)
                          (op/fail 1 :write 1)
                          (op/invoke 1 :write 1)
                          (op/ok 1 :write 1)]))))

;; ----------
;; Edge cases
;; ----------

(deftest edge-empty-history
  (is (= {:valid? true} (seq/check-register []))))

(deftest edge-only-invoke-fail
  (is (= {:valid? true}
         (seq/check-register [(op/invoke 0 :read nil)
                    (op/fail 0 :write 1)]))))

(deftest edge-only-reads-of-nil
  (is (= {:valid? true}
         (seq/check-register [(op/ok 0 :read :nil)
                    (op/ok 1 :read :nil)]))))


(deftest legal-kv-history-per-key-sequential
  (def history )
  )

;ClusterClient should generate history
;Add nemesis events to history

