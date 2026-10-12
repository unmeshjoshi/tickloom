package com.tickloom.algorithms.replication.quorum;

import com.tickloom.ProcessParams;
import com.tickloom.ProcessId;
import com.tickloom.Replica;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.messaging.*;

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
    static final String STORE_FILE = "quorum-kv";
    static final int GROUP_WAIT_TICKS = 2;

    // Keys in memory, writes in a log on this process's own disk. A set is acknowledged only once it is durable.
    private final LogBackedKeyValueStore store;

    public QuorumReplica(List<ProcessId> peerIds, ProcessParams processParams) {
        super(peerIds, processParams);
        this.store = new LogBackedKeyValueStore(fileSystem, STORE_FILE, GROUP_WAIT_TICKS);
    }

    /** The replica is not running until its store is rebuilt from the log. */
    @Override
    protected TickCompletableFuture<?> onStart() {
        return store.open();
    }

    @Override
    protected void onTick() {
        store.tick();
    }

    /** True while a write this replica applied is not yet durable on its disk. */
    public boolean hasWritesNotYetDurable() {
        return store.hasWritesNotYetDurable();
    }

    /** The value this replica holds locally for the key, as stored (an encoded {@link VersionedValue}), or null. */
    public byte[] getStoredValue(byte[] key) {
        return store.get(key);
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

        this.<InternalGetResponse>quorumRequest(QuorumMessageTypes.INTERNAL_GET_REQUEST, new InternalGetRequest(clientRequest.key()))
                .countResponseIf(response -> response != null && response.value() != null)
                .send()
                .whenComplete((responses, error) -> {
                    if (error != null) {
                        sendFailureGetResponse(clientRequest, correlationId, clientId, error);
                    } else {
                        sendSuccessGetResponse(clientRequest, correlationId, clientId, responses);
                    }
                });
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

        var timestamp = clock.now();
        InternalSetRequest internalSetRequest = new InternalSetRequest(
                clientRequest.key(), clientRequest.value(), timestamp);

        this.<InternalSetResponse>quorumRequest(QuorumMessageTypes.INTERNAL_SET_REQUEST, internalSetRequest)
                .countResponseIf(response -> response != null && response.success())
                .send()
                .whenComplete((responses, error) -> {
                    if (error != null) {
                        sendFailureSetResponseToClient(clientRequest, correlationId, clientAddress, error);
                    } else {
                        sendSuccessSetResponseToClient(clientRequest, correlationId, clientAddress);
                    }
                });
    }

    private void logIncomingSetRequest(SetRequest req, String correlationId, ProcessId clientAddr) {
        System.out.println("QuorumReplica: Processing clientId SET request - keyLength: " + req.key().length +
                ", valueLength: " + req.value().length + ", correlationId: " + correlationId + ", from: " + clientAddr);
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
        sendInternalGetResponse(message, store.get(getRequest.key()), null, getRequest);
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

        //Set only if the key does not exist or the existing value has a lower timestamp.
        byte[] result = store.get(setRequest.key());
        if (result != null) {
            VersionedValue existingValue = messageCodec.decode(result, VersionedValue.class);
            if (existingValue.timestamp() >= value.timestamp()) {
                //Already set with a higher timestamp, so we return true, but dont overwrite the value.
                sendInternalSetResponse(message, true, null, setRequest);
                return;
            }
        }
        // The map changes now; the response goes out only when the write is durable.
        store.put(setRequest.key(), messageCodec.encode(value))
                .whenComplete((ignored, setError) ->
                        sendInternalSetResponse(message, setError == null, setError, setRequest));
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
