package com.tickloom;

import com.tickloom.algorithms.replication.quorum.QuorumReplica;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.algorithms.replication.quorum.QuorumSimulationRunner;
import com.tickloom.testkit.Cluster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Random;

import static com.tickloom.testkit.ClusterAssertions.assertEventually;
import static org.junit.jupiter.api.Assertions.*;

public class SingleRequestIssuerTest {

    private Cluster cluster;
    private QuorumSimulationRunner simulationRunner;
    private QuorumReplicaClient client;
    private SingleRequestIssuer<QuorumReplicaClient> requestIssuer;

    @BeforeEach
    void setUp() throws IOException {
        cluster = new Cluster()
                .withNumProcesses(3)
                .useSimulatedNetwork()
                .build(QuorumReplica::new)
                .start();

        // The simulation runner needs a cluster to direct the client requests to.
        simulationRunner = new QuorumSimulationRunner(123L);

        client = cluster.newClient(ProcessId.of("test-client"), QuorumReplicaClient::new);
        requestIssuer = new SingleRequestIssuer<>(simulationRunner, client, new Random(42L));
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @Test
    void shouldIssueSingleRequestAndComplete() {
        assertFalse(requestIssuer.hasPendingRequests(), "Should have no pending requests initially");

        requestIssuer.issueRequest();

        assertTrue(requestIssuer.hasPendingRequests(), "Should have pending requests after issuing one");
        assertTrue(requestIssuer.isInFlight(), "A request should be in flight");

        // Wait for the request to complete
        assertEventually(cluster, () -> !requestIssuer.hasPendingRequests());

        assertFalse(requestIssuer.isInFlight(), "Should have no in-flight requests after completion");
    }

    @Test
    void shouldQueueRequestsAndProcessSequentially() {
        final int numRequests = 5;

        // Issue multiple requests
        for (int i = 0; i < numRequests; i++) {
            requestIssuer.issueRequest();
        }

        assertTrue(requestIssuer.hasPendingRequests(), "Should have pending requests after queuing");

        // Wait for all requests to complete
        assertEventually(cluster, () -> !requestIssuer.hasPendingRequests()); // Use a longer timeout

        assertFalse(requestIssuer.isInFlight(), "Should have no in-flight requests after all are processed");
    }
}
