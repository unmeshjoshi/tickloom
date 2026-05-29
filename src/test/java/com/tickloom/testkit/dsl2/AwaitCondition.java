package com.tickloom.testkit.dsl2;

import com.tickloom.future.TickCompletableFuture;
import com.tickloom.testkit.Cluster;

public sealed interface AwaitCondition permits AwaitCompletion, AwaitState, AwaitCondition.NoAwait {
    boolean isFulfilled(TickCompletableFuture future, Cluster cluster);

    enum NoAwait implements AwaitCondition {
        INSTANCE;

        @Override
        public boolean isFulfilled(TickCompletableFuture future, Cluster cluster) {
            return true;
        }
    }
}
