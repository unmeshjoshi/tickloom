package com.tickloom.history;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.IPersistentVector;
import clojure.lang.Keyword;
import clojure.lang.PersistentVector;
import com.tickloom.ProcessId;

import java.util.Arrays;
import java.util.List;

//Wrapper
public class JepsenHistory {
    private final IFn create;
    private final IFn invoke;
    private final IFn edn_read;
    private final IFn ok;
    private final IFn fail;
    private final IFn info;
    private final IFn pr_str;

    private IPersistentVector history;

    public Keyword createKeyword(String name) {
        return Keyword.intern(name.toLowerCase());
    }

    public static IPersistentVector tuple(Object... vals) {
        return PersistentVector.create(vals);
    }



    public JepsenHistory() {
        loadNamespaces(Arrays.asList("com.tickloom.checkers.jepsen-history",
                "clojure.edn", "clojure.core"));
        create = Clojure.var("com.tickloom.checkers.jepsen-history", "create");
        invoke = Clojure.var("com.tickloom.checkers.jepsen-history", "invoke");
        ok = Clojure.var("com.tickloom.checkers.jepsen-history", "ok");
        fail = Clojure.var("com.tickloom.checkers.jepsen-history", "fail");
        info = Clojure.var("com.tickloom.checkers.jepsen-history", "info");
        edn_read = Clojure.var("clojure.edn", "read-string");
        pr_str = Clojure.var("clojure.core", "pr-str");

        this.history = (IPersistentVector) create.invoke();

    }

    public JepsenHistory invoke(ProcessId processId, Op op, Object value) {
        //make history final and provide update method to keep JepsenHistory class immutable.
        history = (IPersistentVector) invoke.invoke(history, processId.id(), processId.name(), createKeyword(op.name()), value);
        return this;
    }

    private static void loadNamespaces(List<String> namespaces) {
        IFn REQUIRE = Clojure.var("clojure.core", "require");
        namespaces.forEach(namespace -> REQUIRE.invoke(Clojure.read(namespace)));
    }

    //utility for converting edn strings in tests.
    public IPersistentVector toEdn(String ednString) {
        return (IPersistentVector) edn_read.invoke(ednString);
    }

    public boolean matches(String ednString) {
        IPersistentVector expectedHistory = toEdn(ednString);
        return history.equals(expectedHistory);
    }

    public JepsenHistory ok(ProcessId processId, Op op, Object value) {
        //make history final and provide update method to keep JepsenHistory class immutable.
        history = (IPersistentVector) ok.invoke(history, processId.id(), processId.name(), createKeyword(op.name()), value);
        return this;
    }

    public JepsenHistory fail(ProcessId processId, Op op, Object value) {
        //make history final and provide update method to keep JepsenHistory class immutable.
        history = (IPersistentVector) fail.invoke(history, processId.id(),  processId.name(), createKeyword(op.name()), value);
        return this;
    }

    public JepsenHistory info(ProcessId processId, Op op, Object value) {
        //make history final and provide update method to keep JepsenHistory class immutable.
        history = (IPersistentVector) info.invoke(history, processId.id(),  processId.name(), createKeyword(op.name()), value);
        return this;
    }

    public String getEdnString() {
        return (String) pr_str.invoke(history);
    }
}
