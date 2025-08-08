package com.tickloom.algorithms.replication.quorum;

import com.tickloom.ProcessId;
import com.tickloom.Replica;
import com.tickloom.future.ListenableFuture;
import com.tickloom.messaging.*;
import com.tickloom.network.MessageCodec;
import com.tickloom.network.PeerType;
import com.tickloom.storage.Storage;
import com.tickloom.storage.VersionedValue;
import com.tickloom.util.Clock;

import java.util.List;
import java.util.Map;

/**
 * Quorum-based replica implementation for distributed key-value store.
 * This implementation uses majority quorum consensus for read and write operations.
 * <p>
 * Based on the quorum consensus algorithm where operations require majority
 * agreement from replicas before completing successfully.
 */
public class QuorumReplica extends Replica {

    public QuorumReplica(ProcessId id, List<ProcessId> peerIds, MessageBus messageBus, MessageCodec messageCodec, Storage storage, Clock clock, int requestTimeoutTicks) {
        super(id, peerIds, messageBus, messageCodec, storage, clock, requestTimeoutTicks);
    }

    @Override
    public void onMessageReceived(Message message) {
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
        } else {
            // Unknown message type
            System.out.println("QuorumReplica: Received unknown message type: " + messageType);
        }
        // else ignore unknown message types
    }

    // Client request handlers

    private void handleClientGetRequest(Message message) {
        var correlationId = message.correlationId();
        var clientRequest = deserializePayload(message.payload(), GetRequest.class);
        var clientId = message.source();

        logIncomingGetRequest(clientRequest, correlationId, clientId);

        var quorumCallback = createGetQuorumCallback();
        quorumCallback
                .onSuccess(
                        responses -> sendSuccessGetResponse(clientRequest, correlationId, clientId, responses))
                .onFailure(
                        error -> sendFailureGetResponse(clientRequest, correlationId, clientId, error));

        broadcastToAllReplicas(quorumCallback, (node, internalCorrelationId) -> {
            var internalRequest = new InternalGetRequest(clientRequest.key(), internalCorrelationId);
            return createMessage(node, internalCorrelationId, internalRequest, QuorumMessageTypes.INTERNAL_GET_REQUEST);
        });
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

        Message responseMessage = createMessage(clientAddr, correlationId, response, QuorumMessageTypes.CLIENT_GET_RESPONSE);

        send(responseMessage);
    }

    private void sendFailureGetResponse(GetRequest req, String correlationId, ProcessId clientAddr, Throwable error) {
        logFailedGetResponse(req, correlationId, error);

        GetResponse response = new GetResponse(req.key(), null, false);

        Message responseMessage = createMessage(clientAddr, correlationId, response, QuorumMessageTypes.CLIENT_GET_RESPONSE);

        send(responseMessage);
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

        long timestamp = clock.now();
        broadcastToAllReplicas(quorumCallback, (node, internalCorrelationId) -> {
            InternalSetRequest internalRequest = new InternalSetRequest(
                    clientRequest.key(), clientRequest.value(), timestamp, internalCorrelationId);
            return createMessage(node, internalCorrelationId, internalRequest, QuorumMessageTypes.INTERNAL_SET_REQUEST);
        });
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

        Message responseMessage = createMessage(clientAddr, correlationId, response, QuorumMessageTypes.CLIENT_SET_RESPONSE);

        send(responseMessage);
    }

    private void sendFailureSetResponseToClient(SetRequest req, String correlationId, ProcessId clientAddr, Throwable error) {
        logFailedSetResponse(req, correlationId, error);

        SetResponse response = new SetResponse(req.key(), false);

        Message responseMessage = createMessage(clientAddr, correlationId, response, QuorumMessageTypes.CLIENT_SET_RESPONSE);

        send(responseMessage);
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
        future.handle((value, error) -> {
            sendInternalGetResponse(message, value, error, getRequest);
        });
    }

    private void sendInternalGetResponse(Message incomingMessage, VersionedValue value, Throwable error, InternalGetRequest getRequest) {

        logInternalGetResponse(value, error, getRequest);
        //value will be null if not found or error
        InternalGetResponse response = new InternalGetResponse(getRequest.key(), value, getRequest.correlationId());
        Message responseMessage = createResponseMessage(incomingMessage, response, QuorumMessageTypes.INTERNAL_GET_RESPONSE);


        send(responseMessage);

    }

    private static void logInternalGetResponse(VersionedValue value, Throwable error, InternalGetRequest getRequest) {
        if (error == null) {
            String valueStr = value != null ? "found" : "not found";
            System.out.println("QuorumReplica: Internal GET completed - keyLength: " + getRequest.key().length +
                    ", value: " + valueStr + ", correlationId: " + getRequest.correlationId());

        } else {
            System.out.println("QuorumReplica: Internal GET failed - keyLength: " + getRequest.key().length +
                    ", error: " + error.getMessage() + ", correlationId: " + getRequest.correlationId());
        }
    }

    private void handleInternalSetRequest(Message message) {
        InternalSetRequest setRequest = deserializePayload(message.payload(), InternalSetRequest.class);
        VersionedValue value = new VersionedValue(setRequest.value(), setRequest.timestamp());

        System.out.println("QuorumReplica: Processing internal SET request - keyLength: " + setRequest.key().length +
                ", valueLength: " + setRequest.value().length + ", timestamp: " + setRequest.timestamp() +
                ", correlationId: " + setRequest.correlationId() + ", from: " + message.source());

        // Perform local storage operation
        ListenableFuture<Boolean> future = storage.set(setRequest.key(), value);
        future.handle((success, error)
                -> sendInternalSetResponse(message, success, error, setRequest));
    }

    private void sendInternalSetResponse(Message message, Boolean success, Throwable error, InternalSetRequest setRequest) {
        logInternalSetResponse(success, error, setRequest);

        InternalSetResponse response = new InternalSetResponse(setRequest.key(), success, setRequest.correlationId());
        //success will be false if error
        Message responseMessage = createResponseMessage(message, response, QuorumMessageTypes.INTERNAL_SET_RESPONSE);

        send(responseMessage);

    }

    private static void logInternalSetResponse(Boolean success, Throwable error, InternalSetRequest setRequest) {
        if (error == null) {
            System.out.println("QuorumReplica: Internal SET completed - keyLength: " + setRequest.key().length +
                    ", success: " + success + ", correlationId: " + setRequest.correlationId());

        } else {
            System.out.println("QuorumReplica: Internal SET failed - keyLength: " + setRequest.key().length +
                    ", error: " + error.getMessage() + ", correlationId: " + setRequest.correlationId());

        }
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
