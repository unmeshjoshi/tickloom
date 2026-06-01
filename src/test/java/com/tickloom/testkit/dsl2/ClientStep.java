package com.tickloom.testkit.dsl2;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.ClusterClient;

public abstract class ClientStep<C extends ClusterClient> {
    protected final ProcessId clientId;
    private final Steps<C, ?> owner;

    protected ClientStep(String name, Steps<C, ?> owner) {
        this.clientId = ProcessId.of(name);
        this.owner = owner;
    }

    public ProcessId clientId() { return clientId; }

    protected final <V> StepBuilder<C, V> record(Action<C, V> action) {
        return owner.record(new Step<>(action));
    }
}
