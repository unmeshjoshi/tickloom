package com.tickloom.testkit;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.quorum.QuorumReplica;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ClusterInitialTimeTest {

    @Test
    @DisplayName("Should use default initial clock time of 1000ms")
    void shouldUseDefaultInitialClockTime() throws IOException {
        try (Cluster cluster = Cluster.createSimulated(2, QuorumReplica::new)) {

            ProcessId p1 = ProcessId.of("process-1");
            ProcessId p2 = ProcessId.of("process-2");

            // Verify default initial time is 1000
            assertEquals(1000L, cluster.getClockForProcess(p1).now());
            assertEquals(1000L, cluster.getClockForProcess(p2).now());
        }
    }

    @Test
    @DisplayName("Should allow configuring initial clock time")
    void shouldAllowConfiguringInitialClockTime() throws IOException {
        long customInitialTime = 5000L;
        
        try (Cluster cluster = Cluster.createSimulated(2, QuorumReplica::new)) {
            cluster.setTimeForAllProcesses(customInitialTime);

            ProcessId p1 = ProcessId.of("process-1");
            ProcessId p2 = ProcessId.of("process-2");

            // Verify custom initial time
            assertEquals(customInitialTime, cluster.getClockForProcess(p1).now());
            assertEquals(customInitialTime, cluster.getClockForProcess(p2).now());
        }
    }

    @Test
    @DisplayName("Should reject non-positive initial clock time")
    void shouldRejectNonPositiveInitialClockTime() {
        // Test zero
        IllegalArgumentException exception1 = assertThrows(IllegalArgumentException.class, 
            () -> new Cluster().withInitialClockTime(0L));
        assertEquals("Initial clock time must be positive, got: 0", exception1.getMessage());

        // Test negative
        IllegalArgumentException exception2 = assertThrows(IllegalArgumentException.class, 
            () -> new Cluster().withInitialClockTime(-100L));
        assertEquals("Initial clock time must be positive, got: -100", exception2.getMessage());
    }

    @Test
    @DisplayName("Should apply initial time to both server processes and clients")
    void shouldApplyInitialTimeToServerProcessesAndClients() throws IOException {
        long customInitialTime = 2500L;
        
        try (Cluster cluster = new Cluster()
                .withProcessIds(ProcessId.of("process-1"), ProcessId.of("process-2"))
                .withInitialClockTime(customInitialTime)
                .useSimulatedNetwork()
                .build(QuorumReplica::new)
                .start()) {

            // Verify server processes
            ProcessId p1 = ProcessId.of("process-1");
            assertEquals(customInitialTime, cluster.getClockForProcess(p1).now());

            // Create a clientId and verify it also uses the same initial time
            ProcessId clientId = ProcessId.of("test-clientId");
            cluster.newClient(clientId, QuorumReplicaClient::new);
            
            assertEquals(customInitialTime, cluster.getClockForProcess(clientId).now());
        }
    }
}
