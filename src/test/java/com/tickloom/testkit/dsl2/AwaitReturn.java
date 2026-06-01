package com.tickloom.testkit.dsl2;

import com.tickloom.future.TickCompletableFuture;
import com.tickloom.testkit.Cluster;

/**
 * Awaits completion, then matches the projected result against {@code expected}.
 * Step.verifyPostCondition performs the equality check after the future completes.
 */
public record AwaitReturn<V>(V expected) implements AwaitCondition {
    @Override
    public boolean isFulfilled(TickCompletableFuture<?> future, Cluster cluster) {
        return future.isCompleted() && !future.isFailed();
    }
}
