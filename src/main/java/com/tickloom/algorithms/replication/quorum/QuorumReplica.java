package com.tickloom.algorithms.replication.quorum;

import com.tickloom.ProcessParams;
import com.tickloom.ProcessId;
import com.tickloom.Replica;
import com.tickloom.future.ListenableFuture;
import com.tickloom.messaging.*;
import com.tickloom.storage.VersionedValue;

import java.util.List;
import java.util.Map;

/**
 * Quorum-based replica implementation for distributed key-name store.
 * This implementation uses majority quorum consensus for read and write operations.
 * <p>
 * Based on the quorum consensus algorithm where operations require majority
 * agreement from replicas before completing successfully.
 */
public class QuorumReplica extends Replica {

    public QuorumReplica(List<ProcessId> peerIds, ProcessParams processParams) {
        super(peerIds, processParams);
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
        System.out.println("QuorumReplica: Processing clientId GET request - keyLength: " + req.key().length +
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

        System.out.println("Sending client response for key = " + new String(req.key()) + " " + response);

        send(responseMessage);
    }

    private void sendFailureGetResponse(GetRequest req, String correlationId, ProcessId clientAddr, Throwable error) {
        logFailedGetResponse(req, correlationId, error);

        var response = new GetResponse(req.key(), null, false);

        var responseMessage = createMessage(clientAddr, correlationId, response, QuorumMessageTypes.CLIENT_GET_RESPONSE);

        System.out.println("Sending client response for key = " + new String(req.key()) + " " + response);
        send(responseMessage);
    }

    private void logSuccessfulGetResponse(GetRequest req, String correlationId, VersionedValue latestValue) {
        System.out.println("QuorumReplica: Successful GET response - key: " + new String(req.key()) +
                ", correlationId: " + correlationId + ", hasValue: " + (latestValue != null));
    }

    private void logFailedGetResponse(GetRequest req, String correlationId, Throwable error) {
        System.out.println("QuorumReplica: Failed GET response - key: " + new String(req.key()) +
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
        System.out.println("QuorumReplica: Processing clientId SET request - keyLength: " + req.key().length +
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

    // Helper method to extract the latest name from quorum responses
    private VersionedValue getLatestValueFromResponses(Map<ProcessId, InternalGetResponse> responses) {
        VersionedValue latestValue = null;
        long latestTimestamp = -1;

        for (InternalGetResponse response : responses.values()) {
            if (response.value() != null) {
                VersionedValue versionedValue = messageCodec.decode(response.value(), VersionedValue.class);
                long timestamp = versionedValue.timestamp();
                if (timestamp > latestTimestamp) {
                    latestTimestamp = timestamp;
                    latestValue = versionedValue;
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

    private void sendInternalGetResponse(Message incomingMessage, byte[] value, Throwable error, InternalGetRequest getRequest) {

        logInternalGetResponse(value, error, getRequest);
        //name will be null if not found or error
        var response = new InternalGetResponse(getRequest.key(), value);

        var responseMessage = createResponseMessage(incomingMessage, response, QuorumMessageTypes.INTERNAL_GET_RESPONSE);

        System.out.println("Sending response message for key = " + new String(getRequest.key()) + " " + responseMessage);

        send(responseMessage);

    }

    private static void logInternalGetResponse(byte[] value, Throwable error, InternalGetRequest getRequest) {
        if (error == null) {
            String valueStr = value != null ? value + "found" : "not found";
            System.out.println("QuorumReplica: Internal GET completed - key: " + getRequest.key() +
                    ", value: " + valueStr);

        } else {
            System.out.println("QuorumReplica: Internal GET failed - key: " + getRequest.key() +
                    ", error: " + error.getMessage());
        }
    }

    private void handleInternalSetRequest(Message message) {
        var setRequest = deserializePayload(message.payload(), InternalSetRequest.class);
        var value = new VersionedValue(setRequest.value(), setRequest.timestamp());

        System.out.println("QuorumReplica: Processing internal SET request - keyLength: " + setRequest.key().length +
                ", valueLength: " + setRequest.value().length + ", timestamp: " + setRequest.timestamp() +
                ", from: " + message.source());

        //First get the value and set only if it does not exist or its of lower timestamp.
        ListenableFuture<byte[]> getFuture = storage.get(setRequest.key());
        getFuture.handle((result, error) -> {
            if (error != null) {
                sendInternalSetResponse(message, false, error, setRequest);
                return;
            }
            if (result != null) {
                VersionedValue existingValue = messageCodec.decode(result, VersionedValue.class);
                if (existingValue.timestamp() >= value.timestamp()) {
                    //Already set with higher timestamp, so we return true, but dont overwrite the value.
                    sendInternalSetResponse(message, true, null, setRequest);
                    return;
                }
            }
            // Perform local storage operation
            var future = storage.set(setRequest.key(), messageCodec.encode(value));
            future.handle((success, setError)
                    -> sendInternalSetResponse(message, success, setError, setRequest));
        });
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
