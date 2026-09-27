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
    void shouldSendHeterogeneousPayloadsPerNode() {
        // Given
        SimulatedNetwork network = SimulatedNetwork.noLossNetwork(new Random());
        List<ProcessId> peerIds = List.of(ProcessId.of("node1"), ProcessId.of("node2"));
        TestableReplica replica = createTestReplica(peerIds, network);

        // When
        replica.<Message>quorumRequest(new MessageType("SHARD_PUT"))
                .to(replica.getPeers())
                .withPayloadPerNode((node, index) -> "shard-" + index)
                .send();

        // Then
        List<Message> pending = network.getPendingMessages();
        assertEquals(2, pending.size());
        assertEquals("shard-0", replica.deserializePayload(pending.get(0).payload(), String.class));
        assertEquals("shard-1", replica.deserializePayload(pending.get(1).payload(), String.class));
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

    @Test
    public void shouldWaitForAllResponsesWhenUsingWaitForAll() {
        List<ProcessId> peerIds = List.of(ProcessId.of("node1"), ProcessId.of("node2"), ProcessId.of("node3"));
        TestableReplica replica = createTestReplica(peerIds);

        MessageType requestType = new MessageType("INTERNAL_GET_REQUEST");
        MessageType responseType = new MessageType("INTERNAL_GET_RESPONSE");

        TickCompletableFuture<Map<ProcessId, Message>> responseFuture = replica.<Message>quorumRequest(requestType, new byte[0])
                .to(replica.getPeers())
                .waitForAll()
                .send();

        // 1 of 3 responses
        replica.respond(peerIds.get(0), responseMessage(peerIds.get(0), responseType));
        assertFalse(responseFuture.isCompleted());

        // 2 of 3 responses (would satisfy majority, but not waitForAll)
        replica.respond(peerIds.get(1), responseMessage(peerIds.get(1), responseType));
        assertFalse(responseFuture.isCompleted());

        // 3 of 3 responses (100% completed!)
        replica.respond(peerIds.get(2), responseMessage(peerIds.get(2), responseType));
        assertTrue(responseFuture.isCompleted());
        assertEquals(3, responseFuture.getResult().size());
    }

    @Test
    public void shouldBroadcastRequestToAllNodesAndCompleteWhenAllRespond() {
        List<ProcessId> peerIds = List.of(ProcessId.of("node1"), ProcessId.of("node2"));
        TestableReplica replica = createTestReplica(peerIds);

        MessageType requestType = new MessageType("CATALOG_LIST_REQUEST");
        MessageType responseType = new MessageType("CATALOG_LIST_RESPONSE");

        // Broadcast to all nodes (peers + self = 3 nodes)
        TickCompletableFuture<Map<ProcessId, Message>> responseFuture =
                replica.broadcastRequest(requestType, "list-all");

        // Respond from self (1 of 3)
        replica.respond(replica.id, responseMessage(replica.id, responseType));
        assertFalse(responseFuture.isCompleted());

        // Respond from peer 1 (2 of 3)
        replica.respond(peerIds.get(0), responseMessage(peerIds.get(0), responseType));
        assertFalse(responseFuture.isCompleted());

        // Respond from peer 2 (3 of 3 - completes!)
        replica.respond(peerIds.get(1), responseMessage(peerIds.get(1), responseType));
        assertTrue(responseFuture.isCompleted());
        assertEquals(3, responseFuture.getResult().size());
    }

    @Test
    public void shouldTolerateNodeRejectionWhenQuorumStillPossible() {
        List<ProcessId> peerIds = List.of(ProcessId.of("node1"), ProcessId.of("node2"), ProcessId.of("node3"));
        TestableReplica replica = createTestReplica(peerIds);

        MessageType requestType = new MessageType("INTERNAL_GET_REQUEST");
        MessageType responseType = new MessageType("INTERNAL_GET_RESPONSE");

        TickCompletableFuture<Map<ProcessId, Message>> responseFuture = replica.<Message>quorumRequest(requestType, new byte[0])
                .to(replica.getPeers())
                .countResponseIf(msg -> "OK".equals(new String(msg.payload())))
                .send();

        // Node 1 rejects -> 1 failure (max allowed: 3 - 2 = 1) -> future remains pending!
        replica.respond(peerIds.get(0), responseMessage(peerIds.get(0), responseType, "REJECT".getBytes()));
        assertFalse(responseFuture.isCompleted());

        // Node 2 accepts -> 1 success -> future remains pending!
        replica.respond(peerIds.get(1), responseMessage(peerIds.get(1), responseType, "OK".getBytes()));
        assertFalse(responseFuture.isCompleted());

        // Node 3 accepts -> 2 successes (quorum reached!) -> future completes!
        replica.respond(peerIds.get(2), responseMessage(peerIds.get(2), responseType, "OK".getBytes()));
        assertTrue(responseFuture.isCompleted());
        assertEquals(2, responseFuture.getResult().size());
        assertTrue(responseFuture.getResult().containsKey(peerIds.get(1)));
        assertTrue(responseFuture.getResult().containsKey(peerIds.get(2)));
    }

    @Test
    public void shouldFailFastWhenRejectionsExceedMaxFailures() {
        List<ProcessId> peerIds = List.of(ProcessId.of("node1"), ProcessId.of("node2"), ProcessId.of("node3"));
        TestableReplica replica = createTestReplica(peerIds);

        MessageType requestType = new MessageType("INTERNAL_GET_REQUEST");
        MessageType responseType = new MessageType("INTERNAL_GET_RESPONSE");

        TickCompletableFuture<Map<ProcessId, Message>> responseFuture = replica.<Message>quorumRequest(requestType, new byte[0])
                .to(replica.getPeers())
                .countResponseIf(msg -> "OK".equals(new String(msg.payload())))
                .send();

        // Node 1 rejects -> 1 failure (max allowed: 3 - 2 = 1) -> future remains pending!
        replica.respond(peerIds.get(0), responseMessage(peerIds.get(0), responseType, "REJECT".getBytes()));
        assertFalse(responseFuture.isCompleted());

        // Node 2 rejects -> 2 failures (> 1 max allowed) -> FAIL FAST immediately without waiting for Node 3!
        replica.respond(peerIds.get(1), responseMessage(peerIds.get(1), responseType, "REJECT".getBytes()));
        assertTrue(responseFuture.isFailed());
        assertNotNull(responseFuture.getException());
    }

    @Test
    public void shouldBroadcastOneWayMessageToAllNodes() {
        SimulatedNetwork network = SimulatedNetwork.noLossNetwork(new Random());
        List<ProcessId> peerIds = List.of(ProcessId.of("node1"), ProcessId.of("node2"));
        TestableReplica replica = createTestReplica(peerIds, network);
        MessageType pingType = new MessageType("HEARTBEAT_PING");

        replica.broadcast(pingType, "ping");

        List<Message> pending = network.getPendingMessages();
        assertEquals(2, pending.size());
        assertTrue(pending.stream().anyMatch(m -> m.destination().equals(peerIds.get(0)) && m.messageType().equals(pingType)));
        assertTrue(pending.stream().anyMatch(m -> m.destination().equals(peerIds.get(1)) && m.messageType().equals(pingType)));

        assertNotNull(replica.selfMessage);
        assertEquals(pingType, replica.selfMessage.messageType());

        assertEquals(0, replica.getWaitingListSize());
    }

    @Test
    public void shouldBroadcastOneWayMessageToSpecificTargets() {
        SimulatedNetwork network = SimulatedNetwork.noLossNetwork(new Random());
        List<ProcessId> peerIds = List.of(ProcessId.of("node1"), ProcessId.of("node2"));
        TestableReplica replica = createTestReplica(peerIds, network);
        MessageType pingType = new MessageType("HEARTBEAT_PING");

        replica.broadcast(List.of(peerIds.get(0)), pingType, "ping");

        List<Message> pending = network.getPendingMessages();
        assertEquals(1, pending.size());
        assertEquals(peerIds.get(0), pending.get(0).destination());
        assertEquals(pingType, pending.get(0).messageType());
        assertNull(replica.selfMessage);

        assertEquals(0, replica.getWaitingListSize());
    }

    private static Message responseMessage(ProcessId from, MessageType type) {
        return responseMessage(from, type, new byte[0]);
    }

    private static Message responseMessage(ProcessId from, MessageType type, byte[] payload) {
        return Message.of(
                from,
                ProcessId.of("test"),
                PeerType.SERVER,
                type,
                payload,
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

        TestableReplica replica = new TestableReplica(peerIds, new SimulatedStorage(random),
                new ProcessParams(selfId,
                        messageBus, messageCodec, timeoutTicks,
                        new SystemClock(), new IdGen(selfId.name(),
                        new Random()), new SimulatedStorage(random)), network);
        replica.start();
        return replica;
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
            if (fromNode.equals(this.id)) {
                waitingList.handleResponse(selfMessage.correlationId(), responsePayload, fromNode);
                return;
            }
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