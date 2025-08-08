package com.tickloom;

import com.tickloom.messaging.*;
import com.tickloom.network.MessageCodec;
import com.tickloom.storage.Storage;
import com.tickloom.util.Utils;
import com.tickloom.util.Clock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

public abstract class Replica extends Process {
    protected final List<ProcessId> peerIds;
    protected final Storage storage;
    // Request tracking infrastructure
    protected final AtomicLong requestIdGenerator = new AtomicLong(0);
    
    public Replica(ProcessId id, List<ProcessId> peerIds, MessageBus messageBus, MessageCodec messageCodec, Storage storage, Clock clock, int requestTimeoutTicks) {
        super(id, messageBus, messageCodec, requestTimeoutTicks, clock);
        this.peerIds = List.copyOf(peerIds);
        this.storage = storage;
    }


    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
                "name='" + id + '\'' +
                ", peers=" + peerIds +
                '}';
    }


    /**
     * Generates a unique correlation ID for internal messages
     * for replica to replica communication.
     */
    private String internalCorrelationId() {
        return Utils.generateCorrelationId("internal");
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
                                              BiFunction<ProcessId, String, Message> messageBuilder)  {
        for (ProcessId node : getAllNodes()) {
            String internalCorrelationId = internalCorrelationId();
            waitingList.add(internalCorrelationId, (RequestCallback<Object>) quorumCallback);

            Message internalMessage = messageBuilder.apply(node, internalCorrelationId);
            send(internalMessage);
        }
    }

    protected void send(Message responseMessage) {
        try {
            //if it's a self message immediately process
            if (responseMessage.destination().equals(id)) {
                onMessageReceived(responseMessage);
                return;
            }
            messageBus.sendMessage(responseMessage);
        } catch (IOException e) {
            System.err.println("QuorumReplica: Failed to send response: " + e.getMessage());
        }
    }
}
