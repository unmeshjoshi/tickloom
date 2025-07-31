package com.tickloom;

import com.tickloom.messaging.*;
import com.tickloom.network.MessageCodec;
import com.tickloom.storage.Storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

public abstract class Replica extends Process implements MessageHandler {
    protected final List<ProcessId> peerIds;
    protected final MessageBus messageBus;
    protected final MessageCodec messageCodec;
    protected final Storage storage;
    protected final int requestTimeoutTicks;
    protected final RequestWaitingList waitingList;
    // Request tracking infrastructure
    protected final AtomicLong requestIdGenerator = new AtomicLong(0);
    public Replica(ProcessId id, List<ProcessId> peerIds, MessageBus messageBus, MessageCodec messageCodec, Storage storage, int requestTimeoutTicks) {
        super(id);
        this.peerIds = peerIds;
        this.messageBus = messageBus;
        this.messageCodec = messageCodec;
        this.storage = storage;
        this.waitingList = new RequestWaitingList<>(1000);
        this.requestTimeoutTicks = requestTimeoutTicks;
    }

    @Override
    public abstract void onMessageReceived(Message message);

    /**
     * Common tick() processing for all replica types.
     * This handles infrastructure concerns like storage ticks and timeouts.
     * Subclasses can override to add specific logic.
     */
    public void tick() {
        if (messageBus == null || storage == null) {
            return;
        }

        // Tick the main timeout object
        waitingList.tick();


        // Allow subclasses to perform additional tick processing
        onTick();
    }

    /**
     * Hook method for subclasses to perform additional tick processing.
     * This is called after common timeout handling.
     */
    protected void onTick() {
        // Subclasses can override to add specific tick processing
    }

    /**
     * Generates a unique request ID for this replica.
     */
    protected String generateRequestId() {
        return id.name() + "-" + requestIdGenerator.incrementAndGet();
    }


    /**
     * Serializes a payload object to bytes.
     */
    protected byte[] serializePayload(Object payload) {
        return messageCodec.encode(payload);
    }

    /**
     * Deserializes bytes to a payload object.
     */
    protected <T> T deserializePayload(byte[] data, Class<T> type) {
        return messageCodec.decode(data, type);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
                "name='" + id + '\'' +
                ", peers=" + peerIds +
                '}';
    }

    /**
     * Generates a unique correlation ID for internal messages.
     */
    private String generateCorrelationId() {
        return "internal-" + UUID.randomUUID();
        //internal correlation ID should be UUID as it should not use System.currentTimeMillis
        //Multiple internal messages can be sent at the same millisecond.
    }

    /**
     * Gets all nodes in the cluster (peers + self).
     */
    protected List<ProcessId> getAllNodes() {
        List<ProcessId> allNodes = new ArrayList<>(peerIds);
        allNodes.add(id);
        return allNodes;
    }

    /**
     * Generic helper to broadcast an internal request to all nodes (peers + self).
     * It handles correlation ID generation, waiting list registration and message sending.
     */
    protected <T> void broadcastToAllReplicas(AsyncQuorumCallback<T> quorumCallback,
                                              BiFunction<ProcessId, String, Message> messageBuilder) throws IOException {
        for (ProcessId node : getAllNodes()) {
            String internalCorrelationId = generateCorrelationId();
            waitingList.add(internalCorrelationId, quorumCallback);

            Message internalMessage = messageBuilder.apply(node, internalCorrelationId);
            messageBus.sendMessage(internalMessage);
        }
    }

}
