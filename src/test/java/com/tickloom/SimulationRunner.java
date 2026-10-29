package com.tickloom;

import clojure.lang.IPersistentVector;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.algorithms.replication.quorum.QuorumKVScenarioRunner;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.future.TickCompletableFuture;
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
import java.util.List;
import java.util.Random;

import static com.tickloom.ConsistencyChecker.*;

public abstract class SimulationRunner {
    private final List<SingleRequestIssuer<ClusterClient>> clients;
    private final Cluster cluster;
    protected final SimulationDecisions simulationDecisions;
    private long currentTick = 0;

    protected HistoryRecorder<IPersistentVector> historyRecorder = new HistoryRecorder<>();
    protected History<IPersistentVector> history = new History<>();

    public SimulationRunner(long randomSeed, int clusterSize, int numClients,
                            ProcessFactory processFactory,
                            Cluster.ClientFactory<QuorumReplicaClient> clientFactory) throws IOException {
        Random random = new Random(randomSeed);
        this.simulationDecisions = new SimulationDecisions(random);
        this.cluster = Cluster.createSimulated(clusterSize, processFactory, random);
        this.clients = createClients(numClients, clientFactory);
    }

    private void runClusterFor(long runForTicks) {
        for (int i = 0; i < runForTicks; i++) {
            currentTick++;
            injectRandomFaults();
            cluster.tick();
            maybeIssueClientRequest();
        }
    }

    private void maybeIssueClientRequest() {
        if (simulationDecisions.shouldIssueClientRequest()) {
            // A client is single-threaded and issues one request at a time.
            // If a request is pending, the request is buffered and issued later.
            SingleRequestIssuer<ClusterClient> client = simulationDecisions.pick(clients);
            client.issueRequest();
        }
    }

    private List<SingleRequestIssuer<ClusterClient>> createClients(int numClients,
                                                                   Cluster.ClientFactory<QuorumReplicaClient> clientFactory) throws IOException {
        List<SingleRequestIssuer<ClusterClient>> clients = new ArrayList<>();
        for (int i = 0; i < numClients; i++) {
            ClusterClient clusterClient = cluster.newClient(ProcessId.of("clientId-" + i), clientFactory);
            clients.add(new SingleRequestIssuer<>(this, clusterClient, simulationDecisions.rawRandom()));
        }
        return clients;
    }

    public void runForTicks(long runForTicks) {
        history = runAndGetHistory(runForTicks);

        writeHistory(history);

        checkLinearizability();

        shutdownClojureAgents();
    }

    private static void shutdownClojureAgents() {
        shutdownAgents();
    }

    public History runAndGetHistory(long tickDuration) {
        runClusterFor(tickDuration);
        waitForPendingRequests();
        return historyRecorder.getHistory();
    }

    protected Random random() {
        return simulationDecisions.rawRandom();
    }

    protected void injectRandomFaults() {
    }

    protected Cluster cluster() {
        return cluster;
    }

    public long currentTick() {
        return currentTick;
    }

    public long driverTicksElapsed() {
        return currentTick;
    }

    private void waitForPendingRequests() {
        while (clients.stream().anyMatch(SingleRequestIssuer::hasPendingRequests)) {
            currentTick++;
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

    protected abstract TickCompletableFuture<?> issueRequest(ClusterClient client, Random clusterSeededRandom);

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
        return "Value-" + simulationDecisions.rawRandom().nextInt();
    }

    protected String randomKey() {
        return "Key-" + simulationDecisions.rawRandom().nextInt();
    }

    public static void main(String[] args) throws IOException {
        int randomSeed = 111111;
        int runForTicks = 10000;
        SimulationRunner simulationRunner = new QuorumKVScenarioRunner(randomSeed);
        simulationRunner.runForTicks(runForTicks);
    }
}
