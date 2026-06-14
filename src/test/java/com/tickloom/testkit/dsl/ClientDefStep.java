package com.tickloom.testkit.dsl;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.testkit.dsl.semanticmodel.Scenario;

import java.util.function.Consumer;

public interface ClientDefStep<C extends ClusterClient, T extends ActionScope, G extends SetupScope> {
    ClientDefStep<C, T, G> client(ProcessId id);

    /**
     * Optional refinement of the most recently declared client. Constrains that
     * client's view of the cluster to a single node — the client will only know
     * about {@code serverId}, so every hash-routed request resolves to it.
     * Useful for partition-style scenarios where a client must be pinned to a
     * specific node; omit it for the common case where the client should see
     * the full cluster and route by hash.
     */
    ClientDefStep<C, T, G> connectedTo(ProcessId serverId);

    ClientDefStep<C, T, G> given(Consumer<G> givenConsumer);

    Scenario<C> steps(Consumer<StepScope<T>> stepsConsumer);
}
