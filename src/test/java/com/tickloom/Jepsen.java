package com.tickloom;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import com.tickloom.checkers.ConsistencyChecker;

// ----- Clojure/Jepsen interop helpers -----
public class Jepsen {
    static final IFn REQUIRE = Clojure.var("clojure.core", "require");
    static final IFn ENTRY_ANALYZE_Q;
    static final IFn ENTRY_ANALYZE_WITH_MODEL_Q;
    static final IFn ENTRY_ANALYZE_KV_Q;

    static final IFn ENTRY_ANALYZE_REGISTER_SEQ_Q;
    static final IFn SHUTDOWN_AGENTS;

    static {
        REQUIRE.invoke(Clojure.read("com.tickloom.jepsen.jepsencaller"));
        ENTRY_ANALYZE_Q = Clojure.var("com.tickloom.jepsen.jepsencaller", "analyze?");
        ENTRY_ANALYZE_WITH_MODEL_Q = Clojure.var("com.tickloom.jepsen.jepsencaller", "analyze-with-model?");
        ENTRY_ANALYZE_KV_Q = Clojure.var("com.tickloom.jepsen.jepsencaller", "analyze-kv?");
        ENTRY_ANALYZE_REGISTER_SEQ_Q = Clojure.var("com.tickloom.jepsen.jepsencaller", "check-register-seq");
        SHUTDOWN_AGENTS = Clojure.var("clojure.core", "shutdown-agents");
    }

    public static boolean check(String edn, String modeKeyword, String builtinModelKeyword, String optsEdn) {
        Object valid = ENTRY_ANALYZE_Q.invoke(edn,
                modeKeyword == null ? "linearizable" : modeKeyword,
                builtinModelKeyword,
                optsEdn == null ? "{:time-limit 60000}" : optsEdn);
        return Boolean.TRUE.equals(valid);
    }

    public static boolean checkWithModel(String edn, String modeKeyword, Object modelObject, String optsEdn) {
        Object valid = ENTRY_ANALYZE_WITH_MODEL_Q.invoke(edn,
                modeKeyword == null ? "linearizable" : modeKeyword,
                modelObject,
                optsEdn == null ? "{:time-limit 60000}" : optsEdn);
        return Boolean.TRUE.equals(valid);
    }

    public static boolean checkIndependentKV(String edn, String modeKeyword, String optsEdn) {
        Object valid = ENTRY_ANALYZE_KV_Q.invoke(edn,
                modeKeyword == null ? "linearizable" : modeKeyword,
                optsEdn == null ? "{:time-limit 60000}" : optsEdn);
        return Boolean.TRUE.equals(valid);
    }

    public static boolean checkRegisterSequential(String edn) {
        Object result = ENTRY_ANALYZE_REGISTER_SEQ_Q.invoke(edn);
        System.out.println("result = " + result);
        PersistentArrayMap map = (PersistentArrayMap) result;
        return Boolean.TRUE.equals(map.get(Keyword.intern("valid?")));
    }

    // Removed checkWithCounter; prefer passing a model implementing knossos.model.Model to checkWithModel

    public static boolean checkLinearizableRegister(String edn) {
        return check(edn, "linearizable", "register", "{:time-limit 60000}");
    }

    static void shutdownAgents() {
        SHUTDOWN_AGENTS.invoke();
    }

    
    // === NEW METHODS USING ENHANCED INFRASTRUCTURE ===
    
    /**
     * Check consistency using the new infrastructure.
     * @param historyEdn EDN string of the history
     * @param model Model type ("register" or "kv")
     * @param consistencyType Consistency type ("linearizable" or "sequential")
     * @return true if the history satisfies the consistency model
     */
    public static boolean checkConsistency(String historyEdn, String model, String consistencyType) {
        if ("linearizable".equals(consistencyType)) {
            return ConsistencyChecker.checkLinearizable(historyEdn, model);
        } else if ("sequential".equals(consistencyType)) {
            return ConsistencyChecker.checkSequential(historyEdn, model);
        } else {
            throw new IllegalArgumentException("Unknown consistency type: " + consistencyType);
        }
    }
    
    /**
     * Check consistency with custom model using the new infrastructure.
     */
    public static boolean checkConsistencyWithModel(String historyEdn, Object customModel, String consistencyType) {
        if ("linearizable".equals(consistencyType)) {
            return ConsistencyChecker.checkLinearizableWithModel(historyEdn, customModel);
        } else if ("sequential".equals(consistencyType)) {
            return ConsistencyChecker.checkSequentialWithModel(historyEdn, customModel);
        } else {
            throw new IllegalArgumentException("Unknown consistency type: " + consistencyType);
        }
    }
}