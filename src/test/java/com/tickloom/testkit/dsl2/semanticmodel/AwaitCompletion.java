package com.tickloom.testkit.dsl2.semanticmodel;

import com.tickloom.future.TickCompletableFuture;
import com.tickloom.testkit.Cluster;

public record AwaitCompletion() implements AwaitCondition {
    @Override
    public boolean isFulfilled(TickCompletableFuture<?> future, Cluster cluster) {
        return future.isCompleted() && !future.isFailed();
    }
}
