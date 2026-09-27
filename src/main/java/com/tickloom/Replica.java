package com.tickloom;

import com.tickloom.future.TickCompletableFuture;
import com.tickloom.messaging.*;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    protected <T> QuorumRequestBuilder<T> quorumRequest(MessageType messageType) {
        return new QuorumRequestBuilder<>(messageType);
    }

    protected <T> QuorumRequestBuilder<T> quorumRequest(MessageType messageType, Object samePayload) {
        return this.<T>quorumRequest(messageType).withSamePayload(samePayload);
    }

    /**
     * Sends a scatter-gather request with the same payload to all nodes, completing when 100% of responses arrive.
     */
    protected <T> TickCompletableFuture<Map<ProcessId, T>> broadcastRequest(MessageType type, Object samePayload) {
        return this.<T>quorumRequest(type)
                .withSamePayload(samePayload)
                .waitForAll()
                .send();
    }

    @NotNull
    public List<ProcessId> getPeers() {
        return peerIds;
    }

    protected class QuorumRequestBuilder<T> {
        private final MessageType messageType;
        private int requiredQuorum;
        private boolean waitForAll = false;
        private Predicate<T> successCondition;
        private List<ProcessId> targetNodes;
        private BiFunction<ProcessId, Integer, Object> payloadFunction;

        public QuorumRequestBuilder(MessageType messageType) {
            this.messageType = Objects.requireNonNull(messageType, "messageType cannot be null");
            this.targetNodes = getAllNodes();
        }

        public QuorumRequestBuilder<T> withSamePayload(Object payload) {
            this.payloadFunction = (node, index) -> payload;
            return this;
        }

        public QuorumRequestBuilder<T> withPayloadPerNode(BiFunction<ProcessId, Integer, Object> payloadFunction) {
            this.payloadFunction = Objects.requireNonNull(payloadFunction, "payloadFunction cannot be null");
            return this;
        }

        public QuorumRequestBuilder<T> withQuorumSize(int requiredQuorum) {
            this.requiredQuorum = requiredQuorum;
            return this;
        }

        public QuorumRequestBuilder<T> waitForAll() {
            this.waitForAll = true;
            return this;
        }

        public QuorumRequestBuilder<T> countResponseIf(Predicate<T> successCondition) {
            this.successCondition = successCondition;
            return this;
        }

        public TickCompletableFuture<Map<ProcessId, T>> send() {
            resolveDefaults();
            validate();

            AsyncQuorumCallback<T> quorumCallback = new AsyncQuorumCallback<>(targetNodes.size(), requiredQuorum, successCondition);
            for (int i = 0; i < targetNodes.size(); i++) {
                ProcessId node = targetNodes.get(i);
                String internalCorrelationId = internalCorrelationId();
                waitingList.add(internalCorrelationId, (RequestCallback<Object>) (RequestCallback) quorumCallback);

                Object payload = payloadFunction.apply(node, i);
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
            if (payloadFunction == null) {
                throw new IllegalStateException(
                        "Payload must be specified via withSamePayload(...) or withPayloadPerNode(...)"
                );
            }
            if (requiredQuorum > targetNodes.size()) {
                throw new IllegalArgumentException(
                        "requiredQuorum (" + requiredQuorum + ") cannot exceed targetNodes count (" + targetNodes.size() + ")"
                );
            }
        }

        private void resolveDefaults() {
            this.targetNodes = resolveTargetNodes();
            this.requiredQuorum = resolveRequiredQuorum();
            this.successCondition = resolveSuccessCondition();
        }

        private List<ProcessId> resolveTargetNodes() {
            return (targetNodes != null && !targetNodes.isEmpty()) ? targetNodes : getAllNodes();
        }

        private int resolveRequiredQuorum() {
            if (waitForAll) {
                return targetNodes.size();
            }
            if (isQuorumSizeSpecified()) {
                return requiredQuorum;
            }
            return majorityOf(targetNodes);
        }

        private boolean isQuorumSizeSpecified() {
            return requiredQuorum > 0;
        }

        private int majorityOf(List<ProcessId> nodes) {
            return (nodes.size() / 2) + 1;
        }

        private Predicate<T> resolveSuccessCondition() {
            return (successCondition != null) ? successCondition : msg -> true;
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
