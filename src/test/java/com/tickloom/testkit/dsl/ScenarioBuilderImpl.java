package com.tickloom.testkit.dsl;

import com.tickloom.ProcessFactory;
import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.testkit.Cluster;
import com.tickloom.testkit.dsl.semanticmodel.ClientDef;
import com.tickloom.testkit.dsl.semanticmodel.Scenario;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        implements ServerStep<C, T, G>, ClientStep<C,T, G>, ClientDefStep<C, T, G> {

    private final String name;
    private final ProcessFactory replicaFactory;
    private final Cluster.ClientFactory<C> clientFactory;
    private final StepBuilder<C, T, G> stepBuilder;

    private List<ProcessId> serverIds = List.of();
    private final Map<ProcessId, ClientDef> clientDefs = new HashMap<>();

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
    public ClientStep<C, T, G> servers(ProcessId... ids) {
        this.serverIds = List.of(ids);
        return this;
    }

    @Override
    public ClientDefStep<C, T, G> clients(ProcessId... ids) {
        for (ProcessId id : ids) {
            clientDefs.put(id, new ClientDef(id, null));
        }
        return this;
    }

    ClientDef pending;
    @Override
    public ClientDefStep<C, T, G> client(ProcessId id) {
        pending = clientDefs.get(id);
        return this;
    }

    @Override
    public ClientDefStep<C, T, G> connectedTo(ProcessId serverId) {
        if (pending == null) {
            throw new IllegalStateException("client() must be called before connectedTo()");
        }
        clientDefs.put(pending.id(), new ClientDef(pending.id(), serverId));
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
        return new Scenario<>(name, serverIds, clientDefs.values().stream().toList(),
                stepBuilder.collectedGivens(),
                replicaFactory, clientFactory,
                stepBuilder.collectedSteps());
    }
}
