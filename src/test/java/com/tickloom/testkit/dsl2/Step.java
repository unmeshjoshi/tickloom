package com.tickloom.testkit.dsl2;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.history.HistoryRecorder;
import com.tickloom.testkit.Cluster;

import java.util.Map;
import java.util.Objects;

public final class Step<C extends ClusterClient, V> {
    private final Action<C, V> action;
    private ClusterEvent clusterEvent = ClusterEvent.NO_OP;
    private AwaitCondition awaitCondition = AwaitCondition.NO_AWAIT;

    public Step(Action<C, V> action) {
        this.action = action;
    }

    public void withClusterEvent(ClusterEvent event) { this.clusterEvent = event; }
    public void withAwait(AwaitCondition await) { this.awaitCondition = await; }

    public Action<C, V> action() { return action; }
    public ClusterEvent clusterEvent() { return clusterEvent; }
    public AwaitCondition awaitCondition() { return awaitCondition; }

    public void execute(Map<ProcessId, C> clients, Cluster cluster, HistoryRecorder<String> recorder) {
        clusterEvent.introduceIn(cluster);
        TickCompletableFuture<V> future = action.executeWith(clients, cluster, recorder);

        if (awaitCondition instanceof AwaitPending pending) {
            for (int i = 0; i < pending.ticks(); i++) cluster.tick();
            if (future.isCompleted()) {
                throw new AssertionError("Expected step to stay pending for "
                        + pending.ticks() + " ticks but it completed.");
            }
            return;
        }

        cluster.tickUntil(() -> awaitCondition.isFulfilled(future, cluster));

        if (awaitCondition instanceof AwaitReturn<?> ret) {
            V actual = future.getResult();
            if (!Objects.equals(ret.expected(), actual)) {
                throw new AssertionError("expectReturn mismatch: expected "
                        + ret.expected() + " but got " + actual);
            }
        }
    }
}
