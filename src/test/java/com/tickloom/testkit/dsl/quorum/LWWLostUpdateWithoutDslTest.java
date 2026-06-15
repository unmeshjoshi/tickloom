package com.tickloom.testkit.dsl.quorum;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.quorum.QuorumReplica;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.testkit.Cluster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LWWLostUpdateWithoutDslTest {

    private static final ProcessId ATHENS    = ProcessId.of("athens");
    private static final ProcessId BYZANTIUM = ProcessId.of("byzantium");
    private static final ProcessId CYRENE    = ProcessId.of("cyrene");

    private static final ProcessId ALICE  = ProcessId.of("alice");
    private static final ProcessId BOB    = ProcessId.of("bob");
    private static final ProcessId READER = ProcessId.of("reader");

    private static final String KEY = "x";

    @Test
    @DisplayName("LWW: bob's later-clock write silently overwrites alice's later real-time write (Without DSL)")
    void lww_lost_update_from_server_clock_skew() throws IOException {
        Cluster cluster = new Cluster()
                .withProcessIds(Arrays.asList(ATHENS, BYZANTIUM, CYRENE))
                .useSimulatedNetwork()
                .build(QuorumReplica::new);

        cluster.start();

        try {
            cluster.tickUntil(cluster::areAllNodesInitialized);

            cluster.setTimeForProcess(ATHENS, 1000L);
            cluster.setTimeForProcess(BYZANTIUM, 2000L);

            QuorumReplicaClient alice = cluster.newClientConnectedTo(ALICE, ATHENS, QuorumReplicaClient::new);
            QuorumReplicaClient bob = cluster.newClientConnectedTo(BOB, BYZANTIUM, QuorumReplicaClient::new);
            QuorumReplicaClient reader = cluster.newClientConnectedTo(READER, ATHENS, QuorumReplicaClient::new);

            TickCompletableFuture<com.tickloom.algorithms.replication.quorum.SetResponse> bobWrite = bob.set(KEY.getBytes(java.nio.charset.StandardCharsets.UTF_8), "B".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            cluster.tickUntilComplete(bobWrite);

            TickCompletableFuture<com.tickloom.algorithms.replication.quorum.SetResponse> aliceWrite = alice.set(KEY.getBytes(java.nio.charset.StandardCharsets.UTF_8), "A".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            cluster.tickUntilComplete(aliceWrite);

            TickCompletableFuture<com.tickloom.algorithms.replication.quorum.GetResponse> read = reader.get(KEY.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            cluster.tickUntilComplete(read);

            assertEquals("B", new String(read.getResult().value(), java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            cluster.close();
        }
    }
}
