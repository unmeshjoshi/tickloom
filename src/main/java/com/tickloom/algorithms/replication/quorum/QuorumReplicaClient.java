package com.tickloom.algorithms.replication.quorum;

import com.tickloom.ProcessId;
import com.tickloom.ProcessParams;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.future.ListenableFuture;
import com.tickloom.messaging.*;

import java.util.List;
import java.util.Map;

public class QuorumReplicaClient extends ClusterClient {
    
    public QuorumReplicaClient(List<ProcessId> replicaEndpoints, ProcessParams processParams) {
        super(replicaEndpoints, processParams);
    }
    
    public ListenableFuture<GetResponse> get(byte[] key) {
        GetRequest request = new GetRequest(key);
        ProcessId primaryReplica = firstReplica();
        
        return sendRequest(request, primaryReplica, QuorumMessageTypes.CLIENT_GET_REQUEST);
    }
    
    public ListenableFuture<SetResponse> set(byte[] key, byte[] value) {
        SetRequest request = new SetRequest(key, value);
        ProcessId primaryReplica = firstReplica();
        
        return sendRequest(request, primaryReplica, QuorumMessageTypes.CLIENT_SET_REQUEST);
    }


    private void handleSetResponse(Message message) {
        SetResponse response = deserialize(message.payload(), SetResponse.class);
        handleResponse(message.correlationId(), response, message.source());
    }

    private void handleGetResponse(Message message) {
        GetResponse response = deserialize(message.payload(), GetResponse.class);
        handleResponse(message.correlationId(), response, message.source());
    }

    @Override
    protected Map<MessageType, Handler> initialiseHandlers() {
        return Map.of(
                QuorumMessageTypes.CLIENT_GET_RESPONSE, this::handleGetResponse,
                QuorumMessageTypes.CLIENT_SET_RESPONSE, this::handleSetResponse);

    }

    private ProcessId firstReplica() {
        return replicaEndpoints.get(0);
    }
}
