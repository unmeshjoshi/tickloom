package com.tickloom.testkit;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.quorum.GetResponse;
import com.tickloom.algorithms.replication.quorum.QuorumReplica;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.algorithms.replication.quorum.SetResponse;
import com.tickloom.future.ListenableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static com.tickloom.testkit.ClusterAssertions.*;
import static org.junit.jupiter.api.Assertions.*;

public class NetworkPartitionTest {

    // Common ids reused across tests
    private static final ProcessId ATHENS    = ProcessId.of("athens");
    private static final ProcessId BYZANTIUM = ProcessId.of("byzantium");
    private static final ProcessId CYRENE    = ProcessId.of("cyrene");
    private static final ProcessId DELPHI    = ProcessId.of("delphi");
    private static final ProcessId SPARTA    = ProcessId.of("sparta");

    private static final ProcessId MINORITY_CLIENT = ProcessId.of("minority_client");
    private static final ProcessId MAJORITY_CLIENT = ProcessId.of("majority_client");

    private static final int SKEW_TICKS = 10;

    @Test
    @DisplayName("Split-brain prevention: majority value persists after heal")
    void shouldPreventSplitBrainDuringNetworkPartition() throws IOException {
        try (var cluster = new Cluster()
                .withProcessIds(List.of(ATHENS, BYZANTIUM, CYRENE, DELPHI, SPARTA))
                .useSimulatedNetwork()
                .build(QuorumReplica::new)
                .start()) {

            // clients
            var minorityClient = cluster.newClientConnectedTo(MINORITY_CLIENT, ATHENS, QuorumReplicaClient::new);
            var majorityClient = cluster.newClientConnectedTo(MAJORITY_CLIENT, CYRENE, QuorumReplicaClient::new);

            // data
            byte[] key = "distributed_ledger".getBytes();
            byte[] initialValue = "genesis_block".getBytes();
            byte[] minorityValue = "minority_attempt".getBytes();
            byte[] majorityValue = "majority_success".getBytes();

            // phase 1 — initial write via majority, cluster converges
            var initialSet = majorityClient.set(key, initialValue);
            assertEventually(cluster, initialSet::isCompleted);
            assertTrue(initialSet.getResult().success(), "Initial write should succeed");
            assertAllNodeStoragesContainValue(cluster, key, initialValue);

            // phase 2 — partition 2 vs 3
            var minority = NodeGroup.of(ATHENS, BYZANTIUM);
            var majority = NodeGroup.of(CYRENE, DELPHI, SPARTA);
            cluster.partitionNodes(minority, majority);

            // phase 3 — minority write fails for client (no quorum) but persists locally
            var minorityWrite = minorityClient.set(key, minorityValue);
            assertEventually(cluster, minorityWrite::isFailed);
            assertNodesContainValue(cluster, List.of(ATHENS, BYZANTIUM), key, minorityValue);

            // phase 4 — majority write succeeds in its partition
            var majorityWrite = majorityClient.set(key, majorityValue);
            assertEventually(cluster, () -> majorityWrite.isCompleted() && majorityWrite.getResult().success());
            assertNodesContainValue(cluster, List.of(CYRENE, DELPHI, SPARTA), key, majorityValue);

            // phase 5 — heal and verify final value (majority value should win without skew)
            cluster.healAllPartitions();

            var healedRead = majorityClient.get(key);
            assertEventually(cluster, healedRead::isCompleted);
            assertTrue(healedRead.getResult().found(), "Data should be retrievable after healing");
            assertArrayEquals(majorityValue, healedRead.getResult().value(), "Majority value should persist after heal");
        }
    }

    @Test
    @DisplayName("Clock skew: minority (higher timestamp) wins after heal")
    void clockSkewOverwritesMajorityValue() throws IOException {
        try (var cluster = new Cluster()
                .withProcessIds(List.of(ATHENS, BYZANTIUM, CYRENE, DELPHI, SPARTA))
                .useSimulatedNetwork()
                .build(QuorumReplica::new)
                .start()) {

            var minorityClient = cluster.newClientConnectedTo(MINORITY_CLIENT, ATHENS, QuorumReplicaClient::new);
            var majorityClient = cluster.newClientConnectedTo(MAJORITY_CLIENT, CYRENE, QuorumReplicaClient::new);

            byte[] key = "distributed_ledger".getBytes();
            byte[] initialValue = "genesis_block".getBytes();
            byte[] minorityValue = "minority_attempt".getBytes();
            byte[] majorityValue = "majority_success".getBytes();

            // phase 1 — initial converge
            var initialSet = majorityClient.set(key, initialValue);
            assertEventually(cluster, initialSet::isCompleted);
            assertTrue(initialSet.getResult().success(), "Initial write should succeed");
            assertAllNodeStoragesContainValue(cluster, key, initialValue);

            // phase 2 — partition
            cluster.partitionNodes(NodeGroup.of(ATHENS, BYZANTIUM), NodeGroup.of(CYRENE, DELPHI, SPARTA));

            // phase 3 — minority write fails for client but persists locally
            var minorityWrite = minorityClient.set(key, minorityValue);
            assertEventually(cluster, minorityWrite::isFailed);
            assertNodesContainValue(cluster, List.of(ATHENS, BYZANTIUM), key, minorityValue);

            // phase 4 — skew CYRENE's clock behind ATHENS; majority write now has lower ts
            var athensTs = cluster.getStorageValue(ATHENS, key).timestamp();
            cluster.setTimeForProcess(CYRENE, athensTs - SKEW_TICKS);

            var majorityWrite = majorityClient.set(key, majorityValue);
            assertEventually(cluster, () -> majorityWrite.isCompleted() && majorityWrite.getResult().success());
            assertNodesContainValue(cluster, List.of(CYRENE, DELPHI, SPARTA), key, majorityValue);

            // phase 5 — heal; higher timestamp (minority) should prevail
            cluster.healAllPartitions();

            var healedRead = minorityClient.get(key);
            assertEventually(cluster, healedRead::isCompleted);
            assertTrue(healedRead.getResult().found(), "Data should be retrievable after healing");
            assertArrayEquals(minorityValue, healedRead.getResult().value(),
                    "Minority value (higher timestamp) should win after heal with clock skew");
        }
    }

    @Test
    @DisplayName("Network delays: write/read succeed under variable latencies")
    void shouldHandleVariableNetworkDelays() throws IOException {
        // Local ids for this scenario
        var CALIFORNIA = ProcessId.of("california");
        var NEWYORK    = ProcessId.of("newyork");
        var LONDON     = ProcessId.of("london");

        try (var cluster = new Cluster()
                .withProcessIds(List.of(CALIFORNIA, NEWYORK, LONDON))
                .useSimulatedNetwork()
                .build(QuorumReplica::new)
                .start()) {

            // symmetric delays (ticks)
            cluster.setNetworkDelay(CALIFORNIA, NEWYORK, 5);
            cluster.setNetworkDelay(NEWYORK, CALIFORNIA, 5);
            cluster.setNetworkDelay(CALIFORNIA, LONDON, 15);
            cluster.setNetworkDelay(LONDON, CALIFORNIA, 15);
            cluster.setNetworkDelay(NEWYORK, LONDON, 8);
            cluster.setNetworkDelay(LONDON, NEWYORK, 8);

            var client = cluster.newClient(ProcessId.of("mobile_app"), QuorumReplicaClient::new);

            byte[] key = "user_profile".getBytes();
            byte[] value = "updated_profile_data".getBytes();

            var delayedWrite = client.set(key, value);
            assertEventually(cluster, delayedWrite::isCompleted);
            assertTrue(delayedWrite.getResult().success(), "Write should succeed despite network delays");

            var delayedRead = client.get(key);
            assertEventually(cluster, delayedRead::isCompleted);
            assertTrue(delayedRead.getResult().found(), "Data should be retrievable");
            assertArrayEquals(value, delayedRead.getResult().value(), "Data should be consistent");
        }
    }
}
