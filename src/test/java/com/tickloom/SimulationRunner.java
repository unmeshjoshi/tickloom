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
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;


public abstract class SimulationRunner {
    private final ArrayList<com.tickloom.SingleRequestIssuer<ClusterClient>> clients;
    private final Duration runForDuration;
    private Cluster cluster;

    public SimulationRunner(Duration runForDuration, long randomSeed) throws IOException {
        this.runForDuration = runForDuration;
        this.cluster = new Cluster()
                .withSeed(randomSeed)
                .withNumProcesses(3)
                .withLossySimulatedNetwork()
                .build(QuorumReplica::new);
        Random clusterSeedRandom = cluster.getRandom();
        this.clients = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ClusterClient clusterClient  = cluster.newClient(ProcessId.of("client-" + i), QuorumReplicaClient::new);
            clients.add(new SingleRequestIssuer<>(this, clusterClient, clusterSeedRandom));
        }
    }

    public static void main(String[] args) throws IOException {
        SimulationRunner simulationRunner = new QuorumSimulationRunner(Duration.ofSeconds(60), 111111);
        simulationRunner.run();
    }

    protected History history = new History();

    public void run() {
        long tickDuration = 100000;
        runClusterFor(tickDuration);

        waitForPendingRequests();

        writeHistory();

        checkLinearizability();

        shutdownClojureAgents();
    }

    private static void shutdownClojureAgents() {
        Knossos.shutdownAgents();
    }

    private void runClusterFor(long tickDuration) {
        double issueProbabilityPerTick = 0.4; //40% ticks will issue a client request.
        long tick = 0;
        Random clusterSeededRandom = cluster.getRandom();
        while (tickDuration > tick) {
            tick++;
            cluster.tick();

            // Decide whether to issue client request this tick.
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


    protected <T> ListenableFuture<T> wrapFutureForRecording(ListenableFuture<T> future,
                                                             Op op,
                                                             byte[] key,
                                                             byte[] value,
                                                             Function<T, byte[]> valueSupplier, ProcessId processId) {
        return future.andThen((setResponse, exception) -> {
            if (exception == null) {
                byte[] responseValue = valueSupplier.apply(setResponse);
                history.ok(processId.name(), op, key, responseValue);
            } else {
                if (exception instanceof TimeoutException) {
                    history.timeout(processId.name(), op, key, value);
                }
                history.fail(processId.name(), op, key, value);
            }
        });
    }

    protected abstract ListenableFuture issueRequest(ClusterClient client, Random clusterSeededRandom);

    private void writeHistory() {
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


