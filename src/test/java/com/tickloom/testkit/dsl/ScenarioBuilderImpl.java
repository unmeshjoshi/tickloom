package com.tickloom.testkit.dsl;

import com.tickloom.ProcessFactory;
import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.testkit.Cluster;
import com.tickloom.testkit.dsl.semanticmodel.ClientDef;
import com.tickloom.testkit.dsl.semanticmodel.Scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Package-private builder backbone. Implements every topology scope and is
 * reached only through a protocol-specific public entry point (e.g.
 * {@code QuorumStepBuilder.scenario(name)}). The same {@link StepBuilder}
 * instance is reused for both the {@code given(...)} and {@code steps(...)}
 * blocks so initial conditions and steps land on the same collection.
 */
final class ScenarioBuilderImpl<C extends ClusterClient,
                                T extends ActionScope,
                                G extends SetupScope>
        implements ServerStep<C, T, G>, ClientDefStep<C, T, G>, ConnectedToStep<C, T, G> {

    private final String name;
    private final ProcessFactory replicaFactory;
    private final Cluster.ClientFactory<C> clientFactory;
    private final StepBuilder<C, T, G> stepBuilder;

    private List<ProcessId> serverIds = List.of();
    private final List<ClientDef> clientDefs = new ArrayList<>();
    private ProcessId pendingClientId;

    ScenarioBuilderImpl(String name,
                        ProcessFactory replicaFactory,
                        Cluster.ClientFactory<C> clientFactory,
                        Supplier<? extends StepBuilder<C, T, G>> stepBuilderSupplier) {
        this.name = name;
        this.replicaFactory = replicaFactory;
        this.clientFactory = clientFactory;
        this.stepBuilder = stepBuilderSupplier.get();
    }

    @Override
    public ClientDefStep<C, T, G> servers(ProcessId... ids) {
        this.serverIds = List.of(ids);
        return this;
    }

    @Override
    public ConnectedToStep<C, T, G> client(ProcessId id) {
        this.pendingClientId = id;
        return this;
    }

    @Override
    public ClientDefStep<C, T, G> connectedTo(ProcessId serverId) {
        clientDefs.add(new ClientDef(pendingClientId, serverId));
        pendingClientId = null;
        return this;
    }

    @Override
    public ClientDefStep<C, T, G> given(Consumer<G> givenConsumer) {
        givenConsumer.accept(stepBuilder.setupBuilder());
        return this;
    }

    @Override
    public Scenario<C> steps(Consumer<StepScope<T>> stepsConsumer) {
        stepsConsumer.accept(stepBuilder);
        return new Scenario<>(name, serverIds, clientDefs,
                stepBuilder.collectedGivens(),
                replicaFactory, clientFactory,
                stepBuilder.collectedSteps());
    }
}
