package com.tickloom;

import com.tickloom.future.TickCompletableFuture;
import com.tickloom.messaging.*;
import com.tickloom.network.*;
import com.tickloom.storage.SimulatedStorage;
import com.tickloom.storage.Storage;
import com.tickloom.util.IdGen;
import com.tickloom.util.SystemClock;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ReplicaTest {


    @Test
    void shouldCreateDefensiveCopyOfPeers() {
        // Given
        List<ProcessId> mutablePeers = new ArrayList(List.of(ProcessId.of("peer1")));
        TestableReplica replica = createTestReplica(mutablePeers);
        // When
        mutablePeers.clear();

        // Then
        assertEquals(1, replica.peerIds.size()); // Should not be affected by external changes
    }
    @Test
    void tickShouldInvokeOnTickHookMethod() {
        // Given: timeout set to 1 tick for fast expiry

        TestableReplica replica = createTestReplica(List.of());
        // When: perform tick
        replica.tick();

        // Then: onTick hook called and waiting list processed (expired request removed)
        assertTrue(replica.onTickCalled);
    }
    @Test
    void shouldTickRequestWaitingList() {
        // Given: timeout set to 1 tick for fast expiry
        TestableReplica replica = createTestReplica(List.of());
        String key = "dummy";
        replica.addDummyPendingRequest(key);
        assertEquals(1, replica.getWaitingListSize());

        // When: perform tick
        replica.tick();

        // Then: onTick hook called and waiting list processed (expired request removed)
        assertEquals(0, replica.getWaitingListSize());
    }

    @Test
    void shouldSendRequestsToAllNodesWhenTargetsNotSpecified()  {
        // Given
        SimulatedNetwork network = SimulatedNetwork.noLossNetwork(new Random());
        List<ProcessId> peerIds = List.of(ProcessId.of("node1"), ProcessId.of("node2"));
        TestableReplica replica = createTestReplica(peerIds, network);
        // When
        replica.<Message>quorumRequest(new MessageType("INTERNAL_GET_REQUEST"), new byte[0])
            .countResponseIf(msg -> true)
            .send();

        assertMessageSentToAllNodesIncludingSelf(replica, network);
    }

    @Test
    void shouldSendRequestsToSpecifiedNodes()  {
        // Given
        SimulatedNetwork network = SimulatedNetwork.noLossNetwork(new Random());
        List<ProcessId> peerIds = List.of(ProcessId.of("node1"), ProcessId.of("node2"));
        TestableReplica replica = createTestReplica(peerIds, network);
        // When
        replica.<Message>quorumRequest(new MessageType("INTERNAL_GET_REQUEST"), new byte[0])
                .countResponseIf(msg -> true)
                .to(replica.getPeers()) //sends request only to peers, not to self.
                .send();

        assertMessageSentToAllPeers(replica, network);
        assertNull(replica.selfMessage);
    }

    @Test
    public void waitForSpecifiedQuorumSize() {
        List<ProcessId> peerIds = List.of(ProcessId.of("node1"), ProcessId.of("node2"), ProcessId.of("node3"));
        TestableReplica replica = createTestReplica(peerIds);

        MessageType requestType = new MessageType("INTERNAL_GET_REQUEST");
        MessageType responseType = new MessageType("INTERNAL_GET_RESPONSE");

        TickCompletableFuture<Map<ProcessId, Message>> responseFuture = replica.<Message>quorumRequest(requestType, new byte[0])
                .countResponseIf(msg -> true)
                .withQuorumSize(peerIds.size()) //wait for all the responses
                .to(replica.getPeers()) //sends request only to peers, not to self.
                .send();

        replica.respond(peerIds.get(0), responseMessage(peerIds.get(0), responseType));
        assertFalse(responseFuture.isCompleted());

        replica.respond(peerIds.get(1), responseMessage(peerIds.get(1), responseType));
        assertFalse(responseFuture.isCompleted());

        replica.respond(peerIds.get(2), responseMessage(peerIds.get(2), responseType));
        assertTrue(responseFuture.isCompleted());
        assertEquals(3, responseFuture.getResult().size());
    }

    @Test
    public void waitForDefaultMajorityQuorumSize() {
        List<ProcessId> peerIds = List.of(ProcessId.of("node1"), ProcessId.of("node2"), ProcessId.of("node3"));
        TestableReplica replica = createTestReplica(peerIds);

        MessageType requestType = new MessageType("INTERNAL_GET_REQUEST");
        MessageType responseType = new MessageType("INTERNAL_GET_RESPONSE");

        // No quorum size specified. It will be defaulted to majority of target nodes (3 / 2 + 1 = 2).
        TickCompletableFuture<Map<ProcessId, Message>> responseFuture = replica.<Message>quorumRequest(requestType, new byte[0])
                .countResponseIf(msg -> true)
                .to(replica.getPeers()) // sends request only to peers, not to self.
                .send();

        // 1st response: 1 of 3 (not majority)
        replica.respond(peerIds.get(0), responseMessage(peerIds.get(0), responseType));
        assertFalse(responseFuture.isCompleted());

        // 2nd response: 2 of 3 (majority reached!)
        replica.respond(peerIds.get(1), responseMessage(peerIds.get(1), responseType));
        assertTrue(responseFuture.isCompleted());
        assertEquals(2, responseFuture.getResult().size());
    }

    private static Message responseMessage(ProcessId from, MessageType type) {
        return Message.of(
                from,
                ProcessId.of("test"),
                PeerType.SERVER,
                type,
                new byte[0],
                "dummy-corr-id"
        );
    }

    private static void assertMessageSentToAllNodesIncludingSelf(TestableReplica replica, SimulatedNetwork network) {
        assertMessageSentToAllPeers(replica, network);
        assertMessageSentToSelf(replica);
    }

    private static void assertMessageSentToSelf(TestableReplica replica) {
        assertEquals(replica.selfMessage.messageType(), new MessageType("INTERNAL_GET_REQUEST"));
    }

    private static void assertMessageSentToAllPeers(TestableReplica replica, SimulatedNetwork network) {
        var targetNodes = network.getPendingMessages().stream().map(message -> message.destination()).toList();
        assertEquals(replica.getPeers(), targetNodes);
    }

    @NotNull
    private static TestableReplica createTestReplica(List<ProcessId> peerIds) {
        SimulatedNetwork network = SimulatedNetwork.noLossNetwork(new Random());
        return createTestReplica(peerIds, network);
    }

    private static TestableReplica createTestReplica(List<ProcessId> peerIds, SimulatedNetwork network) {
        ProcessId selfId = ProcessId.of("test");
        int timeoutTicks = 1;
        Random random = new Random();
        JsonMessageCodec messageCodec = new JsonMessageCodec();

        MessageBus messageBus = new MessageBus(network, messageCodec);

        return new TestableReplica(peerIds, new SimulatedStorage(random),
                new ProcessParams(selfId,
                        messageBus, messageCodec, timeoutTicks,
                        new SystemClock(), new IdGen(selfId.name(),
                        new Random()), new SimulatedStorage(random)), network);
    }

    // Test implementations
    private static class TestableReplica extends Replica {
        private final SimulatedNetwork network;
        boolean onTickCalled = false;
        private Message selfMessage;

        public TestableReplica(List<ProcessId> peerIds, Storage storage, ProcessParams processParams, SimulatedNetwork network) {
            super(peerIds, processParams);
            this.network = network;
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


        @Override
        public void onMessageReceived(Message message) {
            this.selfMessage = message;
        }

        void respond(ProcessId fromNode, Object responsePayload) {
            // Find the message that was sent to this peer to get its correlation ID
            Message outgoing = network.getPendingMessages().stream()
                    .filter(m -> m.destination().equals(fromNode))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No pending message for " + fromNode));
            waitingList.handleResponse(outgoing.correlationId(), responsePayload, fromNode);
        }

        public int getWaitingListSize() {
            return waitingList.size();
        }
    }
}