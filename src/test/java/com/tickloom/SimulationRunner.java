package com.tickloom;

import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.algorithms.replication.quorum.*;
import com.tickloom.future.ListenableFuture;
import com.tickloom.history.History;
import com.tickloom.history.Op;
import com.tickloom.testkit.Cluster;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;


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

    public History runAndGetHistory(long tickDuration) {
        runClusterFor(tickDuration);
        waitForPendingRequests();
        // optionally skip file write + knossos here for tests
        return history;
    }

    public static void main(String[] args) throws IOException {
        int randomSeed = 111111;
        int runForTicks = 10000;
        SimulationRunner simulationRunner = new QuorumSimulationRunner(randomSeed);
        simulationRunner.runForTicks(runForTicks);
    }

    protected History history = new History();

    public void runForTicks(long runForTicks) {
        history = runAndGetHistory(runForTicks);

        writeHistory(history);

        checkLinearizability();

        shutdownClojureAgents();
    }

    private static void shutdownClojureAgents() {
        Knossos.shutdownAgents();
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
        Knossos knossos = new Knossos();
        String edn = history.toEdn();
        boolean isLinearizable = knossos.checkLinearizableRegister(edn);
        System.out.println("The history is " + (isLinearizable ? "linearizable" : "not linearizable") + " = " + isLinearizable);
    }


    protected void recordReadInvocation(ProcessId processId, Op op, byte[] keyBytes) {
        history.invoke(processId.name(), op, keyBytes, null);
    }

    protected void recordInvocation(ProcessId processId, Op op, byte[] keyBytes, byte[] valueBytes) {
        history.invoke(processId.name(), op, keyBytes, valueBytes);
    }
    //history recording for get calls, with only key.
    protected <T> ListenableFuture<T> recordReadResponse(ListenableFuture<T> future,
                                                         Op op,
                                                         byte[] key,
                                                         Function<T, byte[]> valueFromResponse, ProcessId processId) {
        return recordResponse(future, op, key, null, valueFromResponse, processId);
    }

    //history recording for set calls which have both key and a writtenValue.
    protected <T> ListenableFuture<T> recordResponse(ListenableFuture<T> future,
                                                     Op op,
                                                     byte[] key,
                                                     byte[] writtenValue,
                                                     Function<T, byte[]> valueFromResponse, ProcessId processId) {
        return future.andThen((setResponse, exception) -> {
            if (exception == null) {
                byte[] responseValue = valueFromResponse.apply(setResponse);
                history.ok(processId.name(), op, key, responseValue);
            } else {
                if (exception instanceof TimeoutException) {
                    history.timeout(processId.name(), op, key, writtenValue);
                }
                history.fail(processId.name(), op, key, writtenValue);
            }
        });
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


