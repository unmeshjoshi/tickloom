package com.tickloom;

import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.testkit.Cluster;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public abstract class FaultInjectingSimulationRunner extends SimulationRunner {
    private static final int HEAL_EVERY_TICKS = 40;
    private static final double FAULT_PROBABILITY_PER_TICK = 0.08;
    private static final int MAX_DELAY_TICKS = 8;

    private long currentTick = 0;
    private final List<String> faultSchedule = new ArrayList<>();

    public FaultInjectingSimulationRunner(long randomSeed,
                                          int clusterSize,
                                          int numClients,
                                          ProcessFactory processFactory,
                                          Cluster.ClientFactory<QuorumReplicaClient> clientFactory) throws IOException {
        super(randomSeed, clusterSize, numClients, processFactory, clientFactory);
    }

    @Override
    protected void injectRandomFaults() {
        currentTick++;
        Random rng = cluster().getRandom();

        if (currentTick % HEAL_EVERY_TICKS == 0) {
            cluster().resetNetworkConditions();
            faultSchedule.add("tick=" + currentTick + " heal-all");
            return;
        }

        if (rng.nextDouble() > FAULT_PROBABILITY_PER_TICK) {
            return;
        }

        int fault = rng.nextInt(4);
        switch (fault) {
            case 0 -> isolateRandomNode(rng);
            case 1 -> partitionRandomPair(rng);
            case 2 -> delayRandomDirectedLink(rng);
            case 3 -> dropRandomDirectedLink(rng);
            default -> {
            }
        }
    }

    public List<String> faultSchedule() {
        return List.copyOf(faultSchedule);
    }

    private void isolateRandomNode(Random rng) {
        ProcessId node = randomNode(rng);
        cluster().isolateProcess(node);
        faultSchedule.add("tick=" + currentTick + " isolate " + node.name());
    }

    private void partitionRandomPair(Random rng) {
        ProcessId source = randomNode(rng);
        ProcessId destination = randomOtherNode(rng, source);
        cluster().partitionNodes(source, destination);
        faultSchedule.add("tick=" + currentTick + " partition " + source.name() + "<->" + destination.name());
    }

    private void delayRandomDirectedLink(Random rng) {
        ProcessId source = randomNode(rng);
        ProcessId destination = randomOtherNode(rng, source);
        int delayTicks = 1 + rng.nextInt(MAX_DELAY_TICKS);
        cluster().setNetworkDelay(source, destination, delayTicks);
        faultSchedule.add("tick=" + currentTick + " delay " + source.name() + "->" + destination.name() + " by " + delayTicks);
    }

    private void dropRandomDirectedLink(Random rng) {
        ProcessId source = randomNode(rng);
        ProcessId destination = randomOtherNode(rng, source);
        cluster().setPacketLoss(source, destination, 1.0);
        faultSchedule.add("tick=" + currentTick + " drop " + source.name() + "->" + destination.name());
    }

    private ProcessId randomNode(Random rng) {
        List<ProcessId> nodes = cluster().getServerProcessIds();
        return nodes.get(rng.nextInt(nodes.size()));
    }

    private ProcessId randomOtherNode(Random rng, ProcessId excluded) {
        List<ProcessId> nodes = cluster().getServerProcessIds().stream()
                .filter(id -> !id.equals(excluded))
                .toList();
        return nodes.get(rng.nextInt(nodes.size()));
    }
}
