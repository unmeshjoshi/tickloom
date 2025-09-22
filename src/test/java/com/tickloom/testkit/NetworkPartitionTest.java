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
    @DisplayName("Split-brain prevention: majority name persists after heal")
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

            // phase 3 — minority write fails for clientId (no quorum) but persists locally
            var minorityWrite = minorityClient.set(key, minorityValue);
            assertEventually(cluster, minorityWrite::isFailed);
            assertNodesContainValue(cluster, List.of(ATHENS, BYZANTIUM), key, minorityValue);

            // phase 4 — majority write succeeds in its partition
            var majorityWrite = majorityClient.set(key, majorityValue);
            assertEventually(cluster, () -> majorityWrite.isCompleted() && majorityWrite.getResult().success());
            assertNodesContainValue(cluster, List.of(CYRENE, DELPHI, SPARTA), key, majorityValue);

            // phase 5 — heal and verify final name (majority name should win without skew)
            cluster.healAllPartitions();

            var healedRead = majorityClient.get(key);
            assertEventually(cluster, healedRead::isCompleted);
            assertTrue(healedRead.getResult().found(), "Data should be retrievable after healing");
            assertArrayEquals(majorityValue, healedRead.getResult().value(), "Majority name should persist after heal");
        }
    }

    @Test
    @DisplayName("Local stale read after quorum write: linearizable=false, sequential=true")
    void localReadAfterQuorumWrite_breaksLin_passesSeq() throws IOException {
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

            // Step 1: initialize name via quorum so all nodes converge to v0
            history.invoke(ProcessId.of("client1"), Op.WRITE, key, v0);
            var init = clientWriter.set(key.getBytes(), v0.getBytes());
            assertEventually(cluster, init::isCompleted);
            assertTrue(init.getResult().success());
            history.ok(ProcessId.of("client1"), Op.WRITE, key, v0);

            // Step 2: partition nodes so writer's majority excludes ATHENS
            var writerSide = NodeGroup.of(CYRENE, DELPHI, SPARTA);
            var otherSide  = NodeGroup.of(ATHENS, BYZANTIUM);
            cluster.partitionNodes(writerSide, otherSide);

            // Step 3: quorum write v1 on writer side
            history.invoke(ProcessId.of("client1"), Op.WRITE, key, v1);
            var w1 = clientWriter.set(key.getBytes(), v1.getBytes());
            assertEventually(cluster, () -> w1.isCompleted() && w1.getResult().success());
            history.ok(ProcessId.of("client1"), Op.WRITE, key, v1);

            // Step 4: different client performs local read on ATHENS (single-node read), sees stale v0
            history.invoke(ProcessId.of("client2"), Op.READ, key, null);
            var vv = cluster.getStorageValue(ATHENS, key.getBytes());
            assertNotNull(vv);
            assertArrayEquals(v0.getBytes(), vv.value());
            history.ok(ProcessId.of("client2"), Op.READ, key, new String(vv.value()));

            // Step 5: analyze: linearizable should fail; sequential should pass (different client)
            String edn = history.toEdn();
            System.out.println("edn = " + edn);
            boolean lin = ConsistencyChecker.check(edn, "linearizable", "register");
            boolean seq = ConsistencyChecker.check(edn, "sequential", "register");
            assertFalse(lin, "Stale read after successful write should not be linearizable");
            assertTrue(seq, "Across clients, stale read can be sequential by reordering");
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

            String key = "distributed_ledger";
            String initialValue = "genesis_block";
            String minorityValue = "minority_attempt";
            String majorityValue = "majority_success";

            // Step 0: start recording a client-observed history for Jepsen analysis
            History<String, String> history = new History<>();

            // Step 1: majority-side write of initialValue; cluster converges on v0
            history.invoke(ProcessId.of("majority_client"), Op.WRITE, new String(key), initialValue);
            var initialSet = majorityClient.set(key.getBytes(), initialValue.getBytes());
            assertEventually(cluster, initialSet::isCompleted);
            assertTrue(initialSet.getResult().success(), "Initial write should succeed");
            assertAllNodeStoragesContainValue(cluster, key.getBytes(), initialValue.getBytes());
            history.ok(ProcessId.of("majority_client"), Op.WRITE, key, initialValue);

            // Step 2: partition cluster into minority (2) and majority (3)
            cluster.partitionNodes(NodeGroup.of(ATHENS, BYZANTIUM), NodeGroup.of(CYRENE, DELPHI, SPARTA));

            // Step 3: minority write times out at client but persists locally in its partition
            history.invoke(ProcessId.of("minority_client"), Op.WRITE, key, minorityValue);
            var minorityWrite = minorityClient.set(key.getBytes(), minorityValue.getBytes());
            assertEventually(cluster, minorityWrite::isFailed);
            assertNodesContainValue(cluster, List.of(ATHENS, BYZANTIUM), key.getBytes(), minorityValue.getBytes());
            history.fail(ProcessId.of("minority_client"), Op.WRITE, key, minorityValue);

            // Step 4: skew majority clock behind minority; majority write now has lower timestamp
            var athensTs = cluster.getStorageValue(ATHENS, key.getBytes()).timestamp();
            cluster.setTimeForProcess(CYRENE, athensTs - SKEW_TICKS);

            history.invoke(ProcessId.of("majority_client"), Op.WRITE, key, majorityValue);
            var majorityWrite = majorityClient.set(key.getBytes(), majorityValue.getBytes());
            assertEventually(cluster, () -> majorityWrite.isCompleted() && majorityWrite.getResult().success());
            history.ok(ProcessId.of("majority_client"), Op.WRITE, key, majorityValue);

            // Step 5: heal partitions; higher timestamp (minority) should prevail cluster-wide
            cluster.healAllPartitions();

            history.invoke(ProcessId.of("minority_client"), Op.READ, key, null);
            var healedRead = minorityClient.get(key.getBytes());
            assertEventually(cluster, healedRead::isCompleted);
            assertTrue(healedRead.getResult().found(), "Data should be retrievable after healing");
            assertArrayEquals(minorityValue.getBytes(), healedRead.getResult().value(),
                    "Minority name (higher timestamp) should win after heal with clock skew");
            history.ok(ProcessId.of("minority_client"), Op.READ, key, new String(healedRead.getResult().value()));

            // Step 6: prove not linearizable (real-time precedence) and not sequential (no serial order preserves results)
            // - Linearizability fails: after healing, the read observes the minority name written in a different
            //   partition while a successful majority write also occurred. There is no placement respecting real-time precedence.
            // - Sequential consistency fails: even ignoring real-time order, no single serial order yields the observed read.
            String edn = history.toEdn();
            System.out.println("edn = " + edn);
            boolean linearizable = ConsistencyChecker.check(edn, "linearizable", "register");
            assertFalse(linearizable, "History should be non-linearizable: failed write took effect");

            boolean okSeq = ConsistencyChecker.check(edn, "sequential", "register");
            assertFalse(okSeq);
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

//    @Test
//    @DisplayName("Stale read because of clock skew after own write breaks linearizability and sequential consistency")
    //TODO: Update this test.
    void clockSkewNoPartition_staleReads_breaksLinAndSeq() throws IOException {
        try (var cluster = new Cluster()
                .withProcessIds(List.of(ATHENS, BYZANTIUM, CYRENE, DELPHI, SPARTA))
                .useSimulatedNetwork()
                .build(QuorumReplica::new)
                .start()) {

            // Step 0: clients on different servers
            var client1 = cluster.newClientConnectedTo(MAJORITY_CLIENT, CYRENE, QuorumReplicaClient::new);
            var client2 = cluster.newClientConnectedTo(MINORITY_CLIENT, ATHENS, QuorumReplicaClient::new);

            // Data
            byte[] key = "kv".getBytes();
            byte[] v1  = "v1".getBytes();
            byte[] v2  = "v2".getBytes();

            // Record client-observed history for Jepsen independent checking
            History history = new History();

            cluster.partitionNodes(NodeGroup.of(CYRENE, DELPHI, SPARTA), NodeGroup.of(ATHENS, BYZANTIUM));
            // Step 1: client1 writes v1 successfully (ok)
//            history.invoke("client1", Op.WRITE, key, v1);
            var w1 = client1.set(key, v1);
            assertEventually(cluster, w1::isCompleted);
            assertTrue(w1.getResult().success());
//            history.ok("client1", Op.WRITE, key, v1);

            cluster.healAllPartitions();
            cluster.partitionNodes(NodeGroup.of(ATHENS, BYZANTIUM, CYRENE), NodeGroup.of(DELPHI, SPARTA));

            // Step 3: client2 writes v2 successfully, but by connecting to ATHENS, which has
            //clock lagging behind the other nodes.
            history.invoke(ProcessId.of("client2"), Op.WRITE, key, v2);
            // Step 4: skew clock behind; majority write now has lower timestamp
            //The write succeeds because the writes are 'permissive'. If a node has
            //a name with higher timestamp and gets a request with lower timestamp, it does
            //not reject the request, and returns success.
            var athensTs = cluster.getStorageValue(CYRENE, key).timestamp();
            cluster.setTimeForProcess(ATHENS, athensTs - SKEW_TICKS);
            var w2 = client2.set(key, v2);
            assertEventually(cluster, () -> w2.isCompleted() && w2.getResult().success());
            history.ok(ProcessId.of("client2"), Op.WRITE, key, v2);

            ClusterAssertions.assertNodesContainValue(cluster, List.of(ATHENS, BYZANTIUM), key, v2);
            ClusterAssertions.assertNodesContainValue(cluster, List.of(CYRENE), key, v1);
            //client2 reads from athens, gets v2
            //client2 reads from cyrene, gets v1

            // Step 4: client2 performs a read
            // (new connection bound to CYRENE). This read gets an older name.
            var client2OtherSide = cluster.newClientConnectedTo(MINORITY_CLIENT, CYRENE, QuorumReplicaClient::new);
            history.invoke(ProcessId.of("client2"), Op.READ, key, null);
            var r2 = client2OtherSide.get(key);
            assertEventually(cluster, r2::isCompleted);
            history.ok(ProcessId.of("client2"), Op.READ, key, r2.getResult().value());

            String synthetic = history.toEdn();
            System.out.println("synthetic = " + synthetic);
            // Step 5.5: Demonstrate with a synthetic per-key history that a completed stale read
            // after the same client's successful write breaks both lin and seq
//            String synthetic = "["
//                    + "{:type :invoke, :f :write, :process 0, :time 0,  :index 0, :name [\"k\" \"v1\"]},"
//                    + "{:type :ok,     :f :write, :process 0, :time 1,  :index 1, :name [\"k\" \"v1\"]},"
//                    + "{:type :invoke, :f :write, :process 1, :time 10, :index 2, :name [\"k\" \"v2\"]},"
//                    + "{:type :ok,     :f :write, :process 1, :time 11, :index 3, :name [\"k\" \"v2\"]},"
//                    + "{:type :invoke, :f :read,  :process 1, :time 20, :index 4, :name [\"k\" nil]},"
//                    + "{:type :ok,     :f :read,  :process 1, :time 21, :index 5, :name [\"k\" \"v1\"]}"
//                    + "]";
            boolean lin = ConsistencyChecker.check(synthetic, "linearizable", "register");
            boolean seq = ConsistencyChecker.check(synthetic, "sequential", "register");
            assertFalse(lin, "Synthetic stale read after own write should not be linearizable");
            assertFalse(seq, "Synthetic stale read after own write should not be sequential");
        }
    }
}
