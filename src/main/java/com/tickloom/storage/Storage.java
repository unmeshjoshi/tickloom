package com.tickloom.storage;

import com.tickloom.Tickable;
import com.tickloom.future.ListenableFuture;

/**
 * Interface for asynchronous key-name storage operations.
 * All operations return ListenableFuture to support non-blocking I/O
 * in the single-threaded event loop.
 */
public interface Storage extends Tickable, AutoCloseable {
    
    /**
     * Retrieves a name for the given key.
     * 
     * @param key the key to retrieve
     * @return a future containing the versioned name, or null if not found
     */
    ListenableFuture<VersionedValue> get(byte[] key);
    
    /**
     * Stores a name for the given key.
     * 
     * @param key the key to store
     * @param value the versioned name to store
     * @return a future containing true if successful, false otherwise
     */
    ListenableFuture<Boolean> set(byte[] key, VersionedValue value);
    
    /**
     * Advances the storage simulation by one tick.
     * This method processes pending operations and completes their futures.
     * Should be called by the simulation loop.
     * Storage implementations manage their own internal tick counters for timing.
     */
    void tick();

    @Override
    default void close() throws Exception {

    }
} 