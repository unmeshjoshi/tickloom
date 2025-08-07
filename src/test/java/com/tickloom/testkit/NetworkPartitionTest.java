package com.tickloom.testkit;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.quorum.QuorumReplica;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.algorithms.replication.quorum.SetResponse;
import com.tickloom.algorithms.replication.quorum.GetResponse;
import com.tickloom.future.ListenableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests demonstrating network partition and delay scenarios using the enhanced
 * Cluster test utility with meaningful process names.
 * <p>
 * These tests showcase how distributed systems handle various network failures
 * and demonstrate patterns from "Patterns of Distributed Systems".
 */
class NetworkPartitionTest {

    @Test
    void shouldPreventSplitBrainDuringNetworkPartition() throws IOException {
        // Define all ProcessIds upfront
        ProcessId athens = ProcessId.of("athens");
        ProcessId byzantium = ProcessId.of("byzantium");
        ProcessId cyrene = ProcessId.of("cyrene");
        ProcessId delphi = ProcessId.of("delphi");
        ProcessId sparta = ProcessId.of("sparta");

        ProcessId minorityClientId = ProcessId.of("minority_client");
        ProcessId majorityClientId = ProcessId.of("majority_client");

        // Scenario: Network partition separates nodes into two groups
        // Group 1: athens, byzantium (minority - 2 nodes)
        // Group 2: cyrene, delphi, sparta (majority - 3 nodes)

        try (Cluster cluster = new Cluster()
                .withProcessIds(athens, byzantium, cyrene, delphi, sparta)
                .useSimulatedNetwork()
                .build(QuorumReplica::new)
                .start()) {

            // Create targeted clients: one for minority, one for majority

            QuorumReplicaClient minorityClientConnectedToAthens = cluster.newClientConnectedTo(minorityClientId, athens, QuorumReplicaClient::new);
            QuorumReplicaClient majorityClientConnectedWithCyrene = cluster.newClientConnectedTo(majorityClientId, cyrene, QuorumReplicaClient::new);

            // Phase 1: Normal operation - store initial data using majority client
            byte[] key = "distributed_ledger".getBytes();
            byte[] initialValue = "genesis_block".getBytes();

            ListenableFuture<SetResponse> initialSet = majorityClientConnectedWithCyrene.set(key, initialValue);
            cluster.tickUntil(() -> initialSet.isCompleted());
            assertTrue(initialSet.getResult().success(), "Initial write should succeed");

            // Verify all nodes have the initial data
            assertTrue(cluster.allNodeStoragesContainValue(key, initialValue), "All nodes should have initial data");

            System.out.println("=== All nodes synchronized with initial data ===");

            // Phase 2: Create network partition
            cluster.partitionNodes(NodeGroup.of(athens, byzantium), NodeGroup.of(cyrene, delphi, sparta)); // <List.of(athens, byzantium), List.of(cyrene, delphi, sparta));

            System.out.println("=== Network partition created ===");
            System.out.println("Minority partition: athens, byzantium (2 nodes)");
            System.out.println("Majority partition: cyrene, delphi, sparta (3 nodes)");

            // Phase 3: Test minority partition - should fail due to lack of quorum
            byte[] minorityValue = "minority_attempt".getBytes();
            ListenableFuture<SetResponse> minorityWrite = minorityClientConnectedToAthens.set(key, minorityValue);
            cluster.tickUntil(() -> minorityWrite.isFailed());

            // Check the actual status of the minority write
            boolean minorityWriteSucceeded = minorityWrite.isCompleted() && minorityWrite.getResult().success();
            System.out.println("Minority partition write succeeded: " + minorityWriteSucceeded);

            // Check what value is actually in the minority nodes (regardless of client response)
            assertTrue(cluster.storageContainsValue(athens, key, minorityValue));
            assertTrue(cluster.storageContainsValue(byzantium, key, minorityValue));

            // Phase 4: Test majority partition - should succeed
            byte[] majorityValue = "majority_success".getBytes();
            ListenableFuture<SetResponse> majorityWrite = majorityClientConnectedWithCyrene.set(key, majorityValue);
            cluster.tickUntil(() -> majorityWrite.isCompleted());
            assertTrue(majorityWrite.getResult().success(), "Majority partition should succeed");

            // Verify majority nodes have the new data
            assertTrue(cluster.storageContainsValue(cyrene, key, majorityValue), "Cyrene should have majority value");
            assertTrue(cluster.storageContainsValue(delphi, key, majorityValue), "Delphi should have majority value");
            assertTrue(cluster.storageContainsValue(sparta, key, majorityValue), "Sparta should have majority value");


            // Phase 5: Heal the partition
            cluster.healAllPartitions();

            System.out.println("=== Network partition healed ===");

            // Phase 6: After healing, perform a read to see the final state
            ListenableFuture<GetResponse> healedRead = majorityClientConnectedWithCyrene.get(key);
            cluster.tickUntil(() -> healedRead.isCompleted());
            assertTrue(healedRead.getResult().found(), "Data should be retrievable after healing");

            // After healing, the system must resolve conflicts between the values written in the two partitions.
            // The quorum algorithm returns the majority value because it was written later than the minority value.
            // All logical clocks advance by exactly one unit per tick.
            byte[] finalValue = healedRead.getResult().value();
            assertArrayEquals(majorityValue, finalValue);

            System.out.println("=== Test demonstrates real split-brain scenario and conflict resolution ===");
        }
    }


    @Test
    void InPartitionedNetwork_ClockSkewCanOverwriteMajorityWriteAfterPartitionHeals() throws IOException { //shouldPreventSplitBrainDuringNetworkPartition() throws IOException {
        // Define all ProcessIds upfront
        ProcessId athens = ProcessId.of("athens");
        ProcessId byzantium = ProcessId.of("byzantium");
        ProcessId cyrene = ProcessId.of("cyrene");
        ProcessId delphi = ProcessId.of("delphi");
        ProcessId sparta = ProcessId.of("sparta");

        ProcessId minorityClientId = ProcessId.of("minority_client");
        ProcessId majorityClientId = ProcessId.of("majority_client");

        // Scenario: Network partition separates nodes into two groups
        // Group 1: athens, byzantium (minority - 2 nodes)
        // Group 2: cyrene, delphi, sparta (majority - 3 nodes)

        try (Cluster cluster = new Cluster()
                .withProcessIds(athens, byzantium, cyrene, delphi, sparta)
                .useSimulatedNetwork()
                .build(QuorumReplica::new)
                .start()) {

            // Create targeted clients: one for minority, one for majority

            QuorumReplicaClient minorityClientConnectedToAthens = cluster.newClientConnectedTo(minorityClientId, athens, QuorumReplicaClient::new);
            QuorumReplicaClient majorityClientConnectedWithCyrene = cluster.newClientConnectedTo(majorityClientId, cyrene, QuorumReplicaClient::new);

            // Phase 1: Normal operation - store initial data using majority client
            byte[] key = "distributed_ledger".getBytes();
            byte[] initialValue = "genesis_block".getBytes();

            ListenableFuture<SetResponse> initialSet = majorityClientConnectedWithCyrene.set(key, initialValue);
            cluster.tickUntil(() -> initialSet.isCompleted());
            assertTrue(initialSet.getResult().success(), "Initial write should succeed");

            // Verify all nodes have the initial data
            assertTrue(cluster.allNodeStoragesContainValue(key, initialValue), "All nodes should have initial data");

            System.out.println("=== All nodes synchronized with initial data ===");

            // Phase 2: Create network partition
            cluster.partitionNodes(NodeGroup.of(athens, byzantium), NodeGroup.of(cyrene, delphi, sparta)); // <List.of(athens, byzantium), List.of(cyrene, delphi, sparta));

            System.out.println("=== Network partition created ===");
            System.out.println("Minority partition: athens, byzantium (2 nodes)");
            System.out.println("Majority partition: cyrene, delphi, sparta (3 nodes)");

            // Phase 3: Test minority partition - should fail due to lack of quorum
            byte[] minorityValue = "minority_attempt".getBytes();
            ListenableFuture<SetResponse> minorityWrite = minorityClientConnectedToAthens.set(key, minorityValue);
            cluster.tickUntil(() -> minorityWrite.isFailed());

            assertTrue(minorityWrite.isFailed());
            System.out.println("Minority partition write failed.");

            // Check what value is actually in the minority nodes (regardless of client response)
            assertTrue(cluster.storageContainsValue(athens, key, minorityValue));
            assertTrue(cluster.storageContainsValue(byzantium, key, minorityValue));

            // Phase 4: Test majority partition - should succeed
            byte[] majorityValue = "majority_success".getBytes();

            //we set the clock of cyrene 10 ticks before the clock of athens.
            //So the new value will get written with lower timestamp.
            //The value which wrote on minority partition has higher timestamp of athens.
            //When the partition heals, the value with higher timestamp will be overwrtten.
            //The value with lower timestamp will not be overwritten.
            cluster.setTimeForProcess(cyrene, cluster.getStorageValue(athens, key).timestamp() - 10);
            ListenableFuture<SetResponse> majorityWrite = majorityClientConnectedWithCyrene.set(key, majorityValue);
            cluster.tickUntil(() -> majorityWrite.isCompleted());
            assertTrue(majorityWrite.getResult().success(), "Majority partition should succeed");

            // Verify majority nodes have the new data
            assertTrue(cluster.storageContainsValue(cyrene, key, majorityValue), "Cyrene should have majority value");
            assertTrue(cluster.storageContainsValue(delphi, key, majorityValue), "Delphi should have majority value");
            assertTrue(cluster.storageContainsValue(sparta, key, majorityValue), "Sparta should have majority value");


            // Phase 5: Heal the partition
            cluster.healAllPartitions();

            System.out.println("=== Network partition healed ===");

            // Phase 6: After healing, perform a read to see the final state
            ListenableFuture<GetResponse> healedRead = minorityClientConnectedToAthens.get(key);
            cluster.tickUntil(() -> healedRead.isCompleted());
            assertTrue(healedRead.getResult().found(), "Data should be retrievable after healing");

            // After healing, the system must resolve conflicts between the values written in the two partitions.
            // The quorum algorithm returns the minority value because it was written with higher timestamp because
            // the clock of cyrene is 10 ticks before the clock of athens

            byte[] finalValue = healedRead.getResult().value();
            assertArrayEquals(minorityValue, finalValue);

            System.out.println("=== Test demonstrates real split-brain scenario and conflict resolution ===");
        }
    }

    @Test
    //TODO: Restructure following test properly
    @DisplayName("Network Delays: Message ordering with variable network latency")
    void shouldHandleVariableNetworkDelays() throws IOException {
        // Define all ProcessIds upfront
        ProcessId california = ProcessId.of("california");
        ProcessId newyork = ProcessId.of("newyork");
        ProcessId london = ProcessId.of("london");
        ProcessId mobileAppId = ProcessId.of("mobile_app");

        // Scenario: Different network links have different latencies
        // Simulating a geographically distributed system

        try (Cluster cluster = new Cluster()
                .withProcessIds(california, newyork, london)
                .useSimulatedNetwork()
                .build(QuorumReplica::new)
                .start()) {

            // Set up realistic network delays (in ticks)
            // California to New York: 50ms (5 ticks assuming 10ms per tick)
            cluster.setNetworkDelay(california, newyork, 5);
            cluster.setNetworkDelay(newyork, california, 5);

            // California to London: 150ms (15 ticks)
            cluster.setNetworkDelay(california, london, 15);
            cluster.setNetworkDelay(london, california, 15);

            // New York to London: 80ms (8 ticks)
            cluster.setNetworkDelay(newyork, london, 8);
            cluster.setNetworkDelay(london, newyork, 8);

            QuorumReplicaClient client = cluster.newClient(mobileAppId, QuorumReplicaClient::new);

            byte[] key = "user_profile".getBytes();
            byte[] value = "updated_profile_data".getBytes();

            // Perform write operation - should complete despite variable delays
            ListenableFuture<SetResponse> delayedWrite = client.set(key, value);

            // Allow enough ticks for the longest delay path
            cluster.tickUntil(() -> delayedWrite.isCompleted());

            assertTrue(delayedWrite.getResult().success(),
                    "Write should succeed despite network delays");

            // Verify read consistency
            ListenableFuture<GetResponse> delayedRead = client.get(key);
            cluster.tickUntil(() -> delayedRead.isCompleted());

            assertTrue(delayedRead.getResult().found(), "Data should be retrievable");
            assertArrayEquals(value, delayedRead.getResult().value(), "Data should be consistent");
        }
    }
}
