package com.tickloom;

import clojure.lang.IPersistentVector;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.algorithms.replication.quorum.*;
import com.tickloom.future.ListenableFuture;
import com.tickloom.history.History;
import com.tickloom.history.JepsenHistory;
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

    //KV histories use tuple for values in jepsen history. e.g. [key value]
    protected History<String, IPersistentVector> history = new History<>();

    public void runForTicks(long runForTicks) {
        history = runAndGetHistory(runForTicks);

        writeHistory(history);

        checkLinearizability();

        shutdownClojureAgents();
    }

    private static void shutdownClojureAgents() {
        ConsistencyChecker.shutdownAgents();
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
        ConsistencyChecker consistencyChecker = new ConsistencyChecker();
        String edn = history.toEdn();
        boolean isLinearizable = ConsistencyChecker.check(edn, "linearizable", "register");
        System.out.println("The history is " + (isLinearizable ? "linearizable" : "not linearizable") + " = " + isLinearizable);
    }


    protected void recordReadInvocation(ProcessId processId, Op op, String key) {
        history.invoke(processId, op, key, JepsenHistory.tuple(key, null));
    }

    protected void recordInvocation(ProcessId processId, Op op, String key, String value) {
        history.invoke(processId, op, key, JepsenHistory.tuple(key, value));
    }
    //history recording for get calls, with only key.
    protected <T> ListenableFuture<T> recordReadResponse(ListenableFuture<T> future,
                                                         Op op,
                                                         String key,
                                                         Function<T, String> valueFromResponse, ProcessId processId) {
        return future.andThen((setResponse, exception) -> {
            try {
                System.out.println("Received response in client for key = " + key + " response: " + setResponse + " exception:" + exception);

                if (exception == null) {
                    String responseValue = valueFromResponse.apply(setResponse);
                    history.ok(processId, op, key, JepsenHistory.tuple(key, responseValue));
                } else {
                    //For reads, any exception including timeouts is considered a failure.
                    history.fail(processId, op, key, JepsenHistory.tuple(key, null));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    //history recording for set calls which have both key and a writtenValue.
    protected <T> ListenableFuture<T> recordResponse(ListenableFuture<T> future,
                                                     Op op,
                                                     String key,
                                                     String writtenValue,
                                                     Function<T, String> valueFromResponse, ProcessId processId) {
        return future.andThen((setResponse, exception) -> {
            try {
                System.out.println("Received response in client for key = " + key + " response: " + setResponse + " exception:" + exception);

                if (exception == null) {
                    String responseValue = valueFromResponse.apply(setResponse);
                    history.ok(processId, op, key, JepsenHistory.tuple(key, responseValue));
                } else if (exception instanceof TimeoutException) {
                    history.timeout(processId, op, key, JepsenHistory.tuple(key, writtenValue));
                    System.out.println("Timeout for key = " + key + " writtenValue: ");
                } else {
                    history.fail(processId, op, key, JepsenHistory.tuple(key, writtenValue));
                }
            } catch (Exception e) {
                e.printStackTrace();
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


