package com.tickloom.testkit.dsl2;

import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.testkit.Cluster;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Staged builder. Types flow from {@link #topology(Function)}'s return into {@link AfterTopology}:
 * the lambda must return a {@link TopologyBuilder.TypedTopology}{@code <C, S>}, and Java infers
 * {@code C} (client class) and {@code S} (steps class) from that return.
 */
public final class ScenarioBuilder {
    private final TopologyBuilder.State state;

    private ScenarioBuilder(String name) {
        this.state = new TopologyBuilder.State(name);
    }

    public static ScenarioBuilder scenario(String name) {
        return new ScenarioBuilder(name);
    }

    public <C extends ClusterClient, S extends Steps<C, ? extends ClientStep<C>>>
    AfterTopology<C, S> topology(Function<TopologyBuilder, TopologyBuilder.TypedTopology<C, S>> spec) {
        spec.apply(new TopologyBuilder(state));
        state.validate();
        return new AfterTopology<>(state);
    }

    public static final class AfterTopology<C extends ClusterClient,
            S extends Steps<C, ? extends ClientStep<C>>> {
        private final TopologyBuilder.State state;

        AfterTopology(TopologyBuilder.State state) { this.state = state; }

        @SuppressWarnings("unchecked")
        public AfterSteps<C> steps(Consumer<S> spec) {
            S s = (S) ((Supplier<?>) state.stepsFactory).get();
            spec.accept(s);
            Cluster.ClientFactory<C> clientFactory = (Cluster.ClientFactory<C>) state.clientFactory;
            return new AfterSteps<>(new Scenario<>(
                    state.name,
                    state.serverIds,
                    state.clientDefs,
                    state.replicaFactory,
                    clientFactory,
                    s.recorded()));
        }
    }

    public static final class AfterSteps<C extends ClusterClient> {
        private final Scenario<C> scenario;

        AfterSteps(Scenario<C> scenario) { this.scenario = scenario; }

        public Scenario<C> scenario() { return scenario; }

        public void run() throws IOException { scenario.run(); }
    }
}
