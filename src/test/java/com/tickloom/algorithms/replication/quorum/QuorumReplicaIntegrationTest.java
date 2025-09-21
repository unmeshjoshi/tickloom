package com.tickloom.algorithms.replication.quorum;

import com.tickloom.ProcessId;
import com.tickloom.testkit.Cluster;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static com.tickloom.testkit.ClusterAssertions.assertEventually;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class QuorumReplicaIntegrationTest {
    @Test
    public void testSetAndGetRequestWithSimulatedNetwork() throws IOException {
        var key = "test-key".getBytes();
        var value = "test-name".getBytes();

        try (Cluster cluster = new Cluster()
                .withNumProcesses(3)
                .useSimulatedNetwork() //use simulated network
                .build(QuorumReplica::new)
                .start()) {

            var client = cluster.newClient(ProcessId.of("Client1"), QuorumReplicaClient::new);
            // Test SET operation
            var setFuture = client.set(key, value);
            // Debug: Check initial state
            System.out.println("Initial pending requests: " + client.getPendingRequestCount());
            assertTrue(setFuture.isPending(), "Set future should be pending initially");

            assertEventually(cluster,() -> setFuture.isCompleted() && setFuture.getResult().success());

            var getFuture = client.get(key);
            assertEventually(cluster,() -> getFuture.isCompleted() && getFuture.getResult().found());

            var getResponse = getFuture.getResult();
            assertArrayEquals(key, getResponse.key(), "Response key should match request key");
            assertArrayEquals(value, getResponse.value(), "Response name should match request name");
        }
    }

    /**
     * Uses Java NIO and real network
     * @see com.tickloom.network.NioNetwork
     */
    @Test
    public void testSetAndGetRequestWithRealNetwork() throws IOException {
        var key = "test-key".getBytes();
        var value = "test-name".getBytes();

        try (Cluster cluster = new Cluster()
                .withNumProcesses(3)
                .build(QuorumReplica::new)
                .start()) {

            var client = cluster.newClient(ProcessId.of("Client1"), QuorumReplicaClient::new);
            // Test SET operation
            var setFuture = client.set(key, value);
            // Debug: Check initial state
            System.out.println("Initial pending requests: " + client.getPendingRequestCount());
            assertTrue(setFuture.isPending(), "Set future should be pending initially");

            assertEventually(cluster,() -> setFuture.isCompleted());

            var setResponse = setFuture.getResult();
            assertTrue(setResponse.success(), "Set operation should succeed");
            assertArrayEquals(key, setResponse.key(), "Response key should match request key");

            var getFuture = client.get(key);
            assertEventually(cluster,() -> getFuture.isCompleted());
            var getResponse = getFuture.getResult();
            assertArrayEquals(key, getResponse.key(), "Response key should match request key");
            assertArrayEquals(value, getResponse.value(), "Response name should match request name");
        }
    }
}
