package com.tickloom.testkit.dsl2;

import com.tickloom.future.TickCompletableFuture;
import com.tickloom.testkit.Cluster;

import java.util.function.Predicate;

public record AwaitState(Predicate<Cluster> predicate) implements AwaitCondition {
    @Override
    public boolean isFulfilled(TickCompletableFuture<?> future, Cluster cluster) {
        return predicate.test(cluster);
    }
}
