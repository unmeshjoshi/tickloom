package com.tickloom.algorithms.replication.quorum;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.future.ListenableFuture;
import com.tickloom.messaging.*;
import com.tickloom.network.MessageCodec;
import com.tickloom.util.Clock;

import java.util.List;

public class QuorumReplicaClient extends ClusterClient {
    
    public QuorumReplicaClient(ProcessId clientId, List<ProcessId> replicaEndpoints,
                               MessageBus messageBus, MessageCodec messageCodec,
                               Clock clock, int timeoutTicks) {
        super(clientId, replicaEndpoints, messageBus, messageCodec, clock, timeoutTicks);
    }
    
    public ListenableFuture<GetResponse> get(byte[] key) {
        GetRequest request = new GetRequest(key);
        ProcessId primaryReplica = getPrimaryReplica();
        
        return sendRequest(request, primaryReplica, QuorumMessageTypes.CLIENT_GET_REQUEST);
    }
    
    public ListenableFuture<SetResponse> set(byte[] key, byte[] value) {
        SetRequest request = new SetRequest(key, value);
        ProcessId primaryReplica = getPrimaryReplica();
        
        return sendRequest(request, primaryReplica, QuorumMessageTypes.CLIENT_SET_REQUEST);
    }
    
    @Override
    public void onMessageReceived(Message message) {
        String correlationId = message.correlationId();
        MessageType messageType = message.messageType();
        
        if (isGetResponse(messageType)) {
            GetResponse response = deserialize(message.payload(), GetResponse.class);
            handleResponse(correlationId, response, message.source());
            
        } else if (isSetResponse(messageType)) {
            SetResponse response = deserialize(message.payload(), SetResponse.class);
            handleResponse(correlationId, response, message.source());
        }
    }
    
    private ProcessId getPrimaryReplica() {
        return replicaEndpoints.get(0);
    }
    
    private boolean isGetResponse(MessageType messageType) {
        return messageType.equals(QuorumMessageTypes.CLIENT_GET_RESPONSE);
    }
    
    private boolean isSetResponse(MessageType messageType) {
        return messageType.equals(QuorumMessageTypes.CLIENT_SET_RESPONSE);
    }
}
