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
    protected Map<MessageType, Handler> initialiseHandlers() {
        return Map.of(
                QuorumMessageTypes.CLIENT_GET_REQUEST, this::handleClientGetRequest,
                QuorumMessageTypes.CLIENT_SET_REQUEST, this::handleClientSetRequest,
                QuorumMessageTypes.INTERNAL_GET_RESPONSE, this::handleInternalGetResponse,
                QuorumMessageTypes.INTERNAL_SET_RESPONSE, this::handleInternalSetResponse,
                QuorumMessageTypes.INTERNAL_GET_REQUEST, this::handleInternalGetRequest,
                QuorumMessageTypes.INTERNAL_SET_REQUEST, this::handleInternalSetRequest);
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
            var internalRequest = new InternalGetRequest(clientRequest.key());
            return createMessage(node, internalCorrelationId, internalRequest, QuorumMessageTypes.INTERNAL_GET_REQUEST);
        });
    }

    private AsyncQuorumCallback<InternalGetResponse> createGetQuorumCallback() {
        var allNodes = getAllNodes();
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
        var latestValue = getLatestValueFromResponses(responses);

        logSuccessfulGetResponse(req, correlationId, latestValue);

        var response = new GetResponse(req.key(),
                latestValue != null ? latestValue.value() : null,
                latestValue != null);

        var responseMessage = createMessage(clientAddr, correlationId, response, QuorumMessageTypes.CLIENT_GET_RESPONSE);

        send(responseMessage);
    }

    private void sendFailureGetResponse(GetRequest req, String correlationId, ProcessId clientAddr, Throwable error) {
        logFailedGetResponse(req, correlationId, error);

        var response = new GetResponse(req.key(), null, false);

        var responseMessage = createMessage(clientAddr, correlationId, response, QuorumMessageTypes.CLIENT_GET_RESPONSE);

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
        var correlationId = message.correlationId();
        var clientRequest = deserializePayload(message.payload(), SetRequest.class);
        var clientAddress = message.source();

        logIncomingSetRequest(clientRequest, correlationId, clientAddress);

        var quorumCallback = createSetQuorumCallback();
        quorumCallback.onSuccess(responses -> sendSuccessSetResponseToClient(clientRequest, correlationId, clientAddress))
                .onFailure(error -> sendFailureSetResponseToClient(clientRequest, correlationId, clientAddress, error));

        var timestamp = clock.now();
        broadcastToAllReplicas(quorumCallback, (node, internalCorrelationId) -> {
            var internalRequest = new InternalSetRequest(
                    clientRequest.key(), clientRequest.value(), timestamp);
            return createMessage(node, internalCorrelationId, internalRequest, QuorumMessageTypes.INTERNAL_SET_REQUEST);
        });
    }

    private void logIncomingSetRequest(SetRequest req, String correlationId, ProcessId clientAddr) {
        System.out.println("QuorumReplica: Processing client SET request - keyLength: " + req.key().length +
                ", valueLength: " + req.value().length + ", correlationId: " + correlationId + ", from: " + clientAddr);
    }

    private AsyncQuorumCallback<InternalSetResponse> createSetQuorumCallback() {
        var allNodes = getAllNodes();
        return new AsyncQuorumCallback<>(
                allNodes.size(),
                response -> response != null && response.success()
        );
    }

    private void sendSuccessSetResponseToClient(SetRequest req, String correlationId, ProcessId clientAddr) {
        logSuccessfulSetResponse(req, correlationId);

        var response = new SetResponse(req.key(), true);

        var responseMessage = createMessage(clientAddr, correlationId, response, QuorumMessageTypes.CLIENT_SET_RESPONSE);

        send(responseMessage);
    }

    private void sendFailureSetResponseToClient(SetRequest req, String correlationId, ProcessId clientAddr, Throwable error) {
        logFailedSetResponse(req, correlationId, error);

        var response = new SetResponse(req.key(), false);

        var responseMessage = createMessage(clientAddr, correlationId, response, QuorumMessageTypes.CLIENT_SET_RESPONSE);

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
        var getRequest = deserializePayload(message.payload(), InternalGetRequest.class);
        System.out.println("QuorumReplica: Processing internal GET request - keyLength: " + getRequest.key().length
                + ", from: " + message.source());
        // Perform local storage operation
        var future = storage.get(getRequest.key());
        future.handle((value, error) -> {
            sendInternalGetResponse(message, value, error, getRequest);
        });
    }

    private void sendInternalGetResponse(Message incomingMessage, VersionedValue value, Throwable error, InternalGetRequest getRequest) {

        logInternalGetResponse(value, error, getRequest);
        //value will be null if not found or error
        var response = new InternalGetResponse(getRequest.key(), value);
        var responseMessage = createResponseMessage(incomingMessage, response, QuorumMessageTypes.INTERNAL_GET_RESPONSE);


        send(responseMessage);

    }

    private static void logInternalGetResponse(VersionedValue value, Throwable error, InternalGetRequest getRequest) {
        if (error == null) {
            String valueStr = value != null ? "found" : "not found";
            System.out.println("QuorumReplica: Internal GET completed - keyLength: " + getRequest.key().length +
                    ", value: " + valueStr );

        } else {
            System.out.println("QuorumReplica: Internal GET failed - keyLength: " + getRequest.key().length +
                    ", error: " + error.getMessage());
        }
    }

    private void handleInternalSetRequest(Message message) {
        var setRequest = deserializePayload(message.payload(), InternalSetRequest.class);
        var value = new VersionedValue(setRequest.value(), setRequest.timestamp());

        System.out.println("QuorumReplica: Processing internal SET request - keyLength: " + setRequest.key().length +
                ", valueLength: " + setRequest.value().length + ", timestamp: " + setRequest.timestamp() +
                 ", from: " + message.source());

        // Perform local storage operation
        var future = storage.set(setRequest.key(), value);
        future.handle((success, error)
                -> sendInternalSetResponse(message, success, error, setRequest));
    }

    private void sendInternalSetResponse(Message message, Boolean success, Throwable error, InternalSetRequest setRequest) {
        logInternalSetResponse(success, error, setRequest);

        var response = new InternalSetResponse(setRequest.key(), success);
        //success will be false if error
        var responseMessage = createResponseMessage(message, response, QuorumMessageTypes.INTERNAL_SET_RESPONSE);

        send(responseMessage);

    }

    private static void logInternalSetResponse(Boolean success, Throwable error, InternalSetRequest setRequest) {
        if (error == null) {
            System.out.println("QuorumReplica: Internal SET completed - keyLength: " + setRequest.key().length +
                    ", success: " + success);

        } else {
            System.out.println("QuorumReplica: Internal SET failed - keyLength: " + setRequest.key().length +
                    ", error: " + error.getMessage());

        }
    }

    private void handleInternalGetResponse(Message message) {
        var response = deserializePayload(message.payload(), InternalGetResponse.class);

        System.out.println("QuorumReplica: Processing internal GET response - keyLength: " + response.key().length +
                  ", from: " + message.source());

        // Route the response to the RequestWaitingList
        waitingList.handleResponse(message.correlationId(), response, message.source());
    }

    private void handleInternalSetResponse(Message message) {
        var response = deserializePayload(message.payload(), InternalSetResponse.class);

        System.out.println("QuorumReplica: Processing internal SET response - keyLength: " + response.key().length +
                ", success: " + response.success() + ", internalCorrelationId: " + message.correlationId() +
                ", from: " + message.source());

        waitingList.handleResponse(message.correlationId(), response, message.source());
    }
}
