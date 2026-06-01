package com.tickloom.testkit.dsl2;

import java.util.Optional;

/**
 * Read-only view of a single replica's storage, used inside {@code waitForNodeState}.
 * Predicates over a NodeView are free of references to the broader {@link com.tickloom.testkit.Cluster}.
 */
public interface NodeView {
    /** Returns the raw bytes stored under {@code key}, or empty if missing. */
    Optional<byte[]> storedBytes(String key);

    /** Decodes the stored value as {@code type} via the cluster's codec. */
    <V> Optional<V> storedAs(String key, Class<V> type);

    /** Convenience: stored quorum {@code VersionedValue#value()} as a UTF-8 string. */
    Optional<String> storedString(String key);
}
