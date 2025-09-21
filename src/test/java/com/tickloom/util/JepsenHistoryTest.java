package com.tickloom.util;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.IPersistentVector;
import com.tickloom.ProcessId;
import com.tickloom.history.Op;
import com.tickloom.history.JepsenHistory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JepsenHistoryTest {

    @Test
    public void testJepsenHistoryCreation() {
        IFn REQUIRE = Clojure.var("clojure.core", "require");
        REQUIRE.invoke(Clojure.read("com.tickloom.checkers.jepsen-history"));

        IFn create = Clojure.var("com.tickloom.checkers.jepsen-history", "create");
        IPersistentVector history = (IPersistentVector) create.invoke();
        assertEquals(0, history.length());
    }

    @Test
    public void testInvokeEvent() {
        JepsenHistory history = new JepsenHistory();

        //process, type, key, name
        history.invoke(ProcessId.of("client-1"), Op.WRITE, "name");

        assertTrue(history.matches("[{:process 0 :process-name \"client-1\" :type :invoke :f :write :value \"name\"}]"));
    }

    @Test
    public void testInvokeEventWithKVHistoryValue() {
        JepsenHistory history = new JepsenHistory();

        //process, type, key, name
        history.invoke(ProcessId.of("client-1"), Op.WRITE, JepsenHistory.tuple("key", "name"));

        assertTrue(history.matches("[{:process 0, :process-name \"client-1\", :type :invoke, :f :write, :value [\"key\" \"name\"]}]"));
    }

    @Test
    public void testOkEvent() {
        JepsenHistory history = new JepsenHistory();
        history.ok(ProcessId.of("client-1"), Op.WRITE, JepsenHistory.tuple("key", "name"));
        assertTrue(history.matches("[{:process 0 :process-name \"client-1\" :type :ok :f :write :value [\"key\" \"name\"]}]"));
    }

    @Test
    public void testFailEvent() {
        JepsenHistory history = new JepsenHistory();
        history.fail(ProcessId.of("client-1"), Op.WRITE, JepsenHistory.tuple("key", "name"));
        assertTrue(history.matches("[{:process 0 :process-name \"client-1\" :type :fail :f :write :value [\"key\" \"name\"]}]"));
    }

    @Test
    public void testInfoEvent() {
        JepsenHistory history = new JepsenHistory();
        history.info(ProcessId.of("client-1"), Op.WRITE, JepsenHistory.tuple("key", "name"));
        assertTrue(history.matches("[{:process 0 :process-name \"client-1\" :type :info :f :write :value [\"key\" \"name\"]}]"));
    }

    @Test
    public void testHistoryWithMultipleEvents() {
        JepsenHistory history = new JepsenHistory();
        ProcessId client1 = ProcessId.of("client-1");
        ProcessId client2 = ProcessId.of("client-2");
        history.invoke(client1, Op.WRITE, JepsenHistory.tuple("key", "name"))
                .info(client1, Op.WRITE, JepsenHistory.tuple("key", "name"))
                .invoke(client2, Op.WRITE, JepsenHistory.tuple("key2", "value2"))
                .ok(client2, Op.WRITE, JepsenHistory.tuple("key2", "value2"));

        assertTrue(history.matches("[" +
                "{:process 0 :process-name \"client-1\" :type :invoke :f :write :value [\"key\" \"name\"]}" +
                "{:process 0 :process-name \"client-1\" :type :info :f :write :value [\"key\" \"name\"]}" +
                "{:process 1 :process-name \"client-2\" :type :invoke :f :write :value [\"key2\" \"value2\"]}" +
                "{:process 1 :process-name \"client-2\" :type :ok :f :write :value [\"key2\" \"value2\"]}" +
                "]"));
    }
}
