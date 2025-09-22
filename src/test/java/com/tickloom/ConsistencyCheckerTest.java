package com.tickloom;

import com.tickloom.algorithms.replication.quorum.QuorumSimulationRunner;
import com.tickloom.history.History;
import com.tickloom.history.JepsenHistory;
import com.tickloom.history.Op;
import com.tickloom.util.TestUtils;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConsistencyCheckerTest {
    // Do not shutdown Clojure agents here; other tests may need them in the same JVM

    @Test
    void shouldBeLinearizableForRegister() {
        // Process p0 writes "3", p1 reads "3" -> linearizable
        History h = new History();
        String k = "k";
        String v3 = "3";

        ProcessId clien1 = ProcessId.of("clien1");
        h.invoke(clien1, Op.WRITE, k, JepsenHistory.tuple(k, v3));
        h.ok    (clien1, Op.WRITE, k, JepsenHistory.tuple(k, v3));

        h.invoke(ProcessId.of("1"), Op.READ,  k, JepsenHistory.tuple(k, null));
        h.ok(ProcessId.of("1"), Op.READ,  k, JepsenHistory.tuple(k, v3));

        String edn = h.toEdn();
        System.out.println("edn = " + edn);
        boolean ok = ConsistencyChecker.checkIndependent(edn, "linearizable", "register");
        assertTrue(ok, "Expected history to be linearizable");
    }

    @Test
    void shouldBeNonLinearizableForRegister() {
        // p0 writes "1", p1 reads "2" with no concurrent write of "2" -> non-linearizable
        History h = new History();
        String k = "k";
        String v1 = "1";
        String v2 = "2";

        h.invoke(ProcessId.of("p0"), Op.WRITE, k, v1);
        h.ok    (ProcessId.of("p0"), Op.WRITE, k, v1);

        h.invoke(ProcessId.of("p1"), Op.READ,  k, null);
        h.ok    (ProcessId.of("p1"), Op.READ,  k, v2);

        String edn = h.toEdn();
        boolean ok = ConsistencyChecker.check(edn, "linearizable", "register");
        assertFalse(ok, "Expected history to be NON-linearizable");
    }

    @Test
    public void shouldBeLinearizableForStrictQuorumImplementation() throws IOException {
        SimulationRunner runner = new QuorumSimulationRunner(123L);
        History history = runner.runAndGetHistory(25);
        String edn = history.toEdn();
        TestUtils.writeEdnFile("linearizable-with-strict-quorum-history.edn", edn);
        assertTrue(ConsistencyChecker.checkIndependent(edn, "linearizable", "register"));
    }

    @Test
    void shouldBeValidForBuiltinModelsWithEmptyHistory() {
        String edn = "[]";
        String[] models = new String[]{
                "register",
                "cas-register",
                "set"
        };
        for (String model : models) {
            boolean ok = ConsistencyChecker.check(edn, "linearizable", model);
            assertTrue(ok, "Empty history should be valid for model: " + model);
        }
    }

    // ---- Examples per built-in model ----

    @Test
    void shouldBeValidForCasRegisterWriteThenRead() {
        // Simple write/read sequence for CAS register (supports :write and :read)
        String edn = "["
                + "{:type :invoke, :f :write, :process 0, :time 0, :index 0, :name \"v1\"},"
                + "{:type :ok,     :f :write, :process 0, :time 1, :index 1, :name \"v1\"},"
                + "{:type :invoke, :f :read,  :process 1, :time 2, :index 2, :name nil},"
                + "{:type :ok,     :f :read,  :process 1, :time 3, :index 3, :name \"v1\"}"
                + "]";
        boolean ok = ConsistencyChecker.check(edn, "linearizable", "cas-register");
        assertTrue(ok);
    }

    @Test
    void shouldBeValidForMutexAcquireRelease() {
        String edn = "["
                + "{:type :invoke, :f :acquire, :process 0, :time 0, :index 0, :name nil},"
                + "{:type :ok,     :f :acquire, :process 0, :time 1, :index 1, :name true},"
                + "{:type :invoke, :f :release, :process 0, :time 2, :index 2, :name nil},"
                + "{:type :ok,     :f :release, :process 0, :time 3, :index 3, :name true},"
                + "{:type :invoke, :f :acquire, :process 1, :time 4, :index 4, :name nil},"
                + "{:type :ok,     :f :acquire, :process 1, :time 5, :index 5, :name true},"
                + "{:type :invoke, :f :release, :process 1, :time 6, :index 6, :name nil},"
                + "{:type :ok,     :f :release, :process 1, :time 7, :index 7, :name true}"
                + "]";
        boolean ok = ConsistencyChecker.check(edn, "linearizable", "mutex");
        assertTrue(ok);
    }

    @Test
    void shouldBeValidForFifoQueueEnqueueDequeue() {
        String edn = "["
                + "{:type :invoke, :f :enqueue, :process 0, :time 0, :index 0, :name \"x\"},"
                + "{:type :ok,     :f :enqueue, :process 0, :time 1, :index 1, :name \"x\"},"
                + "{:type :invoke, :f :dequeue, :process 1, :time 2, :index 2, :name nil},"
                + "{:type :ok,     :f :dequeue, :process 1, :time 3, :index 3, :name \"x\"}"
                + "]";
        boolean ok = ConsistencyChecker.check(edn, "linearizable", "fifo-queue");
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
        boolean ok = ConsistencyChecker.check(edn, "linearizable", "unordered-queue");
        assertTrue(ok);
    }

    @Test
    void shouldBeValidForSetAddThenRead() {
        String edn =    "["
                        + "{:type :invoke, :f :add,  :process 0, :time 0, :index 0, :value 1},"
                        + "{:type :ok,     :f :add,  :process 0, :time 1, :index 1, :value 1},"
                        + "{:type :invoke, :f :read, :process 1, :time 2, :index 2, :value nil},"
                        + "{:type :ok,     :f :read, :process 1, :time 3, :index 3, :value #{1}}"
                        + "]";

        boolean ok = ConsistencyChecker.check(edn, "linearizable", "set");
        assertTrue(ok);
    }

    @Test
    void shouldBeValidForKvIndependentPerKey() {
        // Two keys k1,k2 with per-key linearizable sequences
        String edn = "["
                + "{:type :invoke, :f :write, :process 0, :time 0, :index 0, :name [\"k1\" \"v1\"]},"
                + "{:type :ok,     :f :write, :process 0, :time 1, :index 1, :name [\"k1\" \"v1\"]},"
                + "{:type :invoke, :f :read,  :process 1, :time 2, :index 2, :name [\"k1\" nil]},"
                + "{:type :ok,     :f :read,  :process 1, :time 3, :index 3, :name [\"k1\" \"v1\"]},"

                + "{:type :invoke, :f :write, :process 2, :time 4, :index 4, :name [\"k2\" \"a\"]},"
                + "{:type :ok,     :f :write, :process 2, :time 5, :index 5, :name [\"k2\" \"a\"]},"
                + "{:type :invoke, :f :read,  :process 3, :time 6, :index 6, :name [\"k2\" nil]},"
                + "{:type :ok,     :f :read,  :process 3, :time 7, :index 7, :name [\"k2\" \"a\"]}"
                + "]";
        boolean ok = ConsistencyChecker.checkIndependent(edn, "linearizable", "register");
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
        boolean ok = ConsistencyChecker.checkIndependent(edn, "linearizable", "register");
        assertFalse(ok);
    }
}