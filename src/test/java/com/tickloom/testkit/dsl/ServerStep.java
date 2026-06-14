package com.tickloom.testkit.dsl;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.ClusterClient;

public interface ServerStep<C extends ClusterClient, T extends ActionScope, G extends SetupScope> {
    ClientStep<C, T, G> servers(ProcessId... ids);
}
