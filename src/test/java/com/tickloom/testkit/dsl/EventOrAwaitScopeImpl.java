package com.tickloom.testkit.dsl;

import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.testkit.Cluster;
import com.tickloom.testkit.dsl.semanticmodel.Action;
import com.tickloom.testkit.dsl.semanticmodel.AwaitCompletion;
import com.tickloom.testkit.dsl.semanticmodel.AwaitCondition;
import com.tickloom.testkit.dsl.semanticmodel.AwaitState;
import com.tickloom.testkit.dsl.semanticmodel.ClusterEvent;
import com.tickloom.testkit.dsl.semanticmodel.Step;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

final class EventOrAwaitScopeImpl<C extends ClusterClient,
                                  T extends ActionScope,
                                  G extends SetupScope,
                                  V>
        implements EventOrAwaitScope<T> {

    private final StepBuilder<C, T, G> parent;
    private final Action<C, V> action;
    private final List<ClusterEvent> events = new ArrayList<>();

    EventOrAwaitScopeImpl(StepBuilder<C, T, G> parent, Action<C, V> action) {
        this.parent = parent;
        this.action = action;
    }

    @Override
    public EventOrAwaitScope<T> whileClusterEvent(ClusterEvent event) {
        events.add(event);
        return this;
    }

    @Override
    public StepScope<T> awaitCompletion() {
        return buildStep(new AwaitCompletion());
    }

    @Override
    public StepScope<T> awaitCompletion(Object expectedResult) {
        return buildStep(new AwaitCompletion(expectedResult));
    }

    @Override
    public StepScope<T> await(Predicate<Cluster> condition) {
        return buildStep(new AwaitState(condition));
    }

    private StepScope<T> buildStep(AwaitCondition await) {
        Step<C, V> step = new Step<>(action);
        if (!events.isEmpty()) {
            step.withClusterEvent(compose(events));
        }
        step.withAwait(await);
        parent.addStep(step);
        return parent;
    }

    private static ClusterEvent compose(List<ClusterEvent> events) {
        List<ClusterEvent> snapshot = List.copyOf(events);
        return cluster -> snapshot.forEach(e -> e.introduceIn(cluster));
    }
}
