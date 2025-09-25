package com.tickloom.algorithms.replication;

import com.tickloom.Process;
import com.tickloom.ProcessId;
import com.tickloom.future.ListenableFuture;
import com.tickloom.messaging.*;
import com.tickloom.network.MessageCodec;
import com.tickloom.network.PeerType;
import com.tickloom.util.Clock;
import com.tickloom.util.Utils;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;

public abstract class ClusterClient extends Process {
    
    protected final List<ProcessId> replicaEndpoints;

    public ClusterClient(ProcessId clientId, List<ProcessId> replicaEndpoints,
                         MessageBus messageBus, MessageCodec messageCodec,
                         Clock clock, int timeoutTicks) {
        super(clientId, messageBus, messageCodec, timeoutTicks, clock);
        this.replicaEndpoints = List.copyOf(replicaEndpoints);
    }

//    can have history writing decorator which delegates to cluster client?
    // class HistoryWritingClient extends ClusterClient {




    public List<ProcessId> getReplicaEndpoints() {
        return replicaEndpoints;
    }
    
    public int getPendingRequestCount() {
        return waitingList.size();
    }


    protected <T> ListenableFuture<T> sendRequest(Object request, ProcessId destination, 
                                                 MessageType messageType) {
        String correlationId = generateCorrelationId();
        ListenableFuture<T> future = new ListenableFuture<>();
        
        RequestCallback<Object> callback = createCallback(future);
        waitingList.add(correlationId, callback);
        
        Message message = createMessage(request, correlationId, destination, messageType);
        sendMessageOrHandleError(message, correlationId);
        
        return future;
    }
    
    protected void handleResponse(String correlationId, Object response, ProcessId fromNode) {
        waitingList.handleResponse(correlationId, response, fromNode);
    }
    
    protected void handleError(String correlationId, Exception error) {
        waitingList.handleError(correlationId, error);
    }
    
    protected Message createMessage(Object request, String correlationId, 
                                  ProcessId destination, MessageType messageType) {
        return Message.of(id, destination, PeerType.CLIENT, messageType, 
                         serialize(request), correlationId);
    }
    
    protected void sendMessageOrHandleError(Message message, String correlationId) {
        try {
            messageBus.sendMessage(message);
        } catch (IOException e) {
            waitingList.handleError(correlationId, e);
        }
    }
    
    protected byte[] serialize(Object payload) {
        return messageCodec.encode(payload);
    }
    
    protected <T> T deserialize(byte[] data, Class<T> type) {
        return messageCodec.decode(data, type);
    }
    
    protected String generateCorrelationId() {
        return Utils.generateCorrelationId();
    }
    
    @SuppressWarnings("unchecked")
    private <T> RequestCallback<Object> createCallback(ListenableFuture<T> future) {
        return new RequestCallback<Object>() {
            @Override
            public void onResponse(Object response, ProcessId fromNode) {
                future.complete((T) response);
            }
            
            @Override
            public void onError(Exception error) {
                future.fail(error);
            }
        };
    }
}
