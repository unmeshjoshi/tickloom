package com.tickloom.testkit;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.quorum.QuorumReplica;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.algorithms.replication.quorum.SetResponse;
import com.tickloom.algorithms.replication.quorum.GetResponse;
import com.tickloom.future.ListenableFuture;

/**
 * Demonstrates the tightened Cluster design with ProcessId-only methods and client-specific targeting.
 */
public class ClientTargetingTest {

    @Test
    @DisplayName("Should allow clients to connect to specific nodes for targeted failure scenarios")
    void shouldAllowTargetedClientConnections() throws IOException {
        try (Cluster cluster = new Cluster()
                .withProcessNames("athens", "byzantium", "cyrene", "delphi", "sparta")
                .useSimulatedNetwork()
                .build(QuorumReplica::new)
                .start()) {

            ProcessId athens = ProcessId.of("athens");
            ProcessId byzantium = ProcessId.of("byzantium");
            ProcessId cyrene = ProcessId.of("cyrene");
            ProcessId delphi = ProcessId.of("delphi");
            ProcessId sparta = ProcessId.of("sparta");

            // Create clients connected to specific nodes
            ProcessId client1 = ProcessId.of("client-connected-to-athens");
            ProcessId client2 = ProcessId.of("client-connected-to-byzantium");
            
            // Client 1 connects only to Athens
            QuorumReplicaClient athensClient = cluster.newClientConnectedTo(client1, athens, QuorumReplicaClient::new);
            
            // Client 2 connects only to Byzantium
            QuorumReplicaClient byzantiumClient = cluster.newClientConnectedTo(client2, byzantium, QuorumReplicaClient::new);

            // Phase 1: Normal operation - both clients can write
            byte[] key = "distributed_state".getBytes();
            byte[] value1 = "written_via_athens".getBytes();
            byte[] value2 = "written_via_byzantium".getBytes();

            ListenableFuture<SetResponse> athensWrite = athensClient.set(key, value1);
            cluster.tickUntil(() -> athensWrite.isCompleted());
            assertTrue(athensWrite.getResult().success(), "Athens client should successfully write");

            ListenableFuture<SetResponse> byzantiumWrite = byzantiumClient.set(key, value2);
            cluster.tickUntil(() -> byzantiumWrite.isCompleted());
            assertTrue(byzantiumWrite.getResult().success(), "Byzantium client should successfully write");

            // Phase 2: Isolate Athens - client connected to Athens should fail, but Byzantium client continues
            cluster.isolateProcess(athens);
            System.out.println("=== Athens isolated ===");

            // Athens client should fail/timeout when trying to write
            byte[] failedValue = "should_fail".getBytes();
            ListenableFuture<SetResponse> athensFailedWrite = athensClient.set(key, failedValue);
            
            // Tick for a limited time to see if it completes
            for (int i = 0; i < 50; i++) {
                cluster.tick();
                if (athensFailedWrite.isCompleted()) break;
            }
            
            System.out.println("Athens client write completed: " + athensFailedWrite.isCompleted());
            if (athensFailedWrite.isCompleted()) {
                System.out.println("Athens client write success: " + athensFailedWrite.getResult().success());
            }

            // Byzantium client should still work (writes to majority)
            byte[] workingValue = "byzantium_still_works".getBytes();
            ListenableFuture<SetResponse> byzantiumWorkingWrite = byzantiumClient.set(key, workingValue);
            cluster.tickUntil(() -> byzantiumWorkingWrite.isCompleted());
            assertTrue(byzantiumWorkingWrite.getResult().success(), "Byzantium client should still work during Athens isolation");

            // Phase 3: Reconnect Athens and verify both clients work again
            cluster.reconnectProcess(athens);
            System.out.println("=== Athens reconnected ===");

            byte[] finalValue = "both_clients_working".getBytes();
            ListenableFuture<SetResponse> athensFinalWrite = athensClient.set(key, finalValue);
            cluster.tickUntil(() -> athensFinalWrite.isCompleted());
            assertTrue(athensFinalWrite.getResult().success(), "Athens client should work after reconnection");

            // Verify consistency across all clients
            ListenableFuture<GetResponse> athensRead = athensClient.get(key);
            cluster.tickUntil(() -> athensRead.isCompleted());
            assertTrue(athensRead.getResult().found(), "Athens client should read final value");
            assertArrayEquals(finalValue, athensRead.getResult().value(), "Data should be consistent");

            ListenableFuture<GetResponse> byzantiumRead = byzantiumClient.get(key);
            cluster.tickUntil(() -> byzantiumRead.isCompleted());
            assertTrue(byzantiumRead.getResult().found(), "Byzantium client should read final value");
            assertArrayEquals(finalValue, byzantiumRead.getResult().value(), "Data should be consistent across clients");
        }
    }

    @Test
    @DisplayName("Should demonstrate clock control using ProcessId-only methods")
    void shouldDemonstrateProcessIdClockControl() throws IOException {
        try (Cluster cluster = new Cluster()
                .withProcessNames("fast_node", "slow_node", "normal_node")
                .withInitialClockTime(2000L)
                .useSimulatedNetwork()
                .build(QuorumReplica::new)
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
