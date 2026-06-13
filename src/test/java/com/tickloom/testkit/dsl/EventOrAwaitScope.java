package com.tickloom.testkit.dsl;

import com.tickloom.future.TickCompletableFuture;
import com.tickloom.testkit.Cluster;
import com.tickloom.testkit.dsl.semanticmodel.ClusterEvent;

import java.util.function.Predicate;

public interface EventOrAwaitScope<T extends ActionScope, V> {

    EventOrAwaitScope<T, V> whileClusterEvent(ClusterEvent event);

    StepScope<T> expects(Predicate<TickCompletableFuture<V>> check);

    StepScope<T> await(Predicate<Cluster> condition);

    default StepScope<T> expectSuccess() {
        return expects(f -> !f.isFailed());
    }

    default StepScope<T> expectResponse(Predicate<V> responseCheck) {
        return expects(f -> !f.isFailed() && responseCheck.test(f.getResult()));
    }

    default StepScope<T> expectFailure() {
        return expects(TickCompletableFuture::isFailed);
    }

    default StepScope<T> expectFailure(Class<? extends Throwable> errorType) {
        return expects(f -> f.isFailed() && errorType.isInstance(f.getException()));
    }
}
