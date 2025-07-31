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
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Production-ready RocksDB-based storage implementation.
 * Provides persistent key-value storage while maintaining the async ListenableFuture interface
 * and deterministic tick() behavior required by the simulation framework.
 * 
 * Key features:
 * - Persistent storage using RocksDB
 * - Async operations using background thread pool
 * - Maintains deterministic tick() behavior
 * - Thread-safe concurrent operations
 * - Configurable RocksDB options for performance tuning
 */
public class RocksDbStorage implements Storage {
    
    private RocksDB db;
    private final Options options;
    private final Executor executor;
    private final Path dbPath;
    
    // Queue of completed operations to process in tick()
    private final Queue<CompletedOperation> completedOperations = new ConcurrentLinkedQueue<>();
    
    static {
        // Load RocksDB native library
        RocksDB.loadLibrary();
    }
    
    /**
     * Creates RocksDB storage with default configuration.
     * @param dbPath directory path for the database files
     */
    public RocksDbStorage(String dbPath) {
        this(dbPath, createDefaultOptions(), Executors.newFixedThreadPool(4));
    }
    
    /**
     * Creates RocksDB storage with custom configuration.
     * @param dbPath directory path for the database files
     * @param options RocksDB configuration options
     * @param executor thread pool for async operations
     */
    public RocksDbStorage(String dbPath, Options options, Executor executor) {
        this.dbPath = Paths.get(dbPath);
        this.options = options;
        this.executor = executor;
        
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
    public ListenableFuture<VersionedValue> get(byte[] key) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        
        ListenableFuture<VersionedValue> future = new ListenableFuture<>();
        
        // Execute get operation asynchronously
        executor.execute(() -> {
            try {
                byte[] valueBytes = db.get(key);
                VersionedValue value = null;
                
                if (valueBytes != null) {
                    value = deserializeVersionedValue(valueBytes);
                }
                
                // Queue completion for deterministic processing in tick()
                completedOperations.offer(new CompletedGetOperation(future, value));
                
            } catch (Exception e) {
                completedOperations.offer(new CompletedGetOperation(future, e));
            }
        });
        
        return future;
    }
    
    @Override
    public ListenableFuture<Boolean> set(byte[] key, VersionedValue value) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }
        
        ListenableFuture<Boolean> future = new ListenableFuture<>();
        
        // Execute set operation asynchronously
        executor.execute(() -> {
            try {
                byte[] serializedValue = serializeVersionedValue(value);
                db.put(key, serializedValue);
                
                // Queue completion for deterministic processing in tick()
                completedOperations.offer(new CompletedSetOperation(future, true));
                
            } catch (Exception e) {
                completedOperations.offer(new CompletedSetOperation(future, e));
            }
        });
        
        return future;
    }
    
    @Override
    public void tick() {
        // Process all completed operations deterministically
        CompletedOperation operation;
        while ((operation = completedOperations.poll()) != null) {
            operation.complete();
        }
    }
    
    /**
     * Serializes a VersionedValue to bytes for storage.
     * Format: [8 bytes timestamp][remaining bytes: value]
     */
    private byte[] serializeVersionedValue(VersionedValue versionedValue) {
        byte[] value = versionedValue.value();
        long timestamp = versionedValue.timestamp();
        
        byte[] serialized = new byte[8 + value.length];
        
        // Write timestamp as 8 bytes (big-endian)
        for (int i = 0; i < 8; i++) {
            serialized[i] = (byte) (timestamp >>> (56 - i * 8));
        }
        
        // Write value bytes
        System.arraycopy(value, 0, serialized, 8, value.length);
        
        return serialized;
    }
    
    /**
     * Deserializes bytes back to a VersionedValue.
     */
    private VersionedValue deserializeVersionedValue(byte[] data) {
        if (data.length < 8) {
            throw new IllegalArgumentException("Invalid serialized data: too short");
        }
        
        // Read timestamp from first 8 bytes (big-endian)
        long timestamp = 0;
        for (int i = 0; i < 8; i++) {
            timestamp = (timestamp << 8) | (data[i] & 0xFF);
        }
        
        // Read value from remaining bytes
        byte[] value = new byte[data.length - 8];
        System.arraycopy(data, 8, value, 0, value.length);
        
        return new VersionedValue(value, timestamp);
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
    
    // Helper classes for deterministic operation completion
    
    private abstract static class CompletedOperation {
        abstract void complete();
    }
    
    private static class CompletedGetOperation extends CompletedOperation {
        private final ListenableFuture<VersionedValue> future;
        private final VersionedValue result;
        private final Exception error;
        
        CompletedGetOperation(ListenableFuture<VersionedValue> future, VersionedValue result) {
            this.future = future;
            this.result = result;
            this.error = null;
        }
        
        CompletedGetOperation(ListenableFuture<VersionedValue> future, Exception error) {
            this.future = future;
            this.result = null;
            this.error = error;
        }
        
        @Override
        void complete() {
            if (error != null) {
                future.fail(error);
            } else {
                future.complete(result);
            }
        }
    }
    
    private static class CompletedSetOperation extends CompletedOperation {
        private final ListenableFuture<Boolean> future;
        private final Boolean result;
        private final Exception error;
        
        CompletedSetOperation(ListenableFuture<Boolean> future, Boolean result) {
            this.future = future;
            this.result = result;
            this.error = null;
        }
        
        CompletedSetOperation(ListenableFuture<Boolean> future, Exception error) {
            this.future = future;
            this.result = null;
            this.error = error;
        }
        
        @Override
        void complete() {
            if (error != null) {
                future.fail(error);
            } else {
                future.complete(result);
            }
        }
    }
} 