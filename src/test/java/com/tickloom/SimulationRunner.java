package com.tickloom;

import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.algorithms.replication.quorum.GetResponse;
import com.tickloom.algorithms.replication.quorum.QuorumReplica;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.algorithms.replication.quorum.SetResponse;
import com.tickloom.future.ListenableFuture;
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

    History history = new History();

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
            for (ClusterClient client : clients) {
                client.tick();
            }
            cluster.tick();

            // Decide whether to issue client request this tick.
            if (clusterSeededRandom.nextDouble() > issueProbabilityPerTick) continue;

            ClusterClient client = clients.get(clusterSeededRandom.nextInt(clients.size()));
            issueRequest(client, clusterSeededRandom, tick);
        }
    }

    private void waitForPendingRequests() {
        //        //wait for all requests to finish
        while (hasPendingRequests()) {
            for (ClusterClient client : clients) {
                client.tick();
            }
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

    protected <T> void wrapFutureForRecording(long finalTick,
                                              QuorumReplicaClient quorumReplicaClient,
                                              ListenableFuture<T> set,
                                              Op op,
                                              byte[] key,
                                              byte[] value,
                                              Function<T, byte[]> valueSupplier) {
        new FutureHistoryRecorder(this,
                quorumReplicaClient.id,
                history,
                new FutureHistoryRecorder.Operation(op, key, value, finalTick),
                set,
                valueSupplier);
    }

    protected abstract void issueRequest(ClusterClient client, Random clusterSeededRandom, long tick);


    protected class FutureHistoryRecorder<T> {
        static record Operation(Op op, byte[] key, byte[] value, long tick) {
        }

        public FutureHistoryRecorder(SimulationRunner simulationRunner, ProcessId processId, History history, Operation operation, ListenableFuture<T> future, Function<T, byte[]> valueSupplier) {
            future.handle(new BiConsumer<T, Throwable>() {
                @Override
                public void accept(T setResponse, Throwable exception) {
                    if (exception == null) {
                        byte[] value = valueSupplier.apply(setResponse);
                        history.ok(processId.name(), operation.op, operation.key, value, operation.tick);
                    } else {
                        if (exception instanceof TimeoutException) {
                            history.timeout(processId.name(), operation.op, operation.key, operation.value, operation.tick);
                        }
                        history.fail(processId.name(), operation.op, operation.key, operation.value, operation.tick);
                    }
                    pendingRequests.put(processId, false);
                    simulationRunner.issueNextRequest(processId);
                }
            });
        }
    }


    //We can probably use only buffered requests to figure out if there are any pending requests
    Map<ProcessId, Boolean> pendingRequests = new HashMap<>();
    Map<ProcessId, Queue<Runnable>> bufferedRequests = new HashMap<>();

    private void issueNextRequest(ProcessId processId) {
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

    String randomValue() {
        return "Value-" + cluster.getRandom().nextInt();
    }

    String randomKey() {
        return "Key-" + cluster.getRandom().nextInt();
    }

    private static long elapsedTime(long start) {
        return System.nanoTime() - start;
    }
}


class QuorumSimulationRunner extends SimulationRunner {

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

        if (doSet) {

            QuorumReplicaClient quorumReplicaClient = client;
            ListenableFuture<SetResponse> setFuture = quorumReplicaClient.set(key.getBytes(), value.getBytes());//clients.get(0)
            history.invoke(quorumReplicaClient.id.name(), Op.WRITE, key.getBytes(), value.getBytes(), tick);
            wrapFutureForRecording(tick, quorumReplicaClient, setFuture, Op.WRITE, key.getBytes(), value.getBytes(),
                    (setResponse) -> value.getBytes());


        } else {
            QuorumReplicaClient quorumReplicaClient = client;
            ListenableFuture<GetResponse> getFuture = quorumReplicaClient.get(key.getBytes());//clients.get(0)
            history.invoke(quorumReplicaClient.id.name(), Op.READ, key.getBytes(), null, tick);
            wrapFutureForRecording(tick, quorumReplicaClient,
                    getFuture, Op.READ, key.getBytes(), null,
                    (getResponse) -> getResponse.value());
        }
    }

}