package com.tickloom.testkit.dsl2;

import com.tickloom.testkit.Cluster;

import java.util.List;

/**
 * Applies a sequence of cluster events in order. Use to compose multiple failures into one step.
 */
public record CompositeEvent(List<ClusterEvent> events) implements ClusterEvent {

    public static CompositeEvent of(ClusterEvent... events) {
        return new CompositeEvent(List.of(events));
    }

    @Override
    public void introduceIn(Cluster cluster) {
        for (ClusterEvent e : events) e.introduceIn(cluster);
    }
}
