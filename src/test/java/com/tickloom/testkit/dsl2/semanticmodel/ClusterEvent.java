package com.tickloom.testkit.dsl2.semanticmodel;

import com.tickloom.testkit.Cluster;

public interface ClusterEvent {
    void introduceIn(Cluster cluster);

    ClusterEvent NO_OP = cluster -> { };
}
