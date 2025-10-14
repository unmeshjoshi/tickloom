package com.tickloom.testkit;

import static com.tickloom.testkit.ClusterAssertions.assertEventually;
import static com.tickloom.testkit.Cluster.createSimulated;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.quorum.QuorumReplica;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.algorithms.replication.quorum.SetResponse;
import com.tickloom.algorithms.replication.quorum.GetResponse;
import com.tickloom.future.ListenableFuture;

/**
 * Demonstrates the tightened Cluster design with ProcessId-only methods and clientId-specific targeting.
 */
public class ClientTargetingTest {

    @Test
    @DisplayName("Should allow clients to connect to specific nodes for targeted failure scenarios")
    void shouldAllowTargetedClientConnections() throws IOException {
        List<ProcessId> processIds = List.of(ProcessId.of("athens"), ProcessId.of("byzantium"), ProcessId.of("cyrene"), ProcessId.of("delphi"), ProcessId.of("sparta")); // <"athens", "byzantium", "cyrene", "delphi", "sparta">
        try (Cluster cluster = Cluster.createSimulated(processIds, (peerIds, processParams) -> new QuorumReplica(peerIds, processParams))) {

            ProcessId athens = ProcessId.of("athens");
            ProcessId byzantium = ProcessId.of("byzantium");

            // Create clients connected to specific nodes
            ProcessId client1 = ProcessId.of("clientId-connected-to-athens");
            ProcessId client2 = ProcessId.of("clientId-connected-to-byzantium");
            
            // Client 1 connects only to Athens
            QuorumReplicaClient athensClient = cluster.newClientConnectedTo(client1, athens, QuorumReplicaClient::new);
            
            // Client 2 connects only to Byzantium
            QuorumReplicaClient byzantiumClient = cluster.newClientConnectedTo(client2, byzantium, QuorumReplicaClient::new);

            // Phase 1: Normal operation - both clients can write
            byte[] key = "distributed_state".getBytes();
            byte[] value1 = "written_via_athens".getBytes();
            byte[] value2 = "written_via_byzantium".getBytes();

            ListenableFuture<SetResponse> athensWrite = athensClient.set(key, value1);
            assertEventually(cluster,() -> athensWrite.isCompleted() && athensWrite.getResult().success());

            ListenableFuture<SetResponse> byzantiumWrite = byzantiumClient.set(key, value2);
            assertEventually(cluster,() -> byzantiumWrite.isCompleted() && byzantiumWrite.getResult().success());

            // Phase 2: Isolate Athens - clientId connected to Athens should fail, but Byzantium clientId continues
            cluster.isolateProcess(athens);
            System.out.println("=== Athens isolated ===");

            // Athens clientId should fail/timeout when trying to write
            byte[] failedValue = "should_fail".getBytes();
            ListenableFuture<SetResponse> athensFailedWrite = athensClient.set(key, failedValue);
            
            // Tick for a limited time to see if it completes
            for (int i = 0; i < 50; i++) {
                cluster.tick();
                if (athensFailedWrite.isCompleted()) break;
            }
            
            System.out.println("Athens clientId write completed: " + athensFailedWrite.isCompleted());
            if (athensFailedWrite.isCompleted()) {
                System.out.println("Athens clientId write success: " + athensFailedWrite.getResult().success());
            }

            // Byzantium clientId should still work (writes to majority)
            byte[] workingValue = "byzantium_still_works".getBytes();
            ListenableFuture<SetResponse> byzantiumWorkingWrite = byzantiumClient.set(key, workingValue);
            assertEventually(cluster,() -> byzantiumWorkingWrite.isCompleted() && byzantiumWorkingWrite.getResult().success());

            // Phase 3: Reconnect Athens and verify both clients work again
            cluster.reconnectProcess(athens);
            System.out.println("=== Athens reconnected ===");

            byte[] finalValue = "both_clients_working".getBytes();
            ListenableFuture<SetResponse> athensFinalWrite = athensClient.set(key, finalValue);
            assertEventually(cluster,() -> athensFinalWrite.isCompleted() && athensFinalWrite.getResult().success());

            // Verify consistency across all clients
            ListenableFuture<GetResponse> athensRead = athensClient.get(key);
            assertEventually(cluster,() -> athensRead.isCompleted() && athensRead.getResult().found()
                    && Arrays.equals(finalValue, athensRead.getResult().value()));

            ListenableFuture<GetResponse> byzantiumRead = byzantiumClient.get(key);
            assertEventually(cluster,(() -> byzantiumRead.isCompleted()
                    && byzantiumRead.getResult().found()
                    && Arrays.equals(finalValue, byzantiumRead.getResult().value())));
        }
    }

    @Test
    @DisplayName("Should demonstrate clock control using ProcessId-only methods")
    void shouldDemonstrateProcessIdClockControl() throws IOException {
        List<ProcessId> processIds = List.of(ProcessId.of("fast_node"), ProcessId.of("slow_node"), ProcessId.of("normal_node"));
        try (Cluster cluster = new Cluster()
                .withProcessIds(processIds)
                .withInitialClockTime(2000L)
                .useSimulatedNetwork()
                .build((peerIds, processParams) -> new QuorumReplica(peerIds, processParams))
                .start()) {

            ProcessId fastNode = ProcessId.of("fast_node");
            ProcessId slowNode = ProcessId.of("slow_node");
            ProcessId normalNode = ProcessId.of("normal_node");

            // Verify initial times
            assertEquals(2000L, cluster.getClockForProcess(fastNode).now());
            assertEquals(2000L, cluster.getClockForProcess(slowNode).now());
            assertEquals(2000L, cluster.getClockForProcess(normalNode).now());

            // Set different clock speeds
            cluster.setTimeForProcess(fastNode, 5000L);
            cluster.setTimeForProcess(slowNode, 1000L);
            // normal_node stays at 2000L

            assertEquals(5000L, cluster.getClockForProcess(fastNode).now());
            assertEquals(1000L, cluster.getClockForProcess(slowNode).now());
            assertEquals(2000L, cluster.getClockForProcess(normalNode).now());

            // Advance individual clocks
            cluster.advanceTimeForProcess(fastNode, 500L);
            cluster.advanceTimeForProcess(slowNode, 200L);

            assertEquals(5500L, cluster.getClockForProcess(fastNode).now());
            assertEquals(1200L, cluster.getClockForProcess(slowNode).now());
            assertEquals(2000L, cluster.getClockForProcess(normalNode).now()); // unchanged

            // Test partitioning specific nodes
            cluster.partitionNodes(fastNode, slowNode);
            
            // Heal specific partition
            cluster.healPartition(fastNode, slowNode);
            
            System.out.println("=== ProcessId-only design demonstration completed ===");
        }
    }
}
