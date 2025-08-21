package com.tickloom.algorithms.replication.quorum;

import com.tickloom.history.Op;
import com.tickloom.SimulationRunner;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.future.ListenableFuture;

import java.io.IOException;
import java.time.Duration;
import java.util.Random;

public class QuorumSimulationRunner extends SimulationRunner {

    public QuorumSimulationRunner(Duration runForDuration, long randomSeed) throws IOException {
        super(runForDuration, randomSeed);
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
            history.invoke(quorumReplicaClient.id.name(), Op.WRITE, key.getBytes(), value.getBytes());
            opFuture = wrapFutureForRecording(setFuture, Op.WRITE, key.getBytes(), value.getBytes(),
                    (setResponse) -> value.getBytes(), quorumReplicaClient.id);


        } else {
            QuorumReplicaClient quorumReplicaClient = (QuorumReplicaClient) client;
            ListenableFuture<GetResponse> getFuture = quorumReplicaClient.get(key.getBytes());//clients.get(0)
            history.invoke(quorumReplicaClient.id.name(), Op.READ, key.getBytes(), null);
            opFuture = wrapFutureForRecording(
                    getFuture, Op.READ, key.getBytes(), null,
                    (getResponse) -> getResponse.value(), quorumReplicaClient.id);
        }

        return opFuture;
    }

}
