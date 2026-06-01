package com.tickloom.testkit.dsl2;

import com.tickloom.ProcessId;
import com.tickloom.testkit.Cluster;

public record Reconnect(ProcessId processId) implements ClusterEvent {
    @Override
    public void introduceIn(Cluster cluster) {
        cluster.reconnectProcess(processId);
    }
}
