package com.tickloom;

import com.tickloom.algorithms.replication.quorum.QuorumSimulationRunner;
import com.tickloom.history.History;
import com.tickloom.history.Op;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import clojure.java.api.Clojure;
import clojure.lang.IFn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JepsenTest {
    @AfterAll
    public static void shutDownAgents() {
        Jepsen.shutdownAgents();
    }

    @Test
    void shouldBeLinearizableForRegister() {
        // Process p0 writes "3", p1 reads "3" -> linearizable
        History h = new History();
        byte[] k = "k".getBytes(StandardCharsets.UTF_8);
        byte[] v3 = "3".getBytes(StandardCharsets.UTF_8);

        h.invoke("p0", Op.WRITE, k, v3);
        h.ok    ("p0", Op.WRITE, k, v3);

        h.invoke("p1", Op.READ,  k, null);
        h.ok    ("p1", Op.READ,  k, v3);

        String edn = h.toEdn();
        boolean ok = Jepsen.checkLinearizableRegister(edn);
        assertTrue(ok, "Expected history to be linearizable");
    }

    @Test
    void shouldBeNonLinearizableForRegister() {
        // p0 writes "1", p1 reads "2" with no concurrent write of "2" -> non-linearizable
        History h = new History();
        byte[] k = "k".getBytes(StandardCharsets.UTF_8);
        byte[] v1 = "1".getBytes(StandardCharsets.UTF_8);
        byte[] v2 = "2".getBytes(StandardCharsets.UTF_8);

        h.invoke("p0", Op.WRITE, k, v1);
        h.ok    ("p0", Op.WRITE, k, v1);

        h.invoke("p1", Op.READ,  k, null);
        h.ok    ("p1", Op.READ,  k, v2);

        String edn = h.toEdn();
        boolean ok = Jepsen.checkLinearizableRegister(edn);
        assertFalse(ok, "Expected history to be NON-linearizable");
    }

    @Test
    public void shouldBeLinearizableForStrictQuorumImplementation() throws IOException {
        SimulationRunner runner = new QuorumSimulationRunner(123L);
        History history = runner.runAndGetHistory(1000);
        assertTrue(Jepsen.checkLinearizableRegister(history.toEdn()));
    }

    @Test
    void shouldBeValidForBuiltinModelsWithEmptyHistory() {
        String edn = "[]";
        String[] models = new String[]{
                "register",
                "cas-register",
                "mutex",
                "fifo-queue",
                "unordered-queue",
                "set"
        };
        for (String model : models) {
            boolean ok = Jepsen.check(edn, "linearizable", model, null);
            assertTrue(ok, "Empty history should be valid for model: " + model);
        }
    }

    @Test
    void shouldBeValidForRegisterWithCustomModelObject() {
        // Same history as shouldBeLinearizable_register
        History h = new History();
        byte[] k = "k".getBytes(StandardCharsets.UTF_8);
        byte[] v3 = "3".getBytes(StandardCharsets.UTF_8);

        h.invoke("p0", Op.WRITE, k, v3);
        h.ok    ("p0", Op.WRITE, k, v3);

        h.invoke("p1", Op.READ,  k, null);
        h.ok    ("p1", Op.READ,  k, v3);

        String edn = h.toEdn();

        // Build a concrete Knossos model instance via Clojure interop
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("knossos.model"));
        IFn registerCtor = Clojure.var("knossos.model", "register");
        Object modelObject = registerCtor.invoke();

        boolean ok = Jepsen.checkWithModel(edn, "linearizable", modelObject, null);
        assertTrue(ok, "Expected history to be linearizable with custom model object");
    }

    // ---- Examples per built-in model ----

    @Test
    void shouldBeValidForCasRegisterWriteThenRead() {
        // Simple write/read sequence for CAS register (supports :write and :read)
        String edn = "["
                + "{:type :invoke, :f :write, :process 0, :time 0, :index 0, :value \"v1\"},"
                + "{:type :ok,     :f :write, :process 0, :time 1, :index 1, :value \"v1\"},"
                + "{:type :invoke, :f :read,  :process 1, :time 2, :index 2, :value nil},"
                + "{:type :ok,     :f :read,  :process 1, :time 3, :index 3, :value \"v1\"}"
                + "]";
        boolean ok = Jepsen.check(edn, "linearizable", "cas-register", null);
        assertTrue(ok);
    }

    @Test
    void shouldBeValidForMutexAcquireRelease() {
        String edn = "["
                + "{:type :invoke, :f :acquire, :process 0, :time 0, :index 0, :value nil},"
                + "{:type :ok,     :f :acquire, :process 0, :time 1, :index 1, :value true},"
                + "{:type :invoke, :f :release, :process 0, :time 2, :index 2, :value nil},"
                + "{:type :ok,     :f :release, :process 0, :time 3, :index 3, :value true},"
                + "{:type :invoke, :f :acquire, :process 1, :time 4, :index 4, :value nil},"
                + "{:type :ok,     :f :acquire, :process 1, :time 5, :index 5, :value true},"
                + "{:type :invoke, :f :release, :process 1, :time 6, :index 6, :value nil},"
                + "{:type :ok,     :f :release, :process 1, :time 7, :index 7, :value true}"
                + "]";
        boolean ok = Jepsen.check(edn, "linearizable", "mutex", null);
        assertTrue(ok);
    }

    @Test
    void shouldBeValidForFifoQueueEnqueueDequeue() {
        String edn = "["
                + "{:type :invoke, :f :enqueue, :process 0, :time 0, :index 0, :value \"x\"},"
                + "{:type :ok,     :f :enqueue, :process 0, :time 1, :index 1, :value \"x\"},"
                + "{:type :invoke, :f :dequeue, :process 1, :time 2, :index 2, :value nil},"
                + "{:type :ok,     :f :dequeue, :process 1, :time 3, :index 3, :value \"x\"}"
                + "]";
        boolean ok = Jepsen.check(edn, "linearizable", "fifo-queue", null);
        assertTrue(ok);
    }

    @Test
    void shouldBeValidForUnorderedQueueEnqueueDequeue() {
        String edn = "["
                + "{:type :invoke, :f :enqueue, :process 0, :time 0, :index 0, :value \"a\"},"
                + "{:type :ok,     :f :enqueue, :process 0, :time 1, :index 1, :value \"a\"},"
                + "{:type :invoke, :f :dequeue, :process 1, :time 2, :index 2, :value nil},"
                + "{:type :ok,     :f :dequeue, :process 1, :time 3, :index 3, :value \"a\"}"
                + "]";
        boolean ok = Jepsen.check(edn, "linearizable", "unordered-queue", null);
        assertTrue(ok);
    }

    @Test
    void shouldBeValidForSetAddThenRead() {
        String edn = "["
                + "{:type :invoke, :f :add,  :process 0, :time 0, :index 0, :value \"e1\"},"
                + "{:type :ok,     :f :add,  :process 0, :time 1, :index 1, :value \"e1\"},"
                + "{:type :invoke, :f :read, :process 1, :time 2, :index 2, :value nil},"
                + "{:type :ok,     :f :read, :process 1, :time 3, :index 3, :value #{\"e1\"}}"
                + "]";
        boolean ok = Jepsen.check(edn, "linearizable", "set", null);
        assertTrue(ok);
    }

    @Test
    void shouldBeValidForCustomMutexModel() {
        // Build mutex model object in Java via clojure interop
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("knossos.model"));
        IFn mutexCtor = Clojure.var("knossos.model", "mutex");
        Object modelObject = mutexCtor.invoke();

        String edn = "["
                + "{:type :invoke, :f :acquire, :process 0, :time 0, :index 0, :value nil},"
                + "{:type :ok,     :f :acquire, :process 0, :time 1, :index 1, :value true},"
                + "{:type :invoke, :f :release, :process 0, :time 2, :index 2, :value nil},"
                + "{:type :ok,     :f :release, :process 0, :time 3, :index 3, :value true}"
                + "]";
        boolean ok = Jepsen.checkWithModel(edn, "linearizable", modelObject, null);
        assertTrue(ok);
    }

    @Test
    void shouldBeValidForCustomJavaCounterModel() {
        CounterModel cm = new CounterModel(0);
        String edn = "["
                + "{:type :invoke, :f :inc,  :process 0, :time 0, :index 0, :value nil},"
                + "{:type :ok,     :f :inc,  :process 0, :time 1, :index 1, :value nil},"
                + "{:type :invoke, :f :add,  :process 1, :time 2, :index 2, :value 2},"
                + "{:type :ok,     :f :add,  :process 1, :time 3, :index 3, :value 2},"
                + "{:type :invoke, :f :read, :process 2, :time 4, :index 4, :value nil},"
                + "{:type :ok,     :f :read, :process 2, :time 5, :index 5, :value 3}"
                + "]";
        boolean ok = Jepsen.checkWithModel(edn, "linearizable", cm, null);
        assertTrue(ok);
    }

    @Test
    void shouldBeInvalidForCustomJavaCounterModelWhenReadWrong() {
        CounterModel cm = new CounterModel(0);
        String edn = "["
                + "{:type :invoke, :f :inc,  :process 0, :time 0, :index 0, :value nil},"
                + "{:type :ok,     :f :inc,  :process 0, :time 1, :index 1, :value nil},"
                + "{:type :invoke, :f :add,  :process 1, :time 2, :index 2, :value 2},"
                + "{:type :ok,     :f :add,  :process 1, :time 3, :index 3, :value 2},"
                + "{:type :invoke, :f :read, :process 2, :time 4, :index 4, :value nil},"
                + "{:type :ok,     :f :read, :process 2, :time 5, :index 5, :value 2}"
                + "]";
        boolean ok = Jepsen.checkWithModel(edn, "linearizable", cm, null);
        assertFalse(ok);
    }

    @Test
    void shouldBeInvalidForCustomJavaCounterModelWhenInitialReadNonZero() {
        CounterModel cm = new CounterModel(0);
        String edn = "["
                + "{:type :invoke, :f :read, :process 0, :time 0, :index 0, :value nil},"
                + "{:type :ok,     :f :read, :process 0, :time 1, :index 1, :value 1}"
                + "]";
        boolean ok = Jepsen.checkWithModel(edn, "linearizable", cm, null);
        assertFalse(ok);
    }

    @Test
    void shouldBeValidForKvIndependentPerKey() {
        // Two keys k1,k2 with per-key linearizable sequences
        String edn = "["
                + "{:type :invoke, :f :write, :process 0, :time 0, :index 0, :value [\"k1\" \"v1\"]},"
                + "{:type :ok,     :f :write, :process 0, :time 1, :index 1, :value [\"k1\" \"v1\"]},"
                + "{:type :invoke, :f :read,  :process 1, :time 2, :index 2, :value [\"k1\" nil]},"
                + "{:type :ok,     :f :read,  :process 1, :time 3, :index 3, :value [\"k1\" \"v1\"]},"

                + "{:type :invoke, :f :write, :process 2, :time 4, :index 4, :value [\"k2\" \"a\"]},"
                + "{:type :ok,     :f :write, :process 2, :time 5, :index 5, :value [\"k2\" \"a\"]},"
                + "{:type :invoke, :f :read,  :process 3, :time 6, :index 6, :value [\"k2\" nil]},"
                + "{:type :ok,     :f :read,  :process 3, :time 7, :index 7, :value [\"k2\" \"a\"]}"
                + "]";
        boolean ok = Jepsen.checkIndependentKV(edn, "linearizable", null);
        assertTrue(ok);
    }

    @Test
    void shouldBeInvalidForKvIndependentWhenWrongValueOnOneKey() {
        String edn = "["
                + "{:type :invoke, :f :write, :process 0, :time 0, :index 0, :value [\"k1\" \"v1\"]},"
                + "{:type :ok,     :f :write, :process 0, :time 1, :index 1, :value [\"k1\" \"v1\"]},"
                + "{:type :invoke, :f :read,  :process 1, :time 2, :index 2, :value [\"k1\" nil]},"
                + "{:type :ok,     :f :read,  :process 1, :time 3, :index 3, :value [\"k1\" \"WRONG\"]}"
                + "]";
        boolean ok = Jepsen.checkIndependentKV(edn, "linearizable", null);
        assertFalse(ok);
    }
}