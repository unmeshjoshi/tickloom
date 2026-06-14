package com.tickloom.testkit.dsl;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.ClusterClient;

public interface ClientStep<C extends ClusterClient, T extends ActionScope, G extends SetupScope> {
    ClientDefStep<C, T, G> clients(ProcessId... ids);
}