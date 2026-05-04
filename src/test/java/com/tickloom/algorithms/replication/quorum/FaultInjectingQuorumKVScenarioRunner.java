package com.tickloom.algorithms.replication.quorum;

import com.tickloom.FaultInjectingSimulationRunner;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.history.JepsenHistory;
import com.tickloom.history.Op;

import java.io.IOException;
import java.util.Random;

public class FaultInjectingQuorumKVScenarioRunner extends FaultInjectingSimulationRunner {

    public FaultInjectingQuorumKVScenarioRunner(long randomSeed, int clusterSize, int numClients) throws IOException {
        super(randomSeed, clusterSize, numClients,
                (peerIds, processParams) -> new QuorumReplica(peerIds, processParams),
                (replicaEndpoints, processParams) -> new QuorumReplicaClient(replicaEndpoints, processParams));
    }

    public FaultInjectingQuorumKVScenarioRunner(long randomSeed) throws IOException {
        this(randomSeed, 3, 3);
    }

    @Override
    protected TickCompletableFuture issueRequest(ClusterClient client, Random clusterSeededRandom) {
        String key = randomKey();
        String value = randomValue();
        QuorumReplicaClient quorumReplicaClient = (QuorumReplicaClient) client;

        boolean doSet = clusterSeededRandom.nextBoolean();

        if (doSet) {
            var writtenKv = JepsenHistory.tuple(key, value);
            return historyRecorder.invoke(quorumReplicaClient.id,
                    Op.WRITE,
                    writtenKv,
                    () -> quorumReplicaClient.set(key.getBytes(), value.getBytes()),
                    (msg) -> writtenKv
            );
        } else {
            return historyRecorder.invoke(quorumReplicaClient.id,
                    Op.READ,
                    JepsenHistory.tuple(key, null),
                    () -> quorumReplicaClient.get(key.getBytes()),
                    (getResponse) -> {
                        var s = QuorumKVScenarioRunner.maskNull(getResponse.value());
                        return JepsenHistory.tuple(key, s);
                    });
        }
    }
}
