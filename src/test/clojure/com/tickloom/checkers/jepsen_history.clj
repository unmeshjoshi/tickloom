(ns com.tickloom.checkers.jepsen-history)

(defn create
  []
  [])

(defn invoke
  [history process-id process-name f value]
  (conj history {:process process-id :process-name process-name :type :invoke :f f :value value}))

(defn ok
  [history process-id process-name f value]
  (conj history {:process process-id :process-name process-name :type :ok :f f :value value}))

(defn fail
  [history process-id process-name f value]
  (conj history {:process process-id :process-name process-name :type :fail :f f :value value}))

(defn info
  [history process-id process-name f value]
  (conj history {:process process-id :process-name process-name :type :info :f f :value value}))