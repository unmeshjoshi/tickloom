package com.tickloom.io;

import com.tickloom.Tickable;
import com.tickloom.future.TickCompletableFuture;

/**
 * Node-owned filesystem — the storage simulation boundary.
 * <p>
 * Durability follows POSIX: file data is durable only after {@link FileIO#sync()};
 * creating a file is durable only after {@link #syncDirectory(String)} on its parent directory.
 * <p>
 * Operations are submitted immediately but complete during {@link #tick()}, which also
 * drives every file opened through this filesystem.
 */
public interface FileSystem extends Tickable {

    TickCompletableFuture<FileIO> open(String path, boolean create);

    /** Atomically renames {@code from} to {@code to}, replacing {@code to} if it exists. */
    TickCompletableFuture<Void> rename(String from, String to);

    TickCompletableFuture<Void> syncDirectory(String directory);
}
