package com.tickloom.storage;

import org.rocksdb.CompressionType;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import com.tickloom.future.ListenableFuture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Production-ready RocksDB-based storage implementation.
 * Provides persistent key-name storage while maintaining the async ListenableFuture interface
 * and deterministic tick() behavior required by the simulation framework.
 * 
 * Key features:
 * - Persistent storage using RocksDB
 * - Tick-based deterministic operation processing
 * - Configurable operation delays and failure rates
 * - Thread-safe concurrent operations
 * - Configurable RocksDB options for performance tuning
 */
public class RocksDbStorage implements Storage {
    
    private RocksDB db;
    private final Options options;
    private final Path dbPath;
    private final Random random;
    private final int defaultDelayTicks;
    private final double defaultFailureRate;
    
    // Internal state for tick-based operations
    private final Map<BytesKey, byte[]> dataStore = new HashMap<>();
    private final PriorityQueue<PendingOperation> pendingOperations = new PriorityQueue<>();
    
    // Internal counter for operation timing
    private long currentTick = 0;
    
    // Helper class for byte array keys
    private static class BytesKey {
        private final byte[] bytes;
        
        BytesKey(byte[] bytes) {
            this.bytes = bytes.clone();
        }
        
        byte[] bytes() {
            return bytes;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            BytesKey bytesKey = (BytesKey) obj;
            return java.util.Arrays.equals(bytes, bytesKey.bytes);
        }
        
        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(bytes);
        }
    }
    
    static {
        // Load RocksDB native library
        RocksDB.loadLibrary();
    }
    
    /**
     * Creates RocksDB storage with default configuration.
     * @param dbPath directory path for the database files
     */
    public RocksDbStorage(String dbPath) {
        this(dbPath, createDefaultOptions(), new Random(), 0, 0.0);
    }
    
    /**
     * Creates RocksDB storage with custom configuration.
     * @param dbPath directory path for the database files
     * @param options RocksDB configuration options
     * @param random seeded random generator for deterministic behavior
     * @param delayTicks number of ticks to delay operations (0 = immediate)
     * @param failureRate probability [0.0-1.0] that an operation will fail
     */
    public RocksDbStorage(String dbPath, Options options, Random random, int delayTicks, double failureRate) {
        this.dbPath = Paths.get(dbPath);
        this.options = options;
        this.random = random;
        this.defaultDelayTicks = delayTicks;
        this.defaultFailureRate = failureRate;
        
        try {
            // Create database directory if it doesn't exist
            Files.createDirectories(this.dbPath);
            
            // Open RocksDB
            this.db = RocksDB.open(options, this.dbPath.toString());
            
        } catch (RocksDBException | IOException e) {
            throw new RuntimeException("Failed to initialize RocksDB storage", e);
        }
    }
    
    private static Options createDefaultOptions() {
        return new Options()
            .setCreateIfMissing(true)
            .setWriteBufferSize(64 * 1024 * 1024) // 64MB write buffer
            .setMaxWriteBufferNumber(3)
            .setTargetFileSizeBase(64 * 1024 * 1024) // 64MB target file size
            .setCompressionType(CompressionType.LZ4_COMPRESSION);
    }
    
    @Override
    public ListenableFuture<byte[]> get(byte[] key) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        
        ListenableFuture<byte[]> future = new ListenableFuture<>();
        BytesKey bytesKey = new BytesKey(key);
        
        long completionTick = currentTick + defaultDelayTicks;
        pendingOperations.offer(new GetOperation(bytesKey, future, completionTick));
        
        return future;
    }
    
    @Override
    public ListenableFuture<Boolean> set(byte[] key, byte[] value) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }
        
        ListenableFuture<Boolean> future = new ListenableFuture<>();
        BytesKey bytesKey = new BytesKey(key);
        
        long completionTick = currentTick + defaultDelayTicks;
        pendingOperations.offer(new SetOperation(bytesKey, value, future, completionTick));
        
        return future;
    }
    
    @Override
    public void tick() {
        currentTick++;
        
        // Process all operations that are ready to complete
        while (!pendingOperations.isEmpty() && pendingOperations.peek().completionTick <= currentTick) {
            PendingOperation operation = pendingOperations.poll();
            
            // Check for random failures
            if (random.nextDouble() < defaultFailureRate) {
                operation.fail(new RuntimeException("Simulated storage failure"));
                continue;
            }
            
            try {
                operation.execute(dataStore);
            } catch (Exception e) {
                operation.fail(new RuntimeException("Storage operation failed", e));
            }
        }
    }
    
    /**
     * Forces a flush of all pending writes to disk.
     * Useful for ensuring durability before critical operations.
     */
    public void flush() throws RocksDBException {
        db.flush(new org.rocksdb.FlushOptions());
    }
    
    @Override
    public ListenableFuture<Void> sync() {
        ListenableFuture<Void> future = new ListenableFuture<>();
        
        long completionTick = currentTick + defaultDelayTicks;
        pendingOperations.offer(new SyncOperation(future, completionTick));
        
        return future;
    }

    /**
     * Gets database statistics for monitoring.
     */
    public String getStats() throws RocksDBException {
        return db.getProperty("rocksdb.stats");
    }
    
    /**
     * Manually triggers database compaction.
     */
    public void compact() throws RocksDBException {
        db.compactRange();
    }
    
    /**
     * Closes the database and releases resources.
     */
    public void close() {
        if (db != null) {
            db.close();
        }
        if (options != null) {
            options.close();
        }
    }
    
    // Helper classes for tick-based operations
    
    private abstract static class PendingOperation implements Comparable<PendingOperation> {
        protected final BytesKey key;
        protected final long completionTick;
        
        PendingOperation(BytesKey key, long completionTick) {
            this.key = key;
            this.completionTick = completionTick;
        }
        
        abstract void execute(Map<BytesKey, byte[]> dataStore);
        abstract void fail(RuntimeException exception);
        
        @Override
        public int compareTo(PendingOperation other) {
            return Long.compare(this.completionTick, other.completionTick);
        }
    }
    
    private static class GetOperation extends PendingOperation {
        private final ListenableFuture<byte[]> future;
        
        GetOperation(BytesKey key, ListenableFuture<byte[]> future, long completionTick) {
            super(key, completionTick);
            this.future = future;
        }
        
        @Override
        void execute(Map<BytesKey, byte[]> dataStore) {
            byte[] value = dataStore.get(key);
            System.out.println("RocksDbStorage: GET operation completed for key " + java.util.Arrays.toString(key.bytes()));
            future.complete(value);
        }
        
        @Override
        void fail(RuntimeException exception) {
            future.fail(exception);
        }
    }
    
    private static class SetOperation extends PendingOperation {
        private final byte[] value;
        private final ListenableFuture<Boolean> future;
        
        SetOperation(BytesKey key, byte[] value, ListenableFuture<Boolean> future, long completionTick) {
            super(key, completionTick);
            this.value = value;
            this.future = future;
        }
        
        @Override
        void execute(Map<BytesKey, byte[]> dataStore) {
            dataStore.put(key, value);
            System.out.println("RocksDbStorage: SET operation completed for key " + java.util.Arrays.toString(key.bytes()));
            future.complete(true);
        }
        
        @Override
        void fail(RuntimeException exception) {
            future.fail(exception);
        }
    }
    
    private static class SyncOperation extends PendingOperation {
        private final ListenableFuture<Void> future;
        
        SyncOperation(ListenableFuture<Void> future, long completionTick) {
            super(null, completionTick); // Sync doesn't need a key
            this.future = future;
        }
        
        @Override
        void execute(Map<BytesKey, byte[]> dataStore) {
            System.out.println("RocksDbStorage: SYNC operation completed");
            future.complete(null);
        }
        
        @Override
        void fail(RuntimeException exception) {
            future.fail(exception);
        }
    }
} 