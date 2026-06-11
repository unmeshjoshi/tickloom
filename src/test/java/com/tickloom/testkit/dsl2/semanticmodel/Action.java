package com.tickloom.testkit.dsl2.semanticmodel;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.history.HistoryRecorder;
import com.tickloom.testkit.Cluster;

import java.util.Map;

/**
 * A protocol-specific request issued by a {@link ClientStep} verb.
 *
 * @param <C> the client type
 * @param <V> the projected result type; the returned future must already be mapped to this type
 *            (e.g. via {@link TickCompletableFuture#thenApply}) so that
 *            {@code expectReturn(V)} can compare against it directly.
 */
public interface Action<C extends ClusterClient, V> {
    TickCompletableFuture<V> executeWith(Map<ProcessId, C> clients,
                                         Cluster cluster,
                                         HistoryRecorder<String> recorder);
}
