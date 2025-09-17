package com.tickloom.util;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.IPersistentVector;
import clojure.lang.Keyword;
import com.tickloom.history.Op;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JepnsenHistoryTest {

    @Test
    public void testJepsenHistoryCreation() {
        IFn REQUIRE = Clojure.var("clojure.core", "require");
        REQUIRE.invoke(Clojure.read("com.tickloom.checkers.jepsen-history"));

        IFn create = Clojure.var("com.tickloom.checkers.jepsen-history", "create");
        IPersistentVector history = (IPersistentVector) create.invoke();
        assertEquals(0, history.length());
    }

    //Wrapper
    static class JepsenHistory {
        private final IFn create;
        private final IFn invoke;
        private final IFn edn_read;

        private IPersistentVector history;

        public Keyword createKeyword(String name) {
            return Keyword.intern(name.toLowerCase());
        }

        public JepsenHistory() {
            loadNamespaces(Arrays.asList("com.tickloom.checkers.jepsen-history",
                    "clojure.edn"));
            create = Clojure.var("com.tickloom.checkers.jepsen-history", "create");
            invoke = Clojure.var("com.tickloom.checkers.jepsen-history", "invoke");
            edn_read = Clojure.var("clojure.edn", "read-string");

            this.history = (IPersistentVector) create.invoke();

        }

        public JepsenHistory invoke(String processId, Op op, Object value) {
            //make history final and provide update method to keep JepsenHistory class immutable.
            history = (IPersistentVector) invoke.invoke(history, processId, createKeyword(op.name()), value);
            return this;
        }

        private static void loadNamespaces(List<String> namespaces) {
            IFn REQUIRE = Clojure.var("clojure.core", "require");
            namespaces.forEach(namespace -> REQUIRE.invoke(Clojure.read(namespace)));
        }

        //utility for converting edn strings in tests.
        private IPersistentVector toEdn(String ednString) {
            return (IPersistentVector) edn_read.invoke(ednString);
        }

        public boolean matches(String ednString) {
            IPersistentVector expectedHistory = toEdn(ednString);
            return history.equals(expectedHistory);
        }
    }

    @Test
    public void testInvokeEvent() {
        JepsenHistory history = new JepsenHistory();

        //process, type, key, value
        history.invoke("client-1", Op.WRITE, "value");

        assertTrue(history.matches("[{:process \"client-1\" :type :invoke :f :write :value \"value\"}]"));
    }
}
