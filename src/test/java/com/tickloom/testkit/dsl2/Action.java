package com.tickloom.testkit.dsl2;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.algorithms.replication.quorum.SetResponse;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.testkit.Cluster;

import java.util.Map;

public interface Action<T extends ClusterClient> {
     TickCompletableFuture<?> executeWith(Map<ProcessId, T> clients, Cluster cluster);
}
