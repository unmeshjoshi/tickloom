package com.tickloom.testkit.dsl2.semanticmodel;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.history.HistoryRecorder;
import com.tickloom.testkit.Cluster;

import java.util.Map;

public final class Step<C extends ClusterClient, V> {
    private final Action<C, V> action;
    private ClusterEvent clusterEvent = ClusterEvent.NO_OP;
    private AwaitCondition awaitCondition = AwaitCondition.NO_AWAIT;

    public Step(Action<C, V> action) {
        this.action = action;
    }

    public Step<C, V> withClusterEvent(ClusterEvent event) {
        this.clusterEvent = event;
        return this;
    }

    public Step<C, V> withAwait(AwaitCondition await) {
        this.awaitCondition = await;
        return this;
    }

    public Action<C, V> action() {
        return action;
    }

    public ClusterEvent clusterEvent() {
        return clusterEvent;
    }

    public AwaitCondition awaitCondition() {
        return awaitCondition;
    }

    public void execute(Map<ProcessId, C> clients, Cluster cluster, HistoryRecorder<String> recorder) {
        clusterEvent.introduceIn(cluster);
        TickCompletableFuture<V> future = action.executeWith(clients, cluster, recorder);
        cluster.tickUntil(() -> awaitCondition.isFulfilled(future, cluster));
    }
}
