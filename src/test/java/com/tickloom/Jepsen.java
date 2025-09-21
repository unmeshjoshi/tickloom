package com.tickloom;

import clojure.java.api.Clojure;
import clojure.lang.IFn;

// ----- Clojure/Jepsen interop helpers -----
public class Jepsen {
    static final IFn REQUIRE = Clojure.var("clojure.core", "require");
    static final IFn ENTRY_ANALYZE_Q;
    static final IFn ENTRY_ANALYZE_INDEPENDENT_Q;
    static final IFn SHUTDOWN_AGENTS;

    static {
        REQUIRE.invoke(Clojure.read("com.tickloom.jepsen.jepsencaller"));
        ENTRY_ANALYZE_Q = Clojure.var("com.tickloom.jepsen.jepsencaller", "analyze?");
        ENTRY_ANALYZE_INDEPENDENT_Q = Clojure.var("com.tickloom.jepsen.jepsencaller", "analyze-independent?");
        SHUTDOWN_AGENTS = Clojure.var("clojure.core", "shutdown-agents");
    }

    public static boolean check(String edn, String modeKeyword, String builtinModelKeyword) {
        Object valid = ENTRY_ANALYZE_Q.invoke(edn,
                modeKeyword,
                builtinModelKeyword,
                "{:time-limit 60000}");
        return Boolean.TRUE.equals(valid);
    }

    public static boolean checkIndependent(String edn, String modeKeyword, String builtinModelKeyword) {
        Object valid = ENTRY_ANALYZE_INDEPENDENT_Q.invoke(edn,
                modeKeyword,
                builtinModelKeyword,
                "{:time-limit 60000}");
        return Boolean.TRUE.equals(valid);
    }

    // Removed checkWithCounter; prefer passing a model implementing knossos.model.Model to checkWithModel

    static void shutdownAgents() {
        SHUTDOWN_AGENTS.invoke();
    }
}