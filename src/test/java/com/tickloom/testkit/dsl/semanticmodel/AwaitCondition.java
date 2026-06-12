package com.tickloom.testkit.dsl.semanticmodel;

import com.tickloom.future.TickCompletableFuture;
import com.tickloom.testkit.Cluster;

public interface AwaitCondition {
    boolean isFulfilled(TickCompletableFuture<?> future, Cluster cluster);

    AwaitCondition NO_AWAIT = (future, cluster) -> true;
}
