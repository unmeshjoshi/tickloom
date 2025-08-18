package com.tickloom;

import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.algorithms.replication.quorum.GetResponse;
import com.tickloom.algorithms.replication.quorum.QuorumReplica;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.algorithms.replication.quorum.SetResponse;
import com.tickloom.future.ListenableFuture;
import com.tickloom.testkit.Cluster;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeoutException;

public class SimulationRunner {
    private final ArrayList<ClusterClient> clients;
    private final Duration runForDuration;
    private Cluster cluster;
    public SimulationRunner(Duration runForDuration, long randomSeed) throws IOException {
        this.runForDuration = runForDuration;
        this.cluster = new Cluster()
                .withSeed(randomSeed)
                .withNumProcesses(3)
                .useSimulatedNetwork()
                .build(QuorumReplica::new);
        this.clients = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            clients.add(cluster.newClient(ProcessId.of("client-" + i), QuorumReplicaClient::new));

        }
    }

    public static void main(String[] args) throws IOException {
        SimulationRunner simulationRunner = new SimulationRunner(Duration.ofSeconds(60), 999999);
        simulationRunner.run();
    }

    History history = new History();
    public void run() {
        long tickDuration = 685645;
        double issueProbabilityPerTick = 0.4;
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

            String key = randomKey();
            String value = randomValue();

            // Pick operation.
            boolean doSet = clusterSeededRandom.nextBoolean();

            if (doSet) {

                QuorumReplicaClient quorumReplicaClient = (QuorumReplicaClient) clients.get(clusterSeededRandom.nextInt(clients.size()));
                ListenableFuture<SetResponse> set = quorumReplicaClient.set(key.getBytes(), value.getBytes());//clients.get(0)
                history.invoke(quorumReplicaClient.id.name(), Op.WRITE, key.getBytes(), value.getBytes(), tick);

                long finalTick = tick;
                set.handle((response, exception) -> {
                   if (exception == null) {
                       history.ok(quorumReplicaClient.id.name(), Op.WRITE, key.getBytes(), value.getBytes(), finalTick);
                   } else {
                       if (exception instanceof TimeoutException) {
                           history.timeout(quorumReplicaClient.id.name(), Op.WRITE, key.getBytes(), value.getBytes(), finalTick);
                       }
                       history.fail(quorumReplicaClient.id.name(), Op.WRITE, key.getBytes(), value.getBytes(), finalTick);
                   }

                });

            } else {
                QuorumReplicaClient quorumReplicaClient = (QuorumReplicaClient) clients.get(0);
                ListenableFuture<GetResponse> getResponseListenableFuture = quorumReplicaClient.get(key.getBytes());//clients.get(0)
                history.invoke(quorumReplicaClient.id.name(), Op.READ, key.getBytes(), null, tick);
                long finalTick1 = tick;
                getResponseListenableFuture.handle((response, exception) -> {
                    if (exception == null) {
                        history.ok(quorumReplicaClient.id.name(), Op.READ, key.getBytes(), response.value(), finalTick1);
                    } else {
                        if (exception instanceof TimeoutException) {
                            history.timeout(quorumReplicaClient.id.name(), Op.READ, key.getBytes(), null, finalTick1);
                        }
                        history.fail(quorumReplicaClient.id.name(), Op.READ, key.getBytes(), null, finalTick1);
                    }

                });
            }
        }

        printHistory();
    }

    private void printHistory() {
        history.all().forEach(System.out::println);
    }

    private String randomValue() {
        return "Value-" + cluster.getRandom().nextInt();
    }

    private String randomKey() {
        return "Key-" + cluster.getRandom().nextInt();
    }

    private static long elapsedTime(long start) {
        return System.nanoTime() - start;
    }
}
