package com.tickloom.testkit;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.quorum.QuorumReplica;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.messaging.Message;
import com.tickloom.messaging.MessageType;
import com.tickloom.network.PeerType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClusterTickTest {

    @Test
    void networkDelayIsMeasuredInClusterTicksRegardlessOfNodeCount() throws IOException {
        var athens = ProcessId.of("athens");
        var byzantium = ProcessId.of("byzantium");
        var cyrene = ProcessId.of("cyrene");

        try (var cluster = Cluster.createSimulated(List.of(athens, byzantium, cyrene),
                            (peerIds, processParams) -> new QuorumReplica(peerIds, processParams))) {

            cluster.newClient(ProcessId.of("client"), QuorumReplicaClient::new); // 4 nodes share the simulated network

            cluster.setNetworkDelay(athens, byzantium, 5);
            Message message = Message.of(athens, byzantium, PeerType.SERVER, new MessageType("PROBE"), "probe".getBytes(), "probe-1");
            cluster.getNetwork().send(message);

            for (int i = 0; i < 4; i++) {
                cluster.tick();
            }
            assertTrue(pendingMessages(cluster).contains(message), "Message should still be in flight after 4 cluster ticks");

            cluster.tick();
            assertFalse(pendingMessages(cluster).contains(message), "Message should be delivered on the 5th cluster tick");
        }
    }

    private static List<Message> pendingMessages(Cluster cluster) {
        return ((com.tickloom.network.SimulatedNetwork) cluster.getNetwork()).getPendingMessages();
    }
}
