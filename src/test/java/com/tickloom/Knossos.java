package com.tickloom;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Keyword;

// ----- Clojure/Knossos interop helpers -----
public class Knossos {
    static final IFn REQUIRE = Clojure.var("clojure.core", "require");
    static final IFn READ_STRING;
    static final IFn MODEL_REGISTER;
    static final IFn ANALYSIS;
    static final IFn GET;

    static {
        REQUIRE.invoke(Clojure.read("clojure.edn"));
        REQUIRE.invoke(Clojure.read("knossos.model"));
        REQUIRE.invoke(Clojure.read("knossos.competition"));
        READ_STRING   = Clojure.var("clojure.edn", "read-string");
        MODEL_REGISTER= Clojure.var("knossos.model", "register");
        ANALYSIS      = Clojure.var("knossos.competition", "analysis");
        GET           = Clojure.var("clojure.core", "get");
    }

    static boolean checkLinearizableRegister(String edn) {
        Object history = READ_STRING.invoke(edn);
        Object model   = MODEL_REGISTER.invoke();
        Object opts    = Clojure.read("{:time-limit 60000}"); // 60s cap
        Object result  = ANALYSIS.invoke(model, history, opts);
        Object valid   = ((IFn) GET).invoke(result, Keyword.intern(null, "valid?"));
        return Boolean.TRUE.equals(valid);
    }
}