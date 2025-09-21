package com.tickloom.algorithms.replication.quorum;

import com.tickloom.history.History;
import com.tickloom.history.Op;
import com.tickloom.SimulationRunner;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.future.ListenableFuture;

import java.io.IOException;
import java.util.Random;

public class QuorumSimulationRunner extends SimulationRunner {

    public QuorumSimulationRunner(long randomSeed, int clusterSize, int numClients) throws IOException {
        super(randomSeed, clusterSize, numClients, QuorumReplica::new, QuorumReplicaClient::new);
    }

    public QuorumSimulationRunner(long randomSeed) throws IOException {
        this(randomSeed, 3, 3);
    }

    @Override
    protected ListenableFuture issueRequest(ClusterClient client, Random clusterSeededRandom) {
        String key = randomKey();
        String value = randomValue();
        // Pick operation.
        boolean doSet = clusterSeededRandom.nextBoolean();
        ListenableFuture opFuture = null;
        if (doSet) {

            QuorumReplicaClient quorumReplicaClient = (QuorumReplicaClient) client;
            System.out.println("Issuing request for key = " + key + " value = " + value + " from client " + quorumReplicaClient.id.name() + ": " + history.getProcessIndex(quorumReplicaClient.id));

            ListenableFuture<SetResponse> setFuture = quorumReplicaClient.set(key.getBytes(), value.getBytes());//clients.get(0)
            recordInvocation(quorumReplicaClient.id, Op.WRITE, key, value);
            opFuture = recordResponse(setFuture, Op.WRITE, key, value,
                    (setResponse) -> value, quorumReplicaClient.id);


        } else {
            QuorumReplicaClient quorumReplicaClient = (QuorumReplicaClient) client;
            System.out.println("Issuing request for key = " + key  + " from client " + quorumReplicaClient.id.name() + ": " + history.getProcessIndex(quorumReplicaClient.id));
            ListenableFuture<GetResponse> getFuture = quorumReplicaClient.get(key.getBytes());//clients.get(0)
            recordReadInvocation(quorumReplicaClient.id, Op.READ, key);
            opFuture = recordReadResponse(
                    getFuture, Op.READ, key,
                    (getResponse) -> {
                        try {
                            System.out.println("Received response for key = " + key + " value = " + getResponse);
                            byte[] responseValue = getResponse.value();
                            return (responseValue != null) ?
                                    new String(responseValue) : null; //TODO: Need to fix null handling
                        } catch (Exception e) {
                            e.printStackTrace();
                            return null;
                        }
                    }
            , quorumReplicaClient.id);
        }

        return opFuture;
    }

}


