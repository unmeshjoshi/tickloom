package com.tickloom;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Keyword;

// ----- Clojure/Jepsen interop helpers -----
public class Jepsen {
    static final IFn REQUIRE = Clojure.var("clojure.core", "require");
    static final IFn READ_STRING;
    static final IFn MODEL_REGISTER;
    static final IFn JEPSEN_LINEARIZABLE;
    static final IFn JEPSEN_CHECK;
    static final IFn HASH_MAP;
    static final IFn GET;
    static final IFn SHUTDOWN_AGENTS;

    static {
        REQUIRE.invoke(Clojure.read("clojure.edn"));
        REQUIRE.invoke(Clojure.read("knossos.model"));
        REQUIRE.invoke(Clojure.read("jepsen.checker"));
        READ_STRING = Clojure.var("clojure.edn", "read-string");
        MODEL_REGISTER = Clojure.var("knossos.model", "register");
        JEPSEN_LINEARIZABLE = Clojure.var("jepsen.checker", "linearizable");
        JEPSEN_CHECK = Clojure.var("jepsen.checker", "check");
        HASH_MAP = Clojure.var("clojure.core", "hash-map");
        GET = Clojure.var("clojure.core", "get");
        SHUTDOWN_AGENTS = Clojure.var("clojure.core", "shutdown-agents");
    }

    static boolean checkLinearizableRegister(String edn) {
        Object history = READ_STRING.invoke(edn);
        Object model = MODEL_REGISTER.invoke();
        Object checkerOpts = HASH_MAP.invoke(Keyword.intern(null, "model"), model);
        Object checker = JEPSEN_LINEARIZABLE.invoke(checkerOpts);
        Object test = HASH_MAP.invoke(Keyword.intern(null, "model"), model);
        Object opts = HASH_MAP.invoke();
        Object result = JEPSEN_CHECK.invoke(checker, test, history, opts);
        Object valid = ((IFn) GET).invoke(result, Keyword.intern(null, "valid?"));
        return Boolean.TRUE.equals(valid);
    }

    static void shutdownAgents() {
        SHUTDOWN_AGENTS.invoke();
    }
}