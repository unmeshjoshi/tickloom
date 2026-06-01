package com.tickloom.testkit.dsl2;

import com.tickloom.algorithms.replication.ClusterClient;

import java.util.function.Consumer;

/**
 * Returned from {@link Steps#untilConverged}. Intentionally narrower than {@link StepBuilder}: only
 * cluster events can be attached. The internal {@code waitForNodeState} await is fixed and cannot
 * be clobbered by another wait/expect verb.
 */
public final class ConvergenceBuilder<C extends ClusterClient> {
    private final Step<C, Void> step;

    ConvergenceBuilder(Step<C, Void> step) { this.step = step; }

    public ConvergenceBuilder<C> duringClusterEvents(Consumer<EventsBuilder> spec) {
        EventsBuilder eb = new EventsBuilder();
        spec.accept(eb);
        ClusterEvent existing = step.clusterEvent();
        ClusterEvent fresh = eb.toEvent();
        step.withClusterEvent(existing == ClusterEvent.NO_OP
                ? fresh
                : CompositeEvent.of(existing, fresh));
        return this;
    }
}
