package com.tickloom.testkit.dsl;

import com.tickloom.testkit.Cluster;
import com.tickloom.testkit.dsl.semanticmodel.ClusterEvent;

import java.util.function.Predicate;

public interface EventOrAwaitScope<T extends ActionScope> {
    EventOrAwaitScope<T> whileClusterEvent(ClusterEvent event);
    StepScope<T> awaitCompletion();
    StepScope<T> awaitCompletion(Object expectedResult);
    StepScope<T> await(Predicate<Cluster> condition);
}
