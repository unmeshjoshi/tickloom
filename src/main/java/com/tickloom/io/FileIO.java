package com.tickloom.io;

import com.tickloom.Tickable;
import com.tickloom.future.TickCompletableFuture;

/**
 * Low-level async file I/O interface — the simulation boundary.
 * <p>
 * Models positioned read/write/sync operations similar to io_uring.
 * Higher-level abstractions (WAL, LogStore) are built on top.
 * <p>
 * Operations are submitted immediately but complete during {@link #tick()}.
 */
public interface FileIO extends Tickable, AutoCloseable {

    TickCompletableFuture<Integer> write(byte[] data, long offset);

    TickCompletableFuture<byte[]> read(long offset, int length);

    TickCompletableFuture<Void> sync();

    TickCompletableFuture<Void> truncate(long size);

    long size();

    String getFilename();

    @Override
    default void close() throws Exception {
    }
}
