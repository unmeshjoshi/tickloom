package com.tickloom;

import clojure.lang.IPersistentVector;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.algorithms.replication.quorum.QuorumKVScenarioRunner;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.future.ListenableFuture;
import com.tickloom.history.History;
import com.tickloom.history.HistoryRecorder;
import com.tickloom.history.JepsenHistory;
import com.tickloom.history.Op;
import com.tickloom.testkit.Cluster;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Random;

import static com.tickloom.ConsistencyChecker.*;


public abstract class SimulationRunner {
    private final ArrayList<com.tickloom.SingleRequestIssuer<ClusterClient>> clients;
    private Cluster cluster;

    public SimulationRunner(long randomSeed, ProcessFactory processFactory, Cluster.ClientFactory<QuorumReplicaClient> clientFactory) throws IOException {
        this(randomSeed, 3, 3, processFactory, clientFactory);
    }
    public SimulationRunner(long randomSeed, int clusterSize, int numClients, ProcessFactory processFactory, Cluster.ClientFactory<QuorumReplicaClient> clientFactory) throws IOException {
        this.cluster = new Cluster()
                .withSeed(randomSeed)
                .withNumProcesses(clusterSize)
                .withLossySimulatedNetwork()
                .build(processFactory);
        Random clusterSeedRandom = cluster.getRandom();
        this.clients = new ArrayList<>();
        for (int i = 0; i < numClients; i++) {
            ClusterClient clusterClient  = cluster.newClient(ProcessId.of("clientId-" + i), clientFactory);
            clients.add(new SingleRequestIssuer<>(this, clusterClient, clusterSeedRandom));
        }
    }
    protected HistoryRecorder<IPersistentVector> historyRecorder = new HistoryRecorder();

    public History runAndGetHistory(long tickDuration) {
        runClusterFor(tickDuration);
        waitForPendingRequests();
        // optionally skip file write + knossos here for tests
        return historyRecorder.getHistory();
    }

    public static void main(String[] args) throws IOException {
        int randomSeed = 111111;
        int runForTicks = 10000;
        SimulationRunner simulationRunner = new QuorumKVScenarioRunner(randomSeed);
        simulationRunner.runForTicks(runForTicks);
    }

    //KV histories use tuple for values in jepsen history. e.g. [key value]
    protected History<IPersistentVector> history = new History<>();

    public void runForTicks(long runForTicks) {
        history = runAndGetHistory(runForTicks);

        writeHistory(history);

        checkLinearizability();

        shutdownClojureAgents();
    }

    private static void shutdownClojureAgents() {
        shutdownAgents();
    }

    private void runClusterFor(long tickDuration) {
        double issueProbabilityPerTick = 0.4; //40% ticks will issue a clientId request.
        long tick = 0;
        Random clusterSeededRandom = cluster.getRandom();
        while (tickDuration > tick) {
            tick++;
            cluster.tick();

            // Decide whether to issue clientId request this tick.
            if (clusterSeededRandom.nextDouble() > issueProbabilityPerTick) continue;

            SingleRequestIssuer<ClusterClient> client = clients.get(clusterSeededRandom.nextInt(clients.size()));
            client.issueRequest();
        }
    }

    private void waitForPendingRequests() {
        //wait for all requests to finish
        while (clients.stream().anyMatch(SingleRequestIssuer::hasPendingRequests)) {
            cluster.tick();
        }
    }

    private void checkLinearizability() {
        boolean isLinearizable = check(history, ConsistencyProperty.LINEARIZABILITY, DataModel.REGISTER);
        System.out.println("The history is " + (isLinearizable ? "linearizable" : "not linearizable") + " = " + isLinearizable);
    }


    protected void recordReadInvocation(ProcessId processId, Op op, String key) {
        history.invoke(processId, op, JepsenHistory.tuple(key, null));
    }

    protected void recordInvocation(ProcessId processId, Op op, String key, String value) {
        history.invoke(processId, op, JepsenHistory.tuple(key, value));
    }

    protected abstract ListenableFuture issueRequest(ClusterClient client, Random clusterSeededRandom);

    private void writeHistory(History history) {
        String buildDir = "build";
        String historyFile = buildDir + "/history_" + Instant.now() + ".edn";
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(historyFile))) {
            String edn = history.toEdn();
            writer.write(edn);
            writer.close();
            System.out.println("Wrote history to " + historyFile);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    protected String randomValue() {
        return "Value-" + cluster.getRandom().nextInt();
    }

    protected String randomKey() {
        return "Key-" + cluster.getRandom().nextInt();
    }
}


