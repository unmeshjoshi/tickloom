package com.tickloom.testkit.dsl2;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.testkit.Cluster;

import java.util.List;
import java.util.Map;

public interface Action<T extends ClusterClient> {
     void executeWith(Map<ProcessId, T> clients, Cluster cluster);
}
