package com.tickloom;

import com.tickloom.future.ListenableFuture;
import com.tickloom.messaging.*;

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

    protected <T> QuorumBroadcastBuilder<T> broadcast() {
        return new QuorumBroadcastBuilder<>();
    }

    protected class QuorumBroadcastBuilder<T> {
        private int requiredQuorum = (int) (getAllNodes().size() / 2) + 1;
        private Predicate<T> successCondition;
        private BiFunction<ProcessId, String, Message> messageBuilder;

        public QuorumBroadcastBuilder<T> withQuorumSize(int requiredQuorum) {
            this.requiredQuorum = requiredQuorum;
            return this;
        }

        public QuorumBroadcastBuilder<T> responseConsideredSuccessful(Predicate<T> successCondition) {
            this.successCondition = successCondition;
            return this;
        }

        public QuorumBroadcastBuilder<T> withMessage(BiFunction<ProcessId, String, Message> messageBuilder) {
            this.messageBuilder = messageBuilder;
            return this;
        }

        public ListenableFuture<Map<ProcessId, T>> send() {
            AsyncQuorumCallback<T> quorumCallback = new AsyncQuorumCallback<>(getAllNodes().size(), requiredQuorum, successCondition);
            for (ProcessId node : getAllNodes()) {
                String internalCorrelationId = internalCorrelationId();
                waitingList.add(internalCorrelationId, (RequestCallback<Object>) (RequestCallback) quorumCallback);

                Message internalMessage = messageBuilder.apply(node, internalCorrelationId);
                Replica.this.send(internalMessage);
            }
            return quorumCallback.getQuorumFuture();
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
