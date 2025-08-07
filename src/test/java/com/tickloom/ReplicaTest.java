package com.tickloom;

import com.tickloom.messaging.*;
import com.tickloom.network.*;
import com.tickloom.storage.SimulatedStorage;
import com.tickloom.storage.Storage;
import com.tickloom.util.SystemClock;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ReplicaTest {
    @Test
    void shouldCreateDefensiveCopyOfPeers() {
        // Given
        List<ProcessId> mutablePeers = new ArrayList(List.of(ProcessId.of("test")));
        int timeoutTicks = 1;
        Random random = new Random();
        JsonMessageCodec messageCodec = new JsonMessageCodec();

        Network network = SimulatedNetwork.noLossNetwork(random);
        MessageBus messageBus = new MessageBus(network, messageCodec);

        TestableReplica replica = new TestableReplica(ProcessId.of("test"), mutablePeers, messageBus, messageCodec, new SimulatedStorage(random), timeoutTicks);
        // When
        mutablePeers.clear();

        // Then
        assertEquals(1, replica.peerIds.size()); // Should not be affected by external changes
    }
    @Test
    void tickShouldInvokeOnTick() {
        // Given: timeout set to 1 tick for fast expiry
        int timeoutTicks = 1;
        Random random = new Random();
        JsonMessageCodec messageCodec = new JsonMessageCodec();

        Network network = SimulatedNetwork.noLossNetwork(random);
        MessageBus messageBus = new MessageBus(network, messageCodec);

        TestableReplica replica = new TestableReplica(ProcessId.of("test"), List.of(), messageBus, messageCodec, new SimulatedStorage(random), timeoutTicks);
        // When: perform tick
        replica.tick();

        // Then: onTick hook called and waiting list processed (expired request removed)
        assertTrue(replica.onTickCalled);
    }
    @Test
    void shouldRequestWaitingList() {
        // Given: timeout set to 1 tick for fast expiry
        int timeoutTicks = 1;
        Random random = new Random();
        JsonMessageCodec messageCodec = new JsonMessageCodec();

        Network network = SimulatedNetwork.noLossNetwork(random);
        MessageBus messageBus = new MessageBus(network, messageCodec);

        TestableReplica replica = new TestableReplica(ProcessId.of("test"), List.of(), messageBus, messageCodec, new SimulatedStorage(random), timeoutTicks);
        String key = "dummy";
        replica.addDummyPendingRequest(key);
        assertEquals(1, replica.getWaitingListSize());

        // When: perform tick
        replica.tick();

        // Then: onTick hook called and waiting list processed (expired request removed)
        assertEquals(0, replica.getWaitingListSize());
    }

    @Test
    void shouldBroadcastInternalRequestsToAllNodes()  {
        // Given
        int timeoutTicks = 1;
        Random random = new Random();
        JsonMessageCodec messageCodec = new JsonMessageCodec();

        Network network = SimulatedNetwork.noLossNetwork(random);
        MessageBus messageBus = new MessageBus(network, messageCodec);

        TestableReplica replica = new TestableReplica(ProcessId.of("test"), List.of(ProcessId.of("node1"), ProcessId.of("node2")), messageBus, messageCodec, new SimulatedStorage(random), timeoutTicks);
        // When
        List<String> corrIds = replica.broadcastInternal();

        // Then: correlation IDs should be unique (callback-based approach handles message delivery automatically)
        assertEquals(corrIds.size(), new java.util.HashSet<>(corrIds).size());
    }

    // Test implementations
    private static class TestableReplica extends Replica {
        boolean onTickCalled = false;

        public TestableReplica(ProcessId id, List<ProcessId> peerIds, MessageBus messageBus, MessageCodec messageCodec, Storage storage, int requestTimeoutTicks) {
            super(id, peerIds, messageBus, messageCodec, storage, new SystemClock(), requestTimeoutTicks);
        }


        @Override
        public void onMessageReceived(Message message) {
            // No-op for tests
        }

        @Override
        protected void onTick() {
            onTickCalled = true;
        }

        void addDummyPendingRequest(String key) {
            waitingList.add(key, new RequestCallback<>() {
                @Override public void onResponse(Object response, ProcessId fromNode) {}
                @Override public void onError(Exception error) {}
            });
        }

        int getWaitingListSize() {
            return waitingList.size();
        }

        List<String> broadcastInternal()  {
            java.util.List<String> captured = new java.util.ArrayList<>();
            AsyncQuorumCallback<Message> cb = new AsyncQuorumCallback<>(getAllNodes().size(), msg -> true);
            broadcastToAllReplicas(cb, (destinationId, correlationId) -> {
                captured.add(correlationId);
                return Message.of(id, destinationId, PeerType.SERVER, new MessageType("INTERNAL_GET_REQUEST"), new byte[0], correlationId);
            });
            return captured;
        }
    }
}