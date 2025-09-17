(ns com.tickloom.checkers.jepsen-history)

(defn create
  []
  [])

(defn invoke
  [history process f value]
  (conj history {:process process :type :invoke :f f :value value}))