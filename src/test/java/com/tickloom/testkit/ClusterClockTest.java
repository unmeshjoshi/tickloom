package com.tickloom.testkit;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.quorum.QuorumReplica;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.util.StubClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates how to use individual process clocks in cluster tests.
 * Shows various scenarios for testing time-based behavior with different
 * clock times across processes.
 */
class ClusterClockTest {

    @Test
    @DisplayName("Should allow individual process clock control")
    void shouldAllowIndividualProcessClockControl() throws IOException {
        // Given
        try (Cluster cluster = new Cluster()
                .withNumProcesses(3)
                .useSimulatedNetwork()
                .build(QuorumReplica::new)
                .start()) {

            // When - Set different times for each process (note: all start at default 1000L)
            ProcessId process1 = ProcessId.of("process-1");
            ProcessId process2 = ProcessId.of("process-2");
            ProcessId process3 = ProcessId.of("process-3");

            // Verify they start at default initial time
            assertEquals(1000L, cluster.getClockForProcess(process1).now());
            assertEquals(1000L, cluster.getClockForProcess(process2).now());
            assertEquals(1000L, cluster.getClockForProcess(process3).now());

            // Set different times
            cluster.setTimeForProcess(process1, 1500L);
            cluster.setTimeForProcess(process2, 2000L);
            cluster.setTimeForProcess(process3, 3000L);

            // Then - Verify each process has different times
            assertEquals(1500L, cluster.getClockForProcess(process1).now());
            assertEquals(2000L, cluster.getClockForProcess(process2).now());
            assertEquals(3000L, cluster.getClockForProcess(process3).now());
        }
    }

    @Test
    @DisplayName("Should allow advancing individual process clocks")
    void shouldAllowAdvancingIndividualProcessClocks() throws IOException {
        try (Cluster cluster = new Cluster()
                .withNumProcesses(3)
                .useSimulatedNetwork()

                .build(QuorumReplica::new)
                .start()) {

            ProcessId process1 = ProcessId.of("process-1");
            ProcessId process2 = ProcessId.of("process-2");

            // Set initial times
            cluster.setTimeForProcess(process1, 1000L);
            cluster.setTimeForProcess(process2, 1000L);

            // Advance only process1's clock
            cluster.advanceTimeForProcess(process1, 500L);

            // Verify only process1's time changed
            assertEquals(1500L, cluster.getClockForProcess(process1).now());
            assertEquals(1000L, cluster.getClockForProcess(process2).now());
        }
    }

    @Test
    @DisplayName("Should allow setting time for all processes")
    void shouldAllowSettingTimeForAllProcesses() throws IOException {
        try (Cluster cluster = new Cluster()
                .withNumProcesses(3)
                .useSimulatedNetwork()

                .build(QuorumReplica::new)
                .start()) {

            // Set same time for all processes
            cluster.setTimeForAllProcesses(5000L);

            // Verify all processes have the same time
            Map<ProcessId, StubClock> allClocks = cluster.getAllProcessClocks();
            allClocks.values().forEach(clock -> 
                assertEquals(5000L, clock.now(), "All processes should have the same time"));
        }
    }

    @Test
    @DisplayName("Should allow advancing all process clocks")
    void shouldAllowAdvancingAllProcessClocks() throws IOException {
        try (Cluster cluster = new Cluster()
                .withNumProcesses(3)
                .useSimulatedNetwork()

                .build(QuorumReplica::new)
                .start()) {

            // Set initial time for all
            cluster.setTimeForAllProcesses(1000L);
            
            // Advance all clocks
            cluster.advanceTimeForAllProcesses(2000L);

            // Verify all processes advanced by the same amount
            Map<ProcessId, StubClock> allClocks = cluster.getAllProcessClocks();
            allClocks.values().forEach(clock -> 
                assertEquals(3000L, clock.now(), "All processes should have advanced to 3000L"));
        }
    }

    @Test
    @DisplayName("Should throw exception when accessing non-existent process")
    void shouldThrowExceptionWhenAccessingNonExistentProcess() throws IOException {
        try (Cluster cluster = new Cluster()
                .withNumProcesses(3)
                .useSimulatedNetwork()
                .build(QuorumReplica::new)
                .start()) {

            ProcessId nonExistentProcess = ProcessId.of("process-999");

            // Should throw IllegalArgumentException for non-existent process
            assertThrows(IllegalArgumentException.class, 
                () -> cluster.getClockForProcess(nonExistentProcess),
                "Should throw exception when process does not exist");
                
            assertThrows(IllegalArgumentException.class, 
                () -> cluster.setTimeForProcess(nonExistentProcess, 1000L),
                "Should throw exception when process does not exist");
        }
    }

    @Test
    @DisplayName("Should demonstrate timestamp ordering scenario")
    void shouldDemonstrateTimestampOrderingScenario() throws IOException {
        try (Cluster cluster = new Cluster()
                .withNumProcesses(3)
                .useSimulatedNetwork()

                .build(QuorumReplica::new)
                .start()) {

            ProcessId process1 = ProcessId.of("process-1");
            ProcessId process2 = ProcessId.of("process-2");
            ProcessId process3 = ProcessId.of("process-3");

            // Scenario: Simulate a network partition where process3 is behind in time
            cluster.setTimeForProcess(process1, 10000L);
            cluster.setTimeForProcess(process2, 10000L);
            cluster.setTimeForProcess(process3, 5000L);  // Behind by 5 seconds

            // Create a clientId to test with
            QuorumReplicaClient client = cluster.newClient(
                ProcessId.of("TestClient"), 
                QuorumReplicaClient::new
            );

            // Test operations with different timestamps
            byte[] key = "test-key".getBytes();
            byte[] value = "test-name".getBytes();

            // This would result in different timestamps from different processes
            // which is useful for testing timestamp-based conflict resolution
            
            // Verify the clocks are set as expected
            assertEquals(10000L, cluster.getClockForProcess(process1).now());
            assertEquals(10000L, cluster.getClockForProcess(process2).now());
            assertEquals(5000L, cluster.getClockForProcess(process3).now());
            
            // Advance process3 to simulate catching up
            cluster.advanceTimeForProcess(process3, 5000L);
            assertEquals(10000L, cluster.getClockForProcess(process3).now());
        }
    }
}
