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
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Function;


public abstract class SimulationRunner {
    private final ArrayList<ClusterClient> clients;
    private final Duration runForDuration;
    private Cluster cluster;

    public SimulationRunner(Duration runForDuration, long randomSeed) throws IOException {
        this.runForDuration = runForDuration;
        this.cluster = new Cluster()
                .withSeed(randomSeed)
                .withNumProcesses(3)
                .withLossySimulatedNetwork()
                .build(QuorumReplica::new);
        this.clients = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            clients.add(cluster.newClient(ProcessId.of("client-" + i), QuorumReplicaClient::new));
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

            ClusterClient client = clients.get(clusterSeededRandom.nextInt(clients.size()));
            issueSingleRequest(client, clusterSeededRandom, tick);
        }
    }

    private void waitForPendingRequests() {
        //wait for all requests to finish
        while (hasPendingRequests()) {
            cluster.tick();
        }
    }

    private void checkLinearizability() {
        Knossos knossos = new Knossos();
        String edn = history.toEdn();
        boolean isLinearizable = knossos.checkLinearizableRegister(edn);
        System.out.println("The history is " + (isLinearizable ? "linearizable" : "not linearizable") + " = " + isLinearizable);
    }

    private boolean hasPendingRequests() {
        return !bufferedRequests.values().stream().allMatch(Collection::isEmpty);
    }

    protected <T> ListenableFuture<T> wrapFutureForRecording(long finalTick,
                                                             ListenableFuture<T> future,
                                                             Op op,
                                                             byte[] key,
                                                             byte[] value,
                                                             Function<T, byte[]> valueSupplier, ProcessId processId) {
        return future.andThen((setResponse, exception) -> {
            if (exception == null) {
                byte[] value1 = valueSupplier.apply(setResponse);
                history.ok(processId.name(), op, key, value1, finalTick);
            } else {
                if (exception instanceof TimeoutException) {
                    history.timeout(processId.name(), op, key, value, finalTick);
                }
                history.fail(processId.name(), op, key, value, finalTick);
            }
        });
    }



    private void issueSingleRequest(ClusterClient client, Random clusterSeededRandom, long tick) {
        if (pendingRequests.containsKey(client.id) && pendingRequests.get(client.id)) {
            //buffer the request.
            bufferedRequests.computeIfAbsent(client.id, k -> new ArrayDeque<>()).add(() -> sendClientRequest(client, clusterSeededRandom, tick));
            return;
        }
        pendingRequests.put(client.id, true);

        sendClientRequest(client, clusterSeededRandom, tick);
    }

    private void sendClientRequest(ClusterClient client, Random clusterSeededRandom, long tick) {
        ListenableFuture opFuture = issueRequest(client, clusterSeededRandom, tick);
        var processId = client.id;
        opFuture.andThen((result, exception) -> {
            pendingRequests.put(processId, false);
            issueNextRequest(processId);
        });
    }

    protected abstract ListenableFuture issueRequest(ClusterClient client, Random clusterSeededRandom, long tick);


    protected class FutureHistoryRecorder<T> extends ListenableFuture<T> {
        private final ProcessId processId;
        private final History history;
        private final Operation operation;
        private final ListenableFuture<T> future;
        private final Function<T, byte[]> valueSupplier;

        static record Operation(Op op, byte[] key, byte[] value, long tick) {
        }

        public FutureHistoryRecorder(ProcessId processId, History history, Operation operation, ListenableFuture<T> future, Function<T, byte[]> valueSupplier) {
            this.processId = processId;
            this.history = history;
            this.operation = operation;
            this.future = future;
            this.valueSupplier = valueSupplier;
        }

        @Override
        public ListenableFuture<T> handle(BiConsumer<T, Throwable> handler) {

            return this;
        }
    }


    //We can probably use only buffered requests to figure out if there are any pending requests
   protected Map<ProcessId, Boolean> pendingRequests = new HashMap<>();
    protected Map<ProcessId, Queue<Runnable>> bufferedRequests = new HashMap<>();

    protected void issueNextRequest(ProcessId processId) {
        if (bufferedRequests.containsKey(processId)) {
            pendingRequests.put(processId, true);
            Queue<Runnable> runnables = bufferedRequests.get(processId);
            System.out.println("runnables for " + processId + " = " + runnables.size());
            Runnable poll = runnables.poll();
            if (poll != null) {
                poll.run();
            }
        }
    }

    private void writeHistory() {
        String buildDir = "build";
        String historyFile = buildDir + "/history.edn";
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

    //TODO: See if we can run the simulation for durations instead of specified ticks.
    private static long elapsedTime(long start) {
        return System.nanoTime() - start;
    }
}


