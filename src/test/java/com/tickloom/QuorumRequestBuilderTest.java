package com.tickloom;

import com.tickloom.messaging.*;
import com.tickloom.network.*;
import com.tickloom.util.IdGen;
import com.tickloom.util.SystemClock;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class QuorumRequestBuilderTest {

    private SimulatedNetwork network;
    private TestableReplica replica;

    @Test
    void shouldSendQuorumMessages() throws Exception {
        replica = createReplicaWithPeers("peer1");

        // When: We build and send a quorum request
        replica.<String>quorumRequest(new MessageType("TEST_REQ"), new byte[0])
                .send();

        // Then
        assertMessagesSentToNetworkFor("peer1", "test");
        assertWaitingListHasEntriesFor(replica, 2);
    }

    private TestableReplica createReplicaWithPeers(String... peerNames) {
        List<ProcessId> peers = java.util.Arrays.stream(peerNames).map(ProcessId::of).toList();
        int timeoutTicks = 1;
        Random random = new Random();
        JsonMessageCodec messageCodec = new JsonMessageCodec();
        network = SimulatedNetwork.noLossNetwork(random);

        TestableReplica newReplica = new TestableReplica(
                new ArrayList<>(peers),
                new ProcessParams(ProcessId.of("test"), network, messageCodec, timeoutTicks, new SystemClock(), new IdGen(ProcessId.of("test").name(), new Random()))
        );
        newReplica.start();
        return newReplica;
    }

    // The message to self goes through the network like the others.
    private void assertMessagesSentToNetworkFor(String... expectedDestinations) {
        List<ProcessId> destinations = network.getPendingMessages().stream().map(Message::destination).toList();
        assertEquals(java.util.Arrays.stream(expectedDestinations).map(ProcessId::of).toList(), destinations);
    }

    private void assertWaitingListHasEntriesFor(TestableReplica replica, int expectedCount) {
        assertEquals(expectedCount, replica.getWaitingListSize(), "Waiting list size mismatch");
    }

    // Test implementations
    private static class TestableReplica extends Replica {
        public TestableReplica(List<ProcessId> peerIds, ProcessParams processParams) {
            super(peerIds, processParams);
        }

        @Override
        protected Map<MessageType, Handler> initialiseHandlers() {
            return Map.of();
        }

        int getWaitingListSize() {
            return waitingList.size();
        }
    }
}
