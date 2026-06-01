package com.tickloom.testkit.dsl2;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.quorum.VersionedValue;
import com.tickloom.testkit.Cluster;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

final class ClusterNodeView implements NodeView {
    private final Cluster cluster;
    private final ProcessId node;

    ClusterNodeView(Cluster cluster, ProcessId node) {
        this.cluster = cluster;
        this.node = node;
    }

    @Override
    public Optional<byte[]> storedBytes(String key) {
        byte[] v = cluster.getStorageValue(node, key.getBytes(StandardCharsets.UTF_8));
        return Optional.ofNullable(v);
    }

    @Override
    public <V> Optional<V> storedAs(String key, Class<V> type) {
        V v = cluster.getDecodedStoredValue(node, key.getBytes(StandardCharsets.UTF_8), type);
        return Optional.ofNullable(v);
    }

    @Override
    public Optional<String> storedString(String key) {
        return storedAs(key, VersionedValue.class)
                .map(vv -> new String(vv.value(), StandardCharsets.UTF_8));
    }
}
