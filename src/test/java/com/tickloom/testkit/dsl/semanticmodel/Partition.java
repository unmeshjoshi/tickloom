package com.tickloom.testkit.dsl.semanticmodel;

import com.tickloom.ProcessId;
import com.tickloom.testkit.Cluster;
import com.tickloom.testkit.NodeGroup;

import java.util.List;

public record Partition(List<ProcessId> group1, List<ProcessId> group2) implements ClusterEvent {

    @Override
    public void introduceIn(Cluster cluster) {
        cluster.partitionNodes(
                NodeGroup.of(group1.toArray(new ProcessId[0])),
                NodeGroup.of(group2.toArray(new ProcessId[0])));
    }
}
