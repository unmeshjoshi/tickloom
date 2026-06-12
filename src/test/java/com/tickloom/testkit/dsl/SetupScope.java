package com.tickloom.testkit.dsl;

import com.tickloom.testkit.dsl.semanticmodel.ClusterEvent;

/**
 * Marker for the protocol-specific verbs available inside a {@code given(...)}
 * block. Each protocol defines its own subtype (e.g. {@code QuorumSetupScope})
 * with verbs that touch protocol-specific state (storage seeds, clocks, logs).
 *
 * <p>The generic {@link #apply(ClusterEvent)} hook lets users drop in any
 * one-off {@code ClusterEvent} without forcing every protocol to add a verb
 * for it — mirroring how {@code whileClusterEvent} accepts arbitrary events
 * inside a step.
 */
public interface SetupScope {
    SetupScope apply(ClusterEvent event);
}
