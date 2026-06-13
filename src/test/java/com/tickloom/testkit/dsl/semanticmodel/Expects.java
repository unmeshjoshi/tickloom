package com.tickloom.testkit.dsl.semanticmodel;

import com.tickloom.future.TickCompletableFuture;
import com.tickloom.testkit.Cluster;

import java.util.function.Predicate;

public record Expects(Predicate<TickCompletableFuture<?>> check) implements AwaitCondition {
    @Override
    public boolean isFulfilled(TickCompletableFuture<?> future, Cluster cluster) {
        return future.isCompleted() && check.test(future);
    }
}
