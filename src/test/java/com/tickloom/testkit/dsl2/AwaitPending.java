package com.tickloom.testkit.dsl2;

import com.tickloom.future.TickCompletableFuture;
import com.tickloom.testkit.Cluster;

/**
 * Marker condition: the step should remain pending for {@link #ticks} cluster ticks.
 * {@link Step#execute} ticks the cluster directly for this case; this condition is never
 * polled, so it carries no mutable state.
 */
public record AwaitPending(int ticks) implements AwaitCondition {
    public static final int DEFAULT_TICKS = 50;

    public AwaitPending() { this(DEFAULT_TICKS); }

    @Override
    public boolean isFulfilled(TickCompletableFuture<?> future, Cluster cluster) {
        throw new UnsupportedOperationException("Step.execute drives pending steps directly");
    }
}
