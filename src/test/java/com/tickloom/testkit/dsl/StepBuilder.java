package com.tickloom.testkit.dsl;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.testkit.dsl.semanticmodel.Action;
import com.tickloom.testkit.dsl.semanticmodel.ClusterEvent;
import com.tickloom.testkit.dsl.semanticmodel.Step;

import java.util.ArrayList;
import java.util.List;

/**
 * Generic step-builder base. Subclasses implement two protocol-specific scopes:
 * {@link ActionScope} (verbs available inside a {@code steps} lambda after
 * {@code client(id)}) and {@link SetupScope} (verbs available inside a
 * {@code given} lambda for initial conditions).
 *
 * <p>Uses the simulated self-type idiom: {@code T} and {@code G} are the two
 * scopes; {@link #actionBuilder()} and {@link #setupBuilder()} return
 * {@code this} typed as the narrow projection so the chain enforces grammar
 * while one class holds the protocol's whole DSL surface.
 */
public abstract class StepBuilder<C extends ClusterClient,
                                  T extends ActionScope,
                                  G extends SetupScope>
        implements StepScope<T> {

    private final List<Step<C, ?>> collected = new ArrayList<>();
    private final List<ClusterEvent> givens = new ArrayList<>();
    private ProcessId currentClientId;

    @Override
    public final T client(ProcessId id) {
        this.currentClientId = id;
        return actionBuilder();
    }

    protected abstract T actionBuilder();
    protected abstract G setupBuilder();

    protected final ProcessId currentClientId() {
        return currentClientId;
    }

    protected final <V> EventOrAwaitScope<T> beginStep(Action<C, V> action) {
        return new EventOrAwaitScopeImpl<>(this, action);
    }

    /** Subclasses call this from their {@link SetupScope} verbs to register an initial condition. */
    protected final void addGiven(ClusterEvent event) {
        givens.add(event);
    }

    final void addStep(Step<C, ?> step) {
        collected.add(step);
    }

    final List<Step<C, ?>> collectedSteps() {
        return List.copyOf(collected);
    }

    final List<ClusterEvent> collectedGivens() {
        return List.copyOf(givens);
    }
}
