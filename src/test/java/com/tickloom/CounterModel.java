package com.tickloom;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import clojure.lang.IPersistentMap;
import knossos.model.Model;

/**
 * CounterModel implements Knossos' Model protocol from Java by exposing a step(Object op) method
 * which accepts a Clojure map with keys :f and :value.
 *
 * Operations supported:
 *  - {:f :inc}
 *  - {:f :add, :value int}
 *  - {:f :read} with completion value expected to equal current counter
 *
 * Returning this indicates a consistent step; returning null signals inconsistency.
 */
public class CounterModel implements Model {
    private final int value;
    private static final IFn REQUIRE = Clojure.var("clojure.core", "require");
    private static final IFn INCONSISTENT;

    static {
        REQUIRE.invoke(Clojure.read("knossos.model"));
        INCONSISTENT = Clojure.var("knossos.model", "inconsistent");
    }

    public CounterModel(int initial) { this.value = initial; }

    @Override
    public Object step(Object op) {
        if (!(op instanceof IPersistentMap m)) return null;
        Object f = m.valAt(Keyword.intern(null, "f"));
        if (Keyword.intern(null, "inc").equals(f)) {
            return new CounterModel(this.value + 1);
        } else if (Keyword.intern(null, "add").equals(f)) {
            Object v = m.valAt(Keyword.intern(null, "value"));
            int d = (v instanceof Number) ? ((Number) v).intValue() : 0;
            return new CounterModel(this.value + d);
        } else if (Keyword.intern(null, "read").equals(f)) {
            Object v = m.valAt(Keyword.intern(null, "value"));
            int observed = (v instanceof Number) ? ((Number) v).intValue() : 0;
            return (observed == this.value) ? this : INCONSISTENT.invoke("counter read mismatch");
        }
        return null;
    }

    public int current() { return value; }
}


