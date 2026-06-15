package com.tickloom.testkit.dsl.semanticmodel;

import com.tickloom.Process;
import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.testkit.Cluster;

/**
 * Read-only view of the in-memory state of a {@link Cluster}.
 *
 * <p>A snapshot wraps a reference to a cluster that may already be closed
 * (e.g. the cluster created inside {@code Scenario.run()} is closed by the
 * time the {@code ScenarioResult} is returned). Only state-reading accessors
 * are exposed — anything that would tick the simulation or send messages is
 * intentionally absent. Use snapshots to write post-run assertions on
 * replica / client state without leaking the live cluster lifecycle into
 * the test.
 *
 * <p>Backed by reference, not by deep copy: the process and client objects
 * held by the wrapped cluster are surfaced as-is. They are safe to read for
 * in-memory state (clocks, logs, state machines, etc.) but must not be
 * mutated after capture for accessors to remain meaningful.
 */
public final class ClusterSnapshot {

    private final Cluster cluster;

    public ClusterSnapshot(Cluster cluster) {
        this.cluster = cluster;
    }

    /**
     * Get node for concrete
     * replica types (e.g. {@code RaftServer}, {@code TransactionalStorageReplica}).
     *
     * @throws ClassCastException if the registered process is not of type {@code T}.
     */
    public <T extends Process> T node(ProcessId id) {
        return cluster.getNode(id);
    }

    /**
     * Returns the {@link ClusterClient} registered under {@code id} at
     * snapshot time.
     *
     * @throws IllegalArgumentException if no client is registered under that id.
     */
    public <T extends ClusterClient> T client(ProcessId id) {
        return (T) cluster.getClient(id);
    }
}
