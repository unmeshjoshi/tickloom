package com.tickloom.testkit.dsl;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.ClusterClient;

public interface ConnectedToStep<C extends ClusterClient, T extends ActionScope, G extends SetupScope> {
    ClientDefStep<C, T, G> connectedTo(ProcessId serverId);
}
