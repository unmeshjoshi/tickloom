package com.tickloom.algorithms.replication.quorum;

import com.tickloom.history.Op;
import com.tickloom.SimulationRunner;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.future.ListenableFuture;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Random;

public class QuorumSimulationRunner extends SimulationRunner {

    public QuorumSimulationRunner(Duration runForDuration, long randomSeed) throws IOException {
        super(runForDuration, randomSeed);
    }


    @Override
    protected void issueRequest(ClusterClient client, Random clusterSeededRandom, long tick) {
        String key = randomKey();
        String value = randomValue();
        if (pendingRequests.containsKey(client.id) && pendingRequests.get(client.id)) {
            //buffer the request.
            bufferedRequests.computeIfAbsent(client.id, k -> new ArrayDeque<>()).add(() -> sendClientRequest((QuorumReplicaClient) client, clusterSeededRandom, tick, key, value));
            return;
        }

        pendingRequests.put(client.id, true);
        sendClientRequest((QuorumReplicaClient) client, clusterSeededRandom, tick, key, value);
    }

    private void sendClientRequest(QuorumReplicaClient client, Random clusterSeededRandom, long tick, String key, String value) {
        // Pick operation.
        boolean doSet = clusterSeededRandom.nextBoolean();
        ListenableFuture opFuture = null;
        if (doSet) {

            QuorumReplicaClient quorumReplicaClient = client;
            ListenableFuture<SetResponse> setFuture = quorumReplicaClient.set(key.getBytes(), value.getBytes());//clients.get(0)
            history.invoke(quorumReplicaClient.id.name(), Op.WRITE, key.getBytes(), value.getBytes(), tick);
            opFuture = wrapFutureForRecording(tick, quorumReplicaClient, setFuture, Op.WRITE, key.getBytes(), value.getBytes(),
                    (setResponse) -> value.getBytes());


        } else {
            QuorumReplicaClient quorumReplicaClient = client;
            ListenableFuture<GetResponse> getFuture = quorumReplicaClient.get(key.getBytes());//clients.get(0)
            history.invoke(quorumReplicaClient.id.name(), Op.READ, key.getBytes(), null, tick);
            opFuture = wrapFutureForRecording(tick, quorumReplicaClient,
                    getFuture, Op.READ, key.getBytes(), null,
                    (getResponse) -> getResponse.value());
        }

        var processId = client.id;
        opFuture.handle((result, exception) -> {
            pendingRequests.put(processId, false);
            issueNextRequest(processId);
        });
    }

}
