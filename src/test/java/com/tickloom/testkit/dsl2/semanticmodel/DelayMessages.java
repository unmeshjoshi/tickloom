package com.tickloom.testkit.dsl2.semanticmodel;

import com.tickloom.ProcessId;
import com.tickloom.messaging.MessageType;
import com.tickloom.testkit.Cluster;

import java.util.List;

public record DelayMessages(MessageType messageType, ProcessId from, List<ProcessId> to, int ticks)
        implements ClusterEvent {

    @Override
    public void introduceIn(Cluster cluster) {
        cluster.delayForMessageType(messageType, from, to, ticks);
    }
}
