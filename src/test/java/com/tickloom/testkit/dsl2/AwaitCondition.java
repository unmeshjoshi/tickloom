package com.tickloom.testkit.dsl2;

import com.tickloom.future.TickCompletableFuture;
import com.tickloom.testkit.Cluster;

public interface AwaitCondition {
    boolean isFulfilled(TickCompletableFuture<?> future, Cluster cluster);

    AwaitCondition NO_AWAIT = (future, cluster) -> true;
}
