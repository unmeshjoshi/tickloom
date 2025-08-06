package com.tickloom.algorithms.replication.quorum;

import com.tickloom.ProcessId;
import com.tickloom.Replica;
import com.tickloom.future.ListenableFuture;
import com.tickloom.messaging.*;
import com.tickloom.network.MessageCodec;
import com.tickloom.storage.Storage;
import com.tickloom.storage.VersionedValue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Quorum-based replica implementation for distributed key-value store.
 * This implementation uses majority quorum consensus for read and write operations.
 * 
 * Based on the quorum consensus algorithm where operations require majority
 * agreement from replicas before completing successfully.
 */
public class QuorumReplica extends Replica {

    public QuorumReplica(ProcessId id, List<ProcessId> peerIds, MessageBus messageBus, MessageCodec messageCodec, Storage storage, int requestTimeoutTicks) {
        super(id, peerIds, messageBus, messageCodec, storage, requestTimeoutTicks);
    }

    @Override
    public void onMessageReceived(Message message) {
        if (messageBus == null || storage == null) {
            // Skip message processing if not fully configured
            return;
        }

        var messageType = message.messageType();
        if (messageType.equals(QuorumMessageTypes.CLIENT_GET_REQUEST)) {
            handleClientGetRequest(message);
        } else if (messageType.equals(QuorumMessageTypes.CLIENT_SET_REQUEST)) {
            handleClientSetRequest(message);
        } else if (messageType.equals(QuorumMessageTypes.INTERNAL_GET_REQUEST)) {
            handleInternalGetRequest(message);
        } else if (messageType.equals(QuorumMessageTypes.INTERNAL_SET_REQUEST)) {
            handleInternalSetRequest(message);
        } else if (messageType.equals(QuorumMessageTypes.INTERNAL_GET_RESPONSE)) {
            handleInternalGetResponse(message);
        } else if (messageType.equals(QuorumMessageTypes.INTERNAL_SET_RESPONSE)) {
            handleInternalSetResponse(message);
        }
        // else ignore unknown message types
    }

    // Client request handlers

    private void handleClientGetRequest(Message message) {
        String correlationId = message.correlationId();
        GetRequest clientRequest = deserializePayload(message.payload(), GetRequest.class);
        ProcessId clientAddress = message.source();

        logIncomingGetRequest(clientRequest, correlationId, clientAddress);

        var quorumCallback = createGetQuorumCallback();
        quorumCallback.onSuccess(responses -> sendSuccessGetResponse(clientRequest, correlationId, clientAddress, responses))
                     .onFailure(error -> sendFailureGetResponse(clientRequest, correlationId, clientAddress, error));

        try {
            broadcastToAllReplicas(quorumCallback, (node, internalCorrelationId) -> {
                InternalGetRequest internalRequest = new InternalGetRequest(clientRequest.key(), internalCorrelationId);
                return Message.of(
                        id, node, message.peerType(), QuorumMessageTypes.INTERNAL_GET_REQUEST,
                        serializePayload(internalRequest), internalCorrelationId
                );
            });
        } catch (IOException e) {
            sendFailureGetResponse(clientRequest, correlationId, clientAddress, e);
        }
    }

    private AsyncQuorumCallback<InternalGetResponse> createGetQuorumCallback() {
        List<ProcessId> allNodes = getAllNodes();
        return new AsyncQuorumCallback<>(
                allNodes.size(),
                response -> response != null && response.value() != null
        );
    }

    private void logIncomingGetRequest(GetRequest req, String correlationId, ProcessId clientAddr) {
        System.out.println("QuorumReplica: Processing client GET request - keyLength: " + req.key().length +
                ", correlationId: " + correlationId + ", from: " + clientAddr);
    }

    private void sendSuccessGetResponse(GetRequest req, String correlationId, ProcessId clientAddr,
                                        Map<ProcessId, InternalGetResponse> responses) {
        VersionedValue latestValue = getLatestValueFromResponses(responses);
        
        logSuccessfulGetResponse(req, correlationId, latestValue);
        
        GetResponse response = new GetResponse(req.key(), 
                latestValue != null ? latestValue.value() : null, 
                latestValue != null);
        
        Message responseMessage = Message.of(
                id, clientAddr, com.tickloom.network.PeerType.SERVER, QuorumMessageTypes.CLIENT_GET_RESPONSE,
                serializePayload(response), correlationId
        );
        
        try {
            messageBus.sendMessage(responseMessage);
        } catch (IOException e) {
            System.err.println("QuorumReplica: Failed to send GET response: " + e.getMessage());
        }
    }

    private void sendFailureGetResponse(GetRequest req, String correlationId, ProcessId clientAddr, Throwable error) {
        logFailedGetResponse(req, correlationId, error);
        
        GetResponse response = new GetResponse(req.key(), null, false);
        
        Message responseMessage = Message.of(
                id, clientAddr, com.tickloom.network.PeerType.SERVER, QuorumMessageTypes.CLIENT_GET_RESPONSE,
                serializePayload(response), correlationId
        );
        
        try {
            messageBus.sendMessage(responseMessage);
        } catch (IOException e) {
            System.err.println("QuorumReplica: Failed to send GET error response: " + e.getMessage());
        }
    }

    private void logSuccessfulGetResponse(GetRequest req, String correlationId, VersionedValue latestValue) {
        System.out.println("QuorumReplica: Successful GET response - keyLength: " + req.key().length +
                ", correlationId: " + correlationId + ", hasValue: " + (latestValue != null));
    }

    private void logFailedGetResponse(GetRequest req, String correlationId, Throwable error) {
        System.out.println("QuorumReplica: Failed GET response - keyLength: " + req.key().length +
                ", correlationId: " + correlationId + ", error: " + error.getMessage());
    }

    private void handleClientSetRequest(Message message) {
        String correlationId = message.correlationId();
        SetRequest clientRequest = deserializePayload(message.payload(), SetRequest.class);
        ProcessId clientAddress = message.source();

        logIncomingSetRequest(clientRequest, correlationId, clientAddress);

        var quorumCallback = createSetQuorumCallback();
        quorumCallback.onSuccess(responses -> sendSuccessSetResponseToClient(clientRequest, correlationId, clientAddress))
                     .onFailure(error -> sendFailureSetResponseToClient(clientRequest, correlationId, clientAddress, error));

        try {
            long timestamp = System.currentTimeMillis();
            broadcastToAllReplicas(quorumCallback, (node, internalCorrelationId) -> {
                InternalSetRequest internalRequest = new InternalSetRequest(
                        clientRequest.key(), clientRequest.value(), timestamp, internalCorrelationId);
                return Message.of(
                        id, node, message.peerType(), QuorumMessageTypes.INTERNAL_SET_REQUEST,
                        serializePayload(internalRequest), internalCorrelationId
                );
            });
        } catch (IOException e) {
            e.printStackTrace();
            sendFailureSetResponseToClient(clientRequest, correlationId, clientAddress, e);
        }
    }

    private void logIncomingSetRequest(SetRequest req, String correlationId, ProcessId clientAddr) {
        System.out.println("QuorumReplica: Processing client SET request - keyLength: " + req.key().length +
                ", valueLength: " + req.value().length + ", correlationId: " + correlationId + ", from: " + clientAddr);
    }

    private AsyncQuorumCallback<InternalSetResponse> createSetQuorumCallback() {
        List<ProcessId> allNodes = getAllNodes();
        return new AsyncQuorumCallback<>(
                allNodes.size(),
                response -> response != null && response.success()
        );
    }

    private void sendSuccessSetResponseToClient(SetRequest req, String correlationId, ProcessId clientAddr) {
        logSuccessfulSetResponse(req, correlationId);
        
        SetResponse response = new SetResponse(req.key(), true);
        
        Message responseMessage = Message.of(
                id, clientAddr, com.tickloom.network.PeerType.SERVER, QuorumMessageTypes.CLIENT_SET_RESPONSE,
                serializePayload(response), correlationId
        );
        
        try {
            messageBus.sendMessage(responseMessage);
        } catch (IOException e) {
            System.err.println("QuorumReplica: Failed to send SET response: " + e.getMessage());
        }
    }

    private void sendFailureSetResponseToClient(SetRequest req, String correlationId, ProcessId clientAddr, Throwable error) {
        logFailedSetResponse(req, correlationId, error);
        
        SetResponse response = new SetResponse(req.key(), false);
        
        Message responseMessage = Message.of(
                id, clientAddr, com.tickloom.network.PeerType.SERVER, QuorumMessageTypes.CLIENT_SET_RESPONSE,
                serializePayload(response), correlationId
        );
        
        try {
            messageBus.sendMessage(responseMessage);
        } catch (IOException e) {
            System.err.println("QuorumReplica: Failed to send SET error response: " + e.getMessage());
        }
    }

    private void logSuccessfulSetResponse(SetRequest req, String correlationId) {
        System.out.println("QuorumReplica: Successful SET response - keyLength: " + req.key().length +
                ", correlationId: " + correlationId);
    }

    private void logFailedSetResponse(SetRequest req, String correlationId, Throwable error) {
        System.out.println("QuorumReplica: Failed SET response - keyLength: " + req.key().length +
                ", correlationId: " + correlationId + ", error: " + error.getMessage());
    }

    // Helper method to extract the latest value from quorum responses
    private VersionedValue getLatestValueFromResponses(Map<ProcessId, InternalGetResponse> responses) {
        VersionedValue latestValue = null;
        long latestTimestamp = -1;
        
        for (InternalGetResponse response : responses.values()) {
            if (response.value() != null) {
                long timestamp = response.value().timestamp();
                if (timestamp > latestTimestamp) {
                    latestTimestamp = timestamp;
                    latestValue = response.value();
                }
            }
        }
        
        return latestValue;
    }

    // Internal request handlers

    private void handleInternalGetRequest(Message message) {
        InternalGetRequest getRequest = deserializePayload(message.payload(), InternalGetRequest.class);
        
        System.out.println("QuorumReplica: Processing internal GET request - keyLength: " + getRequest.key().length +
                ", correlationId: " + getRequest.correlationId() + ", from: " + message.source());

        // Perform local storage operation
        ListenableFuture<VersionedValue> future = storage.get(getRequest.key());

        future.onSuccess(value -> {
            String valueStr = value != null ? "found" : "not found";
            System.out.println("QuorumReplica: Internal GET completed - keyLength: " + getRequest.key().length +
                    ", value: " + valueStr + ", correlationId: " + getRequest.correlationId());

            InternalGetResponse response = new InternalGetResponse(getRequest.key(), value, getRequest.correlationId());
            Message responseMessage = Message.of(
                    id, message.source(), message.peerType(), QuorumMessageTypes.INTERNAL_GET_RESPONSE,
                    serializePayload(response), getRequest.correlationId()
            );
            
            try {
                messageBus.sendMessage(responseMessage);
            } catch (IOException e) {
                System.err.println("QuorumReplica: Failed to send internal GET response: " + e.getMessage());
            }
        }).onFailure(error -> {
            System.out.println("QuorumReplica: Internal GET failed - keyLength: " + getRequest.key().length +
                    ", error: " + error.getMessage() + ", correlationId: " + getRequest.correlationId());

            InternalGetResponse response = new InternalGetResponse(getRequest.key(), null, getRequest.correlationId());
            Message responseMessage = Message.of(
                    id, message.source(), message.peerType(), QuorumMessageTypes.INTERNAL_GET_RESPONSE,
                    serializePayload(response), getRequest.correlationId()
            );
            
            try {
                messageBus.sendMessage(responseMessage);
            } catch (IOException e) {
                System.err.println("QuorumReplica: Failed to send internal GET error response: " + e.getMessage());
            }
        });
    }

    private void handleInternalSetRequest(Message message) {
        InternalSetRequest setRequest = deserializePayload(message.payload(), InternalSetRequest.class);
        VersionedValue value = new VersionedValue(setRequest.value(), setRequest.timestamp());

        System.out.println("QuorumReplica: Processing internal SET request - keyLength: " + setRequest.key().length +
                ", valueLength: " + setRequest.value().length + ", timestamp: " + setRequest.timestamp() +
                ", correlationId: " + setRequest.correlationId() + ", from: " + message.source());

        // Perform local storage operation
        ListenableFuture<Boolean> future = storage.set(setRequest.key(), value);

        future.onSuccess(success -> {
            System.out.println("QuorumReplica: Internal SET completed - keyLength: " + setRequest.key().length +
                    ", success: " + success + ", correlationId: " + setRequest.correlationId());

            InternalSetResponse response = new InternalSetResponse(setRequest.key(), success, setRequest.correlationId());
            Message responseMessage = Message.of(
                    id, message.source(), message.peerType(), QuorumMessageTypes.INTERNAL_SET_RESPONSE,
                    serializePayload(response), setRequest.correlationId()
            );
            
            try {
                messageBus.sendMessage(responseMessage);
            } catch (IOException e) {
                System.err.println("QuorumReplica: Failed to send internal SET response: " + e.getMessage());
            }
        }).onFailure(error -> {
            System.out.println("QuorumReplica: Internal SET failed - keyLength: " + setRequest.key().length +
                    ", error: " + error.getMessage() + ", correlationId: " + setRequest.correlationId());

            InternalSetResponse response = new InternalSetResponse(setRequest.key(), false, setRequest.correlationId());
            Message responseMessage = Message.of(
                    id, message.source(), message.peerType(), QuorumMessageTypes.INTERNAL_SET_RESPONSE,
                    serializePayload(response), setRequest.correlationId()
            );
            
            try {
                messageBus.sendMessage(responseMessage);
            } catch (IOException e) {
                System.err.println("QuorumReplica: Failed to send internal SET error response: " + e.getMessage());
            }
        });
    }

    private void handleInternalGetResponse(Message message) {
        InternalGetResponse response = deserializePayload(message.payload(), InternalGetResponse.class);

        System.out.println("QuorumReplica: Processing internal GET response - keyLength: " + response.key().length +
                ", internalCorrelationId: " + response.correlationId() + ", from: " + message.source());

        // Route the response to the RequestWaitingList
        waitingList.handleResponse(message.correlationId(), response, message.source());
    }

    private void handleInternalSetResponse(Message message) {
        InternalSetResponse response = deserializePayload(message.payload(), InternalSetResponse.class);

        System.out.println("QuorumReplica: Processing internal SET response - keyLength: " + response.key().length +
                ", success: " + response.success() + ", internalCorrelationId: " + response.correlationId() +
                ", from: " + message.source());
        
        waitingList.handleResponse(message.correlationId(), response, message.source());
    }
}
