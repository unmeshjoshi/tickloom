package com.tickloom.testkit.dsl;

import com.tickloom.ProcessFactory;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.testkit.Cluster;

import java.util.function.Supplier;

/**
 * Public factory used by protocol-specific entry points (e.g.
 * {@code QuorumStepBuilder.scenario(name)}) to obtain a {@link ServerStep}
 * without exposing the package-private builder implementation.
 */
public final class Scenarios {
    private Scenarios() { }

    public static <C extends ClusterClient,
                   T extends ActionScope,
                   G extends SetupScope>
            ServerStep<C, T, G> scenario(
                    String name,
                    ProcessFactory replicaFactory,
                    Cluster.ClientFactory<C> clientFactory,
                    Supplier<? extends StepBuilder<C, T, G>> stepBuilderSupplier) {
        return new ScenarioBuilderImpl<>(name, replicaFactory, clientFactory, stepBuilderSupplier);
    }
}
