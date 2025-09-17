(ns com.tickloom.checkers.jepsen-history)

(defn create
  []
  [])

(defn invoke
  [history process f value]
  (conj history {:process process :type :invoke :f f :value value}))

(defn ok
  [history process f value]
  (conj history {:process process :type :ok :f f :value value}))

(defn fail
  [history process f value]
  (conj history {:process process :type :fail :f f :value value}))

(defn info
  [history process f value]
  (conj history {:process process :type :info :f f :value value}))