package com.tickloom.algorithms.replication.quorum;

import com.tickloom.ProcessId;
import com.tickloom.future.ListenableFuture;
import com.tickloom.testkit.Cluster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class QuorumReplicaClientTest {
    @BeforeEach
    void setUp() {
    }

    @Test
    public void testSetAndGetRequest() throws IOException {
        byte[] key = "test-key".getBytes();
        byte[] value = "test-value".getBytes();

        Cluster cluster = new Cluster()
                .withNumProcesses(3)
                .build(QuorumReplica::new)
                .start();

        QuorumReplicaClient client
                = cluster.newClient(ProcessId.of("Client1"), QuorumReplicaClient::new);
        // Test SET operation
        ListenableFuture<SetResponse> setFuture = client.set(key, value);
        // Debug: Check initial state
        System.out.println("Initial pending requests: " + client.getPendingRequestCount());
        assertTrue(setFuture.isPending(), "Set future should be pending initially");

        cluster.tickUntil(() -> setFuture.isCompleted());
        assertTrue(setFuture.isCompleted(), "Set operation should complete");
        SetResponse setResponse = setFuture.getResult();
        assertTrue(setResponse.success(), "Set operation should succeed");
        assertArrayEquals(key, setResponse.key(), "Response key should match request key");

        ListenableFuture<GetResponse> getFuture = client.get(key);
        cluster.tickUntil(() -> getFuture.isCompleted());
        assertTrue(getFuture.isCompleted(), "Get operation should complete");
        GetResponse getResponse = getFuture.getResult();
        assertArrayEquals(key, getResponse.key(), "Response key should match request key");
        assertArrayEquals(value, getResponse.value(), "Response value should match request value");
    }

}
