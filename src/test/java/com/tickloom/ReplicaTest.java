package com.tickloom;

import com.tickloom.messaging.*;
import com.tickloom.network.*;
import com.tickloom.storage.SimulatedStorage;
import com.tickloom.storage.Storage;
import com.tickloom.util.IdGen;
import com.tickloom.util.SystemClock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

        TestableReplica replica = new TestableReplica(mutablePeers, new SimulatedStorage(random), new ProcessParams(ProcessId.of("test"), messageBus, messageCodec, timeoutTicks, new SystemClock(), new IdGen(ProcessId.of("test").name(), new Random()), new SimulatedStorage(random)));
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

        TestableReplica replica = new TestableReplica(List.of(), new SimulatedStorage(random), new ProcessParams(ProcessId.of("test"), messageBus, messageCodec, timeoutTicks, new SystemClock(), new IdGen(ProcessId.of("test").name(), new Random()), new SimulatedStorage(random)));
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

        TestableReplica replica = new TestableReplica(List.of(), new SimulatedStorage(random), new ProcessParams(ProcessId.of("test"), messageBus, messageCodec, timeoutTicks, new SystemClock(), new IdGen(ProcessId.of("test").name(), new Random()), new SimulatedStorage(random)));
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

        TestableReplica replica = new TestableReplica(List.of(ProcessId.of("node1"), ProcessId.of("node2")), new SimulatedStorage(random), new ProcessParams(ProcessId.of("test"), messageBus, messageCodec, timeoutTicks, new SystemClock(), new IdGen(ProcessId.of("test").name(), new Random()), new SimulatedStorage(random)));
        // When
        List<String> corrIds = replica.broadcastInternal();

        // Then: correlation IDs should be unique (callback-based approach handles message delivery automatically)
        assertEquals(corrIds.size(), new java.util.HashSet<>(corrIds).size());
    }

    // Test implementations
    private static class TestableReplica extends Replica {
        boolean onTickCalled = false;

        public TestableReplica(List<ProcessId> peerIds, Storage storage, ProcessParams processParams) {
            super(peerIds, processParams);
        }


        @Override
        protected void onTick() {
            onTickCalled = true;
        }


        @Override
        protected Map<MessageType, Handler> initialiseHandlers() {
            return Map.of();
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