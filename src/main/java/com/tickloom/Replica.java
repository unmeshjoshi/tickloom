package com.tickloom;

import com.tickloom.future.TickCompletableFuture;
import com.tickloom.messaging.*;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Predicate;

public abstract class Replica extends Process {
    protected final List<ProcessId> peerIds;

    public Replica(List<ProcessId> peerIds, ProcessParams processParams) {
        super(processParams); // Storage is now handled by Process
        this.peerIds = List.copyOf(peerIds);
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
        return idGen.generateCorrelationId("internal");
    }
    /**
     * Gets all nodes in the cluster (peers + self).
     */
    protected List<ProcessId> getAllNodes() {
        List<ProcessId> allNodes = new ArrayList<>(peerIds);
        allNodes.add(id);
        return allNodes;
    }

    protected <T> QuorumRequestBuilder<T> quorumRequest(MessageType messageType, Object request) {
        return new QuorumRequestBuilder<>(messageType, request);
    }

    @NotNull
    public List<ProcessId> getPeers() {
        return peerIds;
    }

    protected class QuorumRequestBuilder<T> {
        private int requiredQuorum;
        private final MessageType messageType;
        private final Object payload;
        private Predicate<T> successCondition;
        private BiFunction<ProcessId, String, Message> messageBuilder;
        private List<ProcessId> targetNodes;

        public QuorumRequestBuilder(MessageType messageType, Object request) {
            this.messageType = messageType;
            this.payload = request;
            this.targetNodes = getAllNodes();
        }

        public QuorumRequestBuilder<T> withQuorumSize(int requiredQuorum) {
            this.requiredQuorum = requiredQuorum;
            return this;
        }

        public QuorumRequestBuilder<T> countResponseIf(Predicate<T> successCondition) {
            this.successCondition = successCondition;
            return this;
        }

        public QuorumRequestBuilder<T> withMessage(BiFunction<ProcessId, String, Message> messageBuilder) {
            this.messageBuilder = messageBuilder;
            return this;
        }

        public TickCompletableFuture<Map<ProcessId, T>> send() {
            resolveDefaults();
            validate();

            AsyncQuorumCallback<T> quorumCallback = new AsyncQuorumCallback<>(targetNodes.size(), requiredQuorum, successCondition);
            for (ProcessId node : targetNodes) {
                String internalCorrelationId = internalCorrelationId();
                waitingList.add(internalCorrelationId, (RequestCallback<Object>) (RequestCallback) quorumCallback);

                Message internalMessage = createMessage(node, internalCorrelationId, payload, messageType);
                Replica.this.send(internalMessage);
            }
            return quorumCallback.getQuorumFuture();
        }

        public QuorumRequestBuilder<T> to(List<ProcessId> targetNodes) {
            this.targetNodes = List.copyOf(targetNodes);
            return this;
        }

        private void validate() {
            if (requiredQuorum > targetNodes.size()) {
                throw new IllegalArgumentException(
                        "requiredQuorum (" + requiredQuorum + ") cannot exceed targetNodes count (" + targetNodes.size() + ")"
                );
            }
        }

        private void resolveDefaults() {
            if (targetNodes == null || targetNodes.isEmpty()) {
                targetNodes = getAllNodes();
            }
            if (requiredQuorum <= 0) {
                requiredQuorum = (targetNodes.size() / 2) + 1;
            }
        }

    }

    protected void send(Message responseMessage) {
        try {
            messageBus.sendMessage(responseMessage);
            System.out.println("sent message = " + responseMessage);
        } catch (IOException e) {
            System.err.println("QuorumReplica: Failed to send response: " + e.getMessage());
        }
    }
}
