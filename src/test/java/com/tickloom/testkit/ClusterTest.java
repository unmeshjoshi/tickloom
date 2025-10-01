package com.tickloom.testkit;

import com.tickloom.ConsistencyChecker;
import com.tickloom.ProcessFactory;
import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.future.ListenableFuture;
import com.tickloom.history.History;
import com.tickloom.storage.VersionedValue;
import com.tickloom.util.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Base class for cluster-based tests. Owns cluster lifecycle, a Jepsen-style history wrapper (H),
 * and handy assertion/utility methods so tests read like a DSL.
 *
 * @param <C>           client type (e.g., QuorumReplicaClient) created via Cluster.ClientFactory
 * @param <RGet>        response type for read operations
 * @param <JepsenValue> value type recorded into Jepsen history (:value), e.g., String or tuple
 */
public abstract class ClusterTest<C extends ClusterClient, RGet, JepsenValue> {

    private final List<ProcessId> processIds;
    private final ProcessFactory serverFactory;
    private final Cluster.ClientFactory<C> clientFactory;

    /** Jepsen history wrapper for KV ops (generic over read response mapping). */
    protected final QuorumKVOps<RGet, JepsenValue> H;

    /** The running cluster for this test. */
    protected Cluster cluster;

    public ClusterTest(List<ProcessId> processIds,
                       ProcessFactory serverFactory,
                       Cluster.ClientFactory<C> clientFactory,
                       Function<RGet, JepsenValue> readValueMapper) throws IOException {
        this.processIds = processIds;
        this.serverFactory = serverFactory;
        this.clientFactory = clientFactory;
        this.H = new QuorumKVOps<>(readValueMapper);
    }

    @BeforeEach
    public void beforeEach() throws IOException {
        cluster = new Cluster()
                .withProcessIds(processIds)
                .useSimulatedNetwork()
                .build(serverFactory)
                .start();
    }

    @AfterEach
    public void afterEach() {
        if (cluster != null) {
            try { cluster.close(); } catch (Exception ignore) {}
        }
    }

    /* ==============================
     * Mini-DSL / convenience methods
     * ============================== */

    /** Create a client bound to a server. */
    protected C clientConnectedTo(ProcessId clientId, ProcessId serverId) {
        try {
            return cluster.newClientConnectedTo(clientId, serverId, clientFactory);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Partition the cluster into two groups.
     *
     * @return
     */
    protected ClusterTest<C, RGet, JepsenValue> partition(NodeGroup a, NodeGroup b) {
        cluster.partitionNodes(a, b);
        return this;
    }

    /**
     * Heal all partitions.
     *
     * @return
     */
    protected ClusterTest<C, RGet, JepsenValue> healAllPartitions() {
        cluster.healAllPartitions();
        return this;
    }

    /**
     * Set the logical time for a process (absolute).
     *
     * @return
     */
    protected ClusterTest<C, RGet, JepsenValue> clockSkew(ProcessId processId, long logicalTs) {
        cluster.setTimeForProcess(processId, logicalTs);
        return this;
    }

    /** Peek stored value at a node; throws if absent. */
    protected long timestampOfStoredValue(ProcessId processId, byte[] key) {
        var vv = storedValue(processId, key);
        assertNotNull(vv, () -> "No value at " + processId + " for key=" + pretty(key));
        return vv.timestamp();
    }

    /** Peek stored value at a node; may be null. */
    protected VersionedValue storedValue(ProcessId processId, byte[] key) {
        return cluster.getStorageValue(processId, key);
    }

    /**
     * Await a condition, ticking the cluster until it holds or times out.
     *
     * @return
     */
    protected ClusterTest<C, RGet, JepsenValue> assertEventually(BooleanSupplier cond) {
        assertEventually(cond, 10_000, "Timeout waiting for condition");
        return this;
    }

    protected ClusterTest<C, RGet, JepsenValue> assertEventually(BooleanSupplier cond, int timeoutTicks, String message) {
        int ticks = 0;
        while (!cond.getAsBoolean()) {
            if (ticks++ >= timeoutTicks) fail(message + " after " + timeoutTicks + " ticks");
            cluster.tick();
        }
        return this;
    }

    /* ==============================
     * History-backed read/write
     * ============================== */

    /**
     * Record a WRITE in history and await success.
     *
     * @return
     */
    protected ClusterTest<C, RGet, JepsenValue> write(Writer<C, JepsenValue> writer) {
        var fut = H.write(writer.client.id, writer.attemptedValue(), writer.getSupplier());
        assertEventually(() -> fut.isCompleted() && !fut.isFailed());
        return this;
    }

    static abstract class Writer<C extends ClusterClient, HistVal> {
        C client;
        final String key;
        final String value;

        public Writer(C client, String key, String value) {
            this.client = client;
            this.key = key;
            this.value = value;
        }

        public abstract HistVal attemptedValue();
        public abstract Supplier<ListenableFuture<?>> getSupplier();

    }

    /** Record a READ in history and await completion (generic over client/response). */

    static abstract class Reader<C extends ClusterClient, RGet, HistVal> {
        C client;
        private final HistVal attemptedValue;

        public Reader(C client) {
            this.client = client;
            this.attemptedValue = null;
        }
        public Reader(C client, HistVal attemptedValue) {
            this.client = client;
            this.attemptedValue = attemptedValue;
        }

        public abstract Supplier<ListenableFuture<RGet>> getSupplier();
    }


    protected ClusterTest<C, RGet, JepsenValue> read(Reader reader) {
        var fut = H.read(reader.client.id, reader.getSupplier());
        assertEventually(() -> isSuccessful(fut));
        return this;
    }

    private static <RGet> boolean isSuccessful(ListenableFuture<RGet> fut) {
        return fut.isCompleted() && !fut.isFailed();
    }

    /** Persist EDN history for offline Jepsen/Knossos checks; returns the history as well. */
    protected History writeHistoryEdnFile(TestInfo testInfo) throws IOException {
        var history = H.history();
        TestUtils.writeEdnFile(testInfo, history);
        return history;
    }

    /* ==============================
     * Consistency checks
     * ============================== */

    protected ClusterTest<C, RGet, JepsenValue> assertSequentialConsistency(boolean expected) {
        System.out.println("History is " + H.history().toEdn());
        boolean seq = ConsistencyChecker.check(
                H.history(),
                ConsistencyChecker.ConsistencyProperty.SEQUENTIAL_CONSISTENCY,
                ConsistencyChecker.DataModel.REGISTER
        );
        assertEquals(expected, seq, "Sequential consistency mismatch");
        return this;
    }

    protected ClusterTest<C, RGet, JepsenValue> assertLinearizability(boolean expected) {
        boolean lin = ConsistencyChecker.check(
                H.history(),
                ConsistencyChecker.ConsistencyProperty.LINEARIZABILITY,
                ConsistencyChecker.DataModel.REGISTER
        );
        assertEquals(expected, lin, "Linearizability mismatch");
        return this;
    }

    /* ==============================
     * Storage assertions
     * ============================== */

    /**
     * Assert all given nodes store the expected value for key.
     *
     * @return
     */
    protected ClusterTest<C, RGet, JepsenValue> assertNodesContainValue(List<ProcessId> nodes, byte[] key, byte[] expected) {
        for (ProcessId node : nodes) {
            var actual = cluster.getStorageValue(node, key);
            assertNotNull(actual, () -> "No value at " + node + " for key=" + pretty(key));
            assertArrayEquals(expected, actual.value(),
                    () -> "Mismatch at " + node +
                            " key=" + pretty(key) +
                            " expected=" + pretty(expected) +
                            " actual=" + pretty(actual.value()) +
                            " ts=" + actual.timestamp());
        }
        return this;
    }

    /** String sugar for assertNodesContainValue. */
    protected void assertNodesContainValue(List<ProcessId> nodes, String key, String expected) {
        assertNodesContainValue(
                nodes,
                key.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Assert the listed nodes have no value for key.
     *
     * @return
     */
    protected ClusterTest<C, RGet, JepsenValue> assertNoValue(byte[] key, List<ProcessId> nodes) {
        for (ProcessId node : nodes) {
            VersionedValue vv = cluster.getStorageValue(node, key);
            assertNull(vv, () -> "Expected no value at " + node + " for key=" + pretty(key) +
                    " but found=" + (vv == null ? "null" : pretty(vv.value()) + " ts=" + vv.timestamp()));
        }
        return this;
    }

    /* ==============================
     * Helpers
     * ============================== */

    static String pretty(byte[] b) {
        if (b == null) return "null";
        var s = new String(b, StandardCharsets.UTF_8);
        boolean printable = s.chars().allMatch(ch -> ch >= 32 && ch < 127);
        if (printable) return '"' + s + '"';
        var sb = new StringBuilder("0x");
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }


    static String maskNull(byte[] val) {
        return val == null ? null : new String(val);
    }
}
