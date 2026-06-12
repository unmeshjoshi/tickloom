package com.tickloom.testkit.dsl;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.testkit.dsl.semanticmodel.Scenario;

import java.util.function.Consumer;

public interface ClientDefStep<C extends ClusterClient, T extends ActionScope, G extends SetupScope> {
    ConnectedToStep<C, T, G> client(ProcessId id);
    ClientDefStep<C, T, G> given(Consumer<G> givenConsumer);
    Scenario<C> steps(Consumer<StepScope<T>> stepsConsumer);
}
