package com.tickloom.testkit.dsl2;

import com.tickloom.algorithms.replication.ClusterClient;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Returned from a client verb. Chain on {@code .duringClusterEvents(...)} and exactly one
 * of {@code .waitForCompletion()}, {@code .waitForNodeState(...)}, {@code .expectReturn(V)},
 * or {@code .expectPending()}.
 */
public final class StepBuilder<C extends ClusterClient, V> {
    private final Step<C, V> step;

    StepBuilder(Step<C, V> step) { this.step = step; }

    public StepBuilder<C, V> duringClusterEvents(Consumer<EventsBuilder> spec) {
        EventsBuilder eb = new EventsBuilder();
        spec.accept(eb);
        ClusterEvent existing = step.clusterEvent();
        ClusterEvent fresh = eb.toEvent();
        step.withClusterEvent(existing == ClusterEvent.NO_OP
                ? fresh
                : CompositeEvent.of(existing, fresh));
        return this;
    }

    public StepBuilder<C, V> waitForCompletion() {
        step.withAwait(new AwaitCompletion());
        return this;
    }

    public StepBuilder<C, V> waitForNodeState(String node, Predicate<NodeView> predicate) {
        step.withAwait(new AwaitState(cluster ->
                predicate.test(new ClusterNodeView(cluster, com.tickloom.ProcessId.of(node)))));
        return this;
    }

    public StepBuilder<C, V> expectReturn(V expected) {
        step.withAwait(new AwaitReturn<>(expected));
        return this;
    }

    public StepBuilder<C, V> expectPending() {
        step.withAwait(new AwaitPending());
        return this;
    }

    public StepBuilder<C, V> expectPending(int ticks) {
        step.withAwait(new AwaitPending(ticks));
        return this;
    }
}
