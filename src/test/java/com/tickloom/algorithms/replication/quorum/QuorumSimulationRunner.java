package com.tickloom.algorithms.replication.quorum;

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
            ListenableFuture<SetResponse> setFuture = quorumReplicaClient.set(key.getBytes(), value.getBytes());//clients.get(0)
            recordInvocation(quorumReplicaClient.id, Op.WRITE, key.getBytes(), value.getBytes());
            opFuture = recordResponse(setFuture, Op.WRITE, key.getBytes(), value.getBytes(),
                    (setResponse) -> value.getBytes(), quorumReplicaClient.id);


        } else {
            QuorumReplicaClient quorumReplicaClient = (QuorumReplicaClient) client;
            ListenableFuture<GetResponse> getFuture = quorumReplicaClient.get(key.getBytes());//clients.get(0)
            recordReadInvocation(quorumReplicaClient.id, Op.READ, key.getBytes());
            opFuture = recordReadResponse(
                    getFuture, Op.READ, key.getBytes(),
                    (getResponse) -> getResponse.value(), quorumReplicaClient.id);
        }

        return opFuture;
    }

}


