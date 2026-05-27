package com.tickloom.testkit.dsl;

import java.util.List;

public interface ClusterBehaviourBuilder {
    ClusterBehaviourBuilder delayedMessages(String from, List<String> to, int ticks);
    ClusterBehaviourBuilder partition(List<String> group1, List<String> group2);
    ClusterBehaviourBuilder reconnect(String processId);
}
