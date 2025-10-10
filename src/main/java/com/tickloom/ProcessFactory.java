package com.tickloom;

import com.tickloom.algorithms.replication.quorum.QuorumReplicaProcessFactory;
import com.tickloom.messaging.MessageBus;
import com.tickloom.network.MessageCodec;
import com.tickloom.storage.Storage;
import com.tickloom.util.Clock;

import java.util.List;

/**
 * Abstraction for constructing a server {@link Process} at startup.
 * <p>
 * Intent:
 * - Allows library users to plug in their own {@link Process} (or {@code Replica}) implementation
 *   without modifying the command-line launcher.
 * - Used by {@link com.tickloom.cmd.ServerMain} when the {@code --factory <fqcn>} flag is supplied.
 * - If no factory is provided, the launcher falls back to the default (e.g. {@code QuorumReplica}).
 * <p>
 * Example:
 * @see QuorumReplicaProcessFactory
 * <pre>{@code
 * public final class MyProcessFactory implements ProcessFactory {
 *   @Override
 *   public Process create(ProcessId id,
 *                         List<ProcessId> peerIds,
 *                         MessageBus bus,
 *                         MessageCodec codec,
 *                         Storage storage,
 *                         Clock clock,
 *                         int timeoutTicks) {
 *     return new MyReplica(id, peerIds, bus, codec, storage, clock, timeoutTicks);
 *   }
 * }
 * // Run:
 * // java -jar tickloom-server-all.jar --config conf.yaml --id server-1 \
 * //      --factory com.example.MyProcessFactory
 * }</pre>
 */
public interface ProcessFactory {

    /**
     * Constructs a configured {@link Process} instance for the given server id and cluster context.
     *
     * @param peerIds       other server ids in the cluster (excludes {@code id})
     * @param storage       server's {@link Storage} (e.g., RocksDB-backed)
     * @param processParams
     * @return a fully constructed {@link Process} ready to participate in the event loop
     */
    Process create(List<ProcessId> peerIds,
                   Storage storage, ProcessParams processParams);
}


