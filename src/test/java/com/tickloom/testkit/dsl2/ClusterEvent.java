package com.tickloom.testkit.dsl2;

import com.tickloom.testkit.Cluster;

public sealed interface ClusterEvent permits ClusterEvent.NoOp {
    void introduceIn(Cluster cluster);

    enum NoOp implements ClusterEvent {
        INSTANCE;

        @Override
        public void introduceIn(Cluster cluster) {
        }
    }
}
