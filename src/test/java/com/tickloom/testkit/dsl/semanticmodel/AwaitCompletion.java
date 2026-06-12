package com.tickloom.testkit.dsl.semanticmodel;

import com.tickloom.future.TickCompletableFuture;
import com.tickloom.testkit.Cluster;

public record AwaitCompletion(Object expectedResult) implements AwaitCondition {
    public AwaitCompletion() {
        this(null);
    }
    @Override
    public boolean isFulfilled(TickCompletableFuture<?> future, Cluster cluster) {
        boolean isFulfilled = future.isCompleted() && !future.isFailed();
        if (expectedResult != null && isFulfilled) {
            isFulfilled = expectedResult.equals(future.getResult());
        }
        return isFulfilled;
    }
}
