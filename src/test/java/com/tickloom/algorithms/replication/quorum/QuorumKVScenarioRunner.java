package com.tickloom.algorithms.replication.quorum;

import com.tickloom.history.JepsenHistory;
import com.tickloom.history.Op;
import com.tickloom.SimulationRunner;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.future.ListenableFuture;

import java.io.IOException;
import java.util.Random;

public class QuorumKVScenarioRunner extends SimulationRunner {

    public QuorumKVScenarioRunner(long randomSeed, int clusterSize, int numClients) throws IOException {
        super(randomSeed, clusterSize, numClients, QuorumReplica::new, QuorumReplicaClient::new);
    }

    public QuorumKVScenarioRunner(long randomSeed) throws IOException {
        this(randomSeed, 3, 3);
    }


    //TODO: Revisit this design of HistoryRecorder and SimulationRunner. etc.
    //In general each implementation would have a client.. like the QuorumReplicaClient in this case.
    //The implementation needs to have its own Simulationrunner to implement issueRequest and
    //actually invoke a particular request based on the mix of supported operations we need (e.g. 20% writes
    //80% reads. etc.
    //The historyrecorder for jepsen history, needs to have a invoked value for each operation
    //and once we get a response, we need to be able to extract the value from that response.
    //Historyrecorder handles the values to be recorded in case of failures and timeouts.
    @Override
    protected ListenableFuture issueRequest(ClusterClient client, Random clusterSeededRandom) {
        String key = randomKey();
        String value = randomValue();
        // Pick operation.
        boolean doSet = clusterSeededRandom.nextBoolean();
        ListenableFuture opFuture = null;
        QuorumReplicaClient quorumReplicaClient = (QuorumReplicaClient) client;

        if (doSet) {
            System.out.println("Issuing request for key = " + key + " value = " + value + " from client " + quorumReplicaClient.id.name() + ": " + history.getProcessIndex(quorumReplicaClient.id));

            var writtenKv = JepsenHistory.tuple(key, value);
            opFuture = historyRecorder.invoke(quorumReplicaClient.id,
                    Op.WRITE,
                    writtenKv,
                    () -> quorumReplicaClient.set(key.getBytes(), value.getBytes()),
                    (msg) -> writtenKv
            );
        } else {
            System.out.println("Issuing request for key = " + key + " from client " + quorumReplicaClient.id.name() + ": " + history.getProcessIndex(quorumReplicaClient.id));

            opFuture = historyRecorder.invoke(quorumReplicaClient.id,
                    Op.READ,
                    JepsenHistory.tuple(key, null),
                    () -> quorumReplicaClient.get(key.getBytes()),
                    (msg) -> {
                        byte[] readValue = ((GetResponse) msg).value();
                        return JepsenHistory.tuple(key, JepsenHistory.tuple(key, new String(readValue)));
                    });
        }

        return opFuture;
    }

}
