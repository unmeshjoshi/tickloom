package com.tickloom;

import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.testkit.Cluster;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public abstract class FaultInjectingSimulationRunner extends SimulationRunner {
    private static final int MAX_DELAY_TICKS = 8;

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
        if (simulationDecisions.shouldHealNetwork(currentTick())) {
            cluster().resetNetworkConditions();
            faultSchedule.add("tick=" + currentTick() + " heal-all");
            return;
        }

        if (!simulationDecisions.shouldInjectFault()) {
            return;
        }

        SimulationDecisions.FaultType fault = simulationDecisions.chooseFaultType();
        switch (fault) {
            case ISOLATE_NODE   -> isolateRandomNode();
            case PARTITION_PAIR -> partitionRandomPair();
            case DELAY_LINK     -> delayRandomDirectedLink();
            case DROP_LINK      -> dropRandomDirectedLink();
        }
    }

    public List<String> faultSchedule() {
        return List.copyOf(faultSchedule);
    }

    private void isolateRandomNode() {
        ProcessId node = simulationDecisions.pick(cluster().getServerProcessIds());
        cluster().isolateProcess(node);
        faultSchedule.add("tick=" + currentTick() + " isolate " + node.name());
    }

    private void partitionRandomPair() {
        ProcessId source = simulationDecisions.pick(cluster().getServerProcessIds());
        ProcessId destination = simulationDecisions.pick(otherNodes(source));
        cluster().partitionNodes(source, destination);
        faultSchedule.add("tick=" + currentTick() + " partition " + source.name() + "<->" + destination.name());
    }

    private void delayRandomDirectedLink() {
        ProcessId source = simulationDecisions.pick(cluster().getServerProcessIds());
        ProcessId destination = simulationDecisions.pick(otherNodes(source));
        int delayTicks = simulationDecisions.between(1, MAX_DELAY_TICKS);
        cluster().setNetworkDelay(source, destination, delayTicks);
        faultSchedule.add("tick=" + currentTick() + " delay " + source.name() + "->" + destination.name() + " by " + delayTicks);
    }

    private void dropRandomDirectedLink() {
        ProcessId source = simulationDecisions.pick(cluster().getServerProcessIds());
        ProcessId destination = simulationDecisions.pick(otherNodes(source));
        cluster().setPacketLoss(source, destination, 1.0);
        faultSchedule.add("tick=" + currentTick() + " drop " + source.name() + "->" + destination.name());
    }

    private List<ProcessId> otherNodes(ProcessId excluded) {
        return cluster().getServerProcessIds().stream()
                .filter(id -> !id.equals(excluded))
                .toList();
    }
}
