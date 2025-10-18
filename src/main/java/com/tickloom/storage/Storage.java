package com.tickloom.storage;

import com.tickloom.Tickable;
import com.tickloom.future.ListenableFuture;

import java.util.List;
import java.util.Map;

/**
 * Interface for asynchronous key-value storage operations.
 * All operations return ListenableFuture to support non-blocking I/O
 * in the single-threaded event loop.
 * 
 * This interface supports both simple byte array operations
 * like batch writes, range queries, and prefix operations.
 *
 * The expected implementations are expected to store key values
 * in sorted order of keys.
 */
public interface Storage extends Tickable, AutoCloseable {
    /**
     * Retrieves a value for the given key.
     * 
     * @param key the key to retrieve
     * @return a future containing the value, or null if not found
     */
    ListenableFuture<byte[]> get(byte[] key);
    
    /**
     * Stores a value for the given key.
     * 
     * @param key the key to store
     * @param value the value to store
     * @return a future containing true if successful, false otherwise
     */
    ListenableFuture<Boolean> put(byte[] key, byte[] value);
    
    /**
     * Stores a value for the given key with write options.
     * sync is a particularly important option that tells the storage to flush
     * the key values written to physical media.
     *
     * @param key the key to store
     * @param value the value to store
     * @param options write options (sync, etc.)
     * @return a future containing true if successful, false otherwise
     */
    ListenableFuture<Boolean> put(byte[] key, byte[] value, WriteOptions options);

    /**
     * Performs atomic batch write operations.
     * All key-value pairs in the batch are written together atomically.
     * 
     * @param writeBatch the WriteBatch containing key-value pairs to write
     * @return a future containing true if all operations succeeded, false otherwise
     */
    ListenableFuture<Boolean> put(WriteBatch writeBatch);
    
    /**
     * Performs atomic batch write operations with write options.
     * 
     * @param writeBatch the WriteBatch containing key-value pairs to write
     * @param options write options for the entire batch
     * @return a future containing true if all operations succeeded, false otherwise
     */
    ListenableFuture<Boolean> put(WriteBatch writeBatch, WriteOptions options);
    
    /**
     * Retrieves values for a range of keys.
     * 
     * @param startKey the start key (inclusive)
     * @param endKey the end key (exclusive)
     * @return a future containing a map of key-value pairs
     */
    ListenableFuture<Map<byte[], byte[]>> readRange(byte[] startKey, byte[] endKey);
    
    /**
     * Retrieves all keys with the given prefix.
     * 
     * @param prefix the prefix to search for
     * @return a future containing a list of keys with the prefix
     */
    ListenableFuture<List<byte[]>> keysWithPrefix(byte[] prefix);
    
    /**
     * Retrieves the last key in the store (lexicographically).
     * 
     * @return a future containing the last key, or null if store is empty
     */
    ListenableFuture<byte[]> lastKey();
    
    /**
     * Ensures all pending writes are flushed to disk for durability.
     * This method should be called after critical operations to guarantee
     * that data is persisted to stable storage.
     * 
     * @return a future that completes when the sync operation is finished
     */
    ListenableFuture<Void> sync();
    
    /**
     * Advances the storage simulation by one tick.
     * This method processes pending operations and completes their futures.
     * Should be called by the simulation loop.
     * Storage implementations manage their own internal tick counters for timing.
     */
    void tick();

    @Override
    default void close() throws Exception {
        // Default implementation does nothing
    }
    
    // ========== Helper Records ==========
    
    /**
     * Write options for storage operations.
     * sync is particularly important for the durability guarantee.
     * disableWAL is specific to RocksDB, but kept here to demonstrate how additional options can be added.
     * 
     * @param sync whether to sync to disk immediately
     * @param disableWAL whether to disable Write-Ahead Logging
     */
    record WriteOptions(boolean sync, boolean disableWAL) {
        public static WriteOptions DEFAULT = new WriteOptions(false, false);
        /**
         * Write options for critical operations that require immediate durability.
         */
        public static WriteOptions SYNC = new WriteOptions(true, false);
        
        /**
         * Write options for high-performance writes (no sync, no WAL).
         * Use with caution as data may be lost on crash.
         */
        public static WriteOptions FAST = new WriteOptions(false, true);
    }
    
} 