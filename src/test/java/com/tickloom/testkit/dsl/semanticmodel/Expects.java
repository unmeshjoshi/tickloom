package com.tickloom.testkit.dsl.semanticmodel;

import com.tickloom.future.TickCompletableFuture;
import com.tickloom.testkit.Cluster;

import java.util.function.Predicate;

/**
 * Await condition that runs a predicate against the action's future once the
 * future reaches a terminal state (completed successfully or failed). The
 * guard prevents user predicates from seeing a still-pending future, where
 * {@code getResult()} / {@code getException()} would throw.
 */
public record Expects(Predicate<TickCompletableFuture<?>> check) implements AwaitCondition {
    @Override
    public boolean isFulfilled(TickCompletableFuture<?> future, Cluster cluster) {
        return !future.isPending() && check.test(future);
    }
}
