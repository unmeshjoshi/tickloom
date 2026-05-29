package com.tickloom.testkit.dsl2;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.testkit.Cluster;

import java.util.Map;

public class Step {
    Action action;
    ClusterEvent clusterEvent;
    AwaitCondition awaitCondition;

    public Step(Action action) {
        this(action, ClusterEvent.NoOp.INSTANCE, AwaitCondition.NoAwait.INSTANCE);
    }

    public Step(Action action, AwaitCondition awaitCondition) {
        this(action, ClusterEvent.NoOp.INSTANCE, awaitCondition);
    }

    public Step(Action action, ClusterEvent clusterEvent) {
        this(action, clusterEvent, AwaitCondition.NoAwait.INSTANCE);
    }

    public Step(Action action, ClusterEvent clusterEvent, AwaitCondition awaitCondition) {
        this.action = action;
        this.clusterEvent = clusterEvent;
        this.awaitCondition = awaitCondition;
    }

    public <C extends ClusterClient> void execute(Map<ProcessId, C> clients, Cluster cluster) {
        clusterEvent.introduceIn(cluster);
        TickCompletableFuture future = action.executeWith(clients, cluster);
        cluster.tickUntil(() -> awaitCondition.isFulfilled(future, cluster));
    }
}
