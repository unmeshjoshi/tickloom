package com.tickloom.util;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.IPersistentVector;
import clojure.lang.Keyword;
import clojure.lang.PersistentVector;
import com.tickloom.history.Op;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
        private final IFn ok;
        private final IFn fail;
        private final IFn info;

        private IPersistentVector history;

        public Keyword createKeyword(String name) {
            return Keyword.intern(name.toLowerCase());
        }

        public static IPersistentVector tuple(Object... vals) {
            return PersistentVector.create(vals);
        }

        public JepsenHistory() {
            loadNamespaces(Arrays.asList("com.tickloom.checkers.jepsen-history",
                    "clojure.edn"));
            create = Clojure.var("com.tickloom.checkers.jepsen-history", "create");
            invoke = Clojure.var("com.tickloom.checkers.jepsen-history", "invoke");
            ok = Clojure.var("com.tickloom.checkers.jepsen-history", "ok");
            fail = Clojure.var("com.tickloom.checkers.jepsen-history", "fail");
            info = Clojure.var("com.tickloom.checkers.jepsen-history", "info");
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

        public JepsenHistory ok(String processId, Op op, Object value) {
            //make history final and provide update method to keep JepsenHistory class immutable.
            history = (IPersistentVector) ok.invoke(history, processId, createKeyword(op.name()), value);
            return this;
        }

        public JepsenHistory fail(String processId, Op op, Object value) {
            //make history final and provide update method to keep JepsenHistory class immutable.
            history = (IPersistentVector) fail.invoke(history, processId, createKeyword(op.name()), value);
            return this;
        }

        public JepsenHistory info(String processId, Op op, Object value) {
            //make history final and provide update method to keep JepsenHistory class immutable.
            history = (IPersistentVector) info.invoke(history, processId, createKeyword(op.name()), value);
            return this;
        }
    }

    @Test
    public void testInvokeEvent() {
        JepsenHistory history = new JepsenHistory();

        //process, type, key, value
        history.invoke("client-1", Op.WRITE, "value");

        assertTrue(history.matches("[{:process \"client-1\" :type :invoke :f :write :value \"value\"}]"));
    }

    @Test
    public void testInvokeEventWithKVHistoryValue() {
        JepsenHistory history = new JepsenHistory();

        //process, type, key, value
        history.invoke("client-1", Op.WRITE, JepsenHistory.tuple("key", "value"));

        assertTrue(history.matches("[{:process \"client-1\" :type :invoke :f :write :value [\"key\" \"value\"]}]"));
    }

    @Test
    public void testOkEvent() {
        JepsenHistory history = new JepsenHistory();
        history.ok("client-1", Op.WRITE, JepsenHistory.tuple("key", "value"));
        assertTrue(history.matches("[{:process \"client-1\" :type :ok :f :write :value [\"key\" \"value\"]}]"));
    }

    @Test
    public void testFailEvent() {
        JepsenHistory history = new JepsenHistory();
        history.fail("client-1", Op.WRITE, JepsenHistory.tuple("key", "value"));
        assertTrue(history.matches("[{:process \"client-1\" :type :fail :f :write :value [\"key\" \"value\"]}]"));
    }

    @Test
    public void testInfoEvent() {
        JepsenHistory history = new JepsenHistory();
        history.info("client-1", Op.WRITE, JepsenHistory.tuple("key", "value"));
        assertTrue(history.matches("[{:process \"client-1\" :type :info :f :write :value [\"key\" \"value\"]}]"));
    }

    @Test
    public void testHistoryWithMultipleEvents() {
        JepsenHistory history = new JepsenHistory();
        history.invoke("client-1", Op.WRITE, JepsenHistory.tuple("key", "value"))
                .info("client-1", Op.WRITE, JepsenHistory.tuple("key", "value"))
                .invoke("client-2", Op.WRITE, JepsenHistory.tuple("key2", "value2"))
                .ok("client-2", Op.WRITE, JepsenHistory.tuple("key2", "value2"));

        assertTrue(history.matches("[" +
                "{:process \"client-1\" :type :invoke :f :write :value [\"key\" \"value\"]}" +
                "{:process \"client-1\" :type :info :f :write :value [\"key\" \"value\"]}" +
                "{:process \"client-2\" :type :invoke :f :write :value [\"key2\" \"value2\"]}" +
                "{:process \"client-2\" :type :ok :f :write :value [\"key2\" \"value2\"]}" +
                "]"));
    }
}
