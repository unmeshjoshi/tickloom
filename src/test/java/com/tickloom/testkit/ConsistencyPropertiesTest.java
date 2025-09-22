package com.tickloom.testkit;

import com.tickloom.ConsistencyChecker;
import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.quorum.QuorumReplica;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.history.History;
import com.tickloom.history.Op;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static com.tickloom.testkit.ClusterAssertions.assertEventually;

/** Scenarios illustrating differences between linearizability and sequential consistency. */
public class ConsistencyPropertiesTest {

    private static final ProcessId ATHENS    = ProcessId.of("athens");
    private static final ProcessId BYZANTIUM = ProcessId.of("byzantium");
    private static final ProcessId CYRENE    = ProcessId.of("cyrene");
    private static final ProcessId DELPHI    = ProcessId.of("delphi");
    private static final ProcessId SPARTA    = ProcessId.of("sparta");

    @Test
    @DisplayName("Stale local read after quorum write: linearizable=false, sequential=false in this model")
    void shouldBeSequentialButNotLinearizableForStaleLocalRead() throws IOException {
        try (var cluster = new Cluster()
                .withProcessIds(List.of(ATHENS, BYZANTIUM, CYRENE, DELPHI, SPARTA))
                .useSimulatedNetwork()
                .build(QuorumReplica::new)
                .start()) {

            var clientWriter = cluster.newClientConnectedTo(ProcessId.of("client1"), CYRENE, QuorumReplicaClient::new);

            String key = "kv";
            String v0  = "v0";
            String v1  = "v1";

            History<String, String> history = new History();

            // Initialize to v0 under full connectivity
            history.invoke(ProcessId.of("client1"), Op.WRITE, key, v0);
            var init = clientWriter.set(key.getBytes(), v0.getBytes());
            assertEventually(cluster, init::isCompleted);
            assertTrue(init.getResult().success());
            history.ok(ProcessId.of("client1"), Op.WRITE, key, v0);

            assertEventually(cluster, () -> {
                var v = cluster.getStorageValue(ATHENS, key.getBytes());
                return v != null && java.util.Arrays.equals(v.value(), v0.getBytes());
            });
            // Partition to isolate ATHENS from writer majority
            var writerSide = NodeGroup.of(CYRENE, DELPHI, SPARTA);
            var otherSide  = NodeGroup.of(ATHENS, BYZANTIUM);
            cluster.partitionNodes(writerSide, otherSide);

            // Quorum write v1 on writer side
            history.invoke(ProcessId.of("client1"), Op.WRITE, key, v1);
            var w1 = clientWriter.set(key.getBytes(), v1.getBytes());
            assertEventually(cluster, () -> w1.isCompleted() && w1.getResult().success());
            assertTrue(w1.getResult().success());
            history.ok(ProcessId.of("client1"), Op.WRITE, key, v1);

            // Local read from ATHENS (read-one), returns stale v0
            history.invoke(ProcessId.of("client2"), Op.READ, key, null);
            var vv = cluster.getStorageValue(ATHENS, key.getBytes());
            assertNotNull(vv);
            assertArrayEquals(v0.getBytes(), vv.value());
            history.ok(ProcessId.of("client2"), Op.READ, key, new String(vv.value()));

            String edn = history.toEdn();
            System.out.println("edn = " + edn);
            assertFalse(ConsistencyChecker.check(edn, "linearizable", "register"));
            assertTrue(ConsistencyChecker.check(edn, "sequential", "register"));
        }
    }

    @Test
    @DisplayName("Same client stale read after own write: linearizable=false, sequential=false")
    void shouldBeNonLinearizableAndNonSequentialForSameClientStaleRead() throws IOException {
        try (var cluster = new Cluster()
                .withProcessIds(List.of(ATHENS, BYZANTIUM, CYRENE, DELPHI, SPARTA))
                .useSimulatedNetwork()
                .build(QuorumReplica::new)
                .start()) {

            var client = cluster.newClientConnectedTo(ProcessId.of("clientX"), CYRENE, QuorumReplicaClient::new);

            String key = "kv";
            String v0  = "v0";
            String v1  = "v1";

            History<String, String> history = new History();

            // Initialize v0
            history.invoke(ProcessId.of("clientX"), Op.WRITE, key, v0);
            var init = client.set(key.getBytes(), v0.getBytes());
            assertEventually(cluster, init::isCompleted);
            assertTrue(init.getResult().success());
            history.ok(ProcessId.of("clientX"), Op.WRITE, key, v0);

            // Partition to isolate ATHENS
            var writerSide = NodeGroup.of(CYRENE, DELPHI, SPARTA);
            var otherSide  = NodeGroup.of(ATHENS, BYZANTIUM);
            cluster.partitionNodes(writerSide, otherSide);

            // Same client quorum write v1
            history.invoke(ProcessId.of("clientX"), Op.WRITE, key, v1);
            var w1 = client.set(key.getBytes(), v1.getBytes());
            assertEventually(cluster, () -> w1.isCompleted() && w1.getResult().success());
            assertTrue(w1.getResult().success());
            history.ok(ProcessId.of("clientX"), Op.WRITE, key, v1);

            // Same client performs local read from ATHENS (read-one) -> stale v0
            history.invoke(ProcessId.of("clientX"), Op.READ, key, null);
            var vv = cluster.getStorageValue(ATHENS, key.getBytes());
            assertNotNull(vv);
            assertArrayEquals(v0.getBytes(), vv.value());
            history.ok(ProcessId.of("clientX"), Op.READ, key, new String(vv.value()));

            String edn = history.toEdn();
            assertFalse(ConsistencyChecker.check(edn, "linearizable", "register"));
            assertFalse(ConsistencyChecker.check(edn, "sequential", "register"));
        }
    }
}
