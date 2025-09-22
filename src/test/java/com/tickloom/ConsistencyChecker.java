package com.tickloom;

import clojure.java.api.Clojure;
import clojure.lang.IFn;

// ----- Clojure/Jepsen interop helpers -----
public class ConsistencyChecker {
    static final IFn REQUIRE = Clojure.var("clojure.core", "require");
    static final IFn ENTRY_ANALYZE_Q;
    static final IFn ENTRY_ANALYZE_INDEPENDENT_Q;
    static final IFn SHUTDOWN_AGENTS;

    static {
        REQUIRE.invoke(Clojure.read("com.tickloom.checkers.consistency-checker"));
        ENTRY_ANALYZE_Q = Clojure.var("com.tickloom.checkers.consistency-checker", "analyze?");
        ENTRY_ANALYZE_INDEPENDENT_Q = Clojure.var("com.tickloom.checkers.consistency-checker", "analyze-independent?");
        SHUTDOWN_AGENTS = Clojure.var("clojure.core", "shutdown-agents");
    }

    public static boolean check(String edn, ConsistencyProperty consistencyProperty, DataModel model) {
        Object valid = ENTRY_ANALYZE_Q.invoke(edn,
                consistencyProperty.getKeyword(),
                model.getKeyword(),
                "{:time-limit 60000}");
        return Boolean.TRUE.equals(valid);
    }

    public static boolean checkIndependent(String edn, ConsistencyProperty consistencyProperty, DataModel model) {
        Object valid = ENTRY_ANALYZE_INDEPENDENT_Q.invoke(edn,
                consistencyProperty.getKeyword(),
                model.getKeyword(),
                "{:time-limit 60000}");
        return Boolean.TRUE.equals(valid);
    }

    // Removed checkWithCounter; prefer passing a model implementing knossos.model.Model to checkWithModel

    static void shutdownAgents() {
        SHUTDOWN_AGENTS.invoke();
    }


    /**
     * Supported consistency models for verification
     */
    public enum ConsistencyProperty {
        LINEARIZABILITY("linearizable"),
        SEQUENTIAL_CONSISTENCY("sequential");

        private final String keyword;

        ConsistencyProperty(String keyword) {
            this.keyword = keyword;
        }

        public String getKeyword() {
            return keyword;
        }

        @Override
        public String toString() {
            return keyword;
        }
    }

    /**
     * Built-in data structure models supported by the checker.
     * The linearizability checker uses all the supported models by jepsen framework.
     * The sequential consistency checker only supports the register model as of now.
     */
    public enum DataModel {
        REGISTER("register"),
        CAS_REGISTER("cas-register"),
        SET("set"),
        MUTEX("mutex"),
        UNORDERED_QUEUE("unordered-queue"),
        FIFO_QUEUE("fifo-queue");

        private final String keyword;

        DataModel(String keyword) {
            this.keyword = keyword;
        }

        public String getKeyword() {
            return keyword;
        }

        @Override
        public String toString() {
            return keyword;
        }
    }
}