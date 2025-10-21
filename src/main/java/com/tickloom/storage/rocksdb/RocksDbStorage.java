package com.tickloom.storage.rocksdb;

import com.tickloom.storage.Storage;
import com.tickloom.storage.WriteBatch;
import com.tickloom.storage.rocksdb.ops.*;
import org.rocksdb.CompressionType;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import com.tickloom.future.ListenableFuture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * RocksDB-based storage implementation.
 * Provides persistent key-value storage while maintaining the async ListenableFuture interface
 * and deterministic tick() behavior required by the simulation framework.
 * - Support for batch operations, range queries, and prefix operations, typically used for WAL storage.
 * We can safely create multiple instances of this class which are backed by a separate RockDB instance.
 * Each instance should have its own unique database path.
 *
 *
 */
public class RocksDbStorage implements Storage {

    public RocksDB db;
    private final Options options;
    private final Path dbPath;
    private final Random random;
    private final int defaultDelayTicks;
    private final double defaultFailureRate;

    // Internal state for tick-based operations
    private final PriorityQueue<PendingOperation> pendingOperations = new PriorityQueue<>();

    // Internal counter for operation timing
    private long currentTick = 0;

    static {
        RocksDB.loadLibrary();
    }

    /**
     * Creates RocksDB storage with default configuration.
     *
     * @param dbPath directory path for the database files
     */
    public RocksDbStorage(String dbPath) {
        this(dbPath, createDefaultOptions(), new Random(), 0, 0.0);
    }

    /**
     * Creates RocksDB storage with custom configuration.
     *
     * @param dbPath      directory path for the database files
     * @param options     RocksDB configuration options
     * @param random      seeded random generator for deterministic behavior
     * @param delayTicks  number of ticks to delay operations (0 = immediate)
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

    public static Options createDefaultOptions() {
        return new Options()
                .setCreateIfMissing(true)
                .setWriteBufferSize(64 * 1024 * 1024) // 64MB write buffer
                .setMaxWriteBufferNumber(3)
                .setTargetFileSizeBase(64 * 1024 * 1024) // 64MB target file size
                .setCompressionType(CompressionType.LZ4_COMPRESSION);
    }

    // ========== Basic Operations ==========

    @Override
    public ListenableFuture<byte[]> get(byte[] key) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }

        return submit((future, completionTick) -> new GetOperation(this, key, future, completionTick));
    }

    static interface OperationFactory<T> {
        PendingOperation create(ListenableFuture<T> future, long completionTick);
    }

    private <T> ListenableFuture<T> submit(OperationFactory<T> factory) {
        ListenableFuture<T> future = new ListenableFuture<>();

        long completionTick = currentTick + defaultDelayTicks;
        pendingOperations.offer(factory.create(future, completionTick));
        return future;
    }

    @Override
    public ListenableFuture<Boolean> put(byte[] key, byte[] value) {
        return put(key, value, WriteOptions.DEFAULT);
    }

    @Override
    public ListenableFuture<Boolean> put(byte[] key, byte[] value, WriteOptions options) {
        return submit((future, completionTick) -> new SetOperation(this, key, value, future, completionTick, options));
    }

    @Override
    public ListenableFuture<Boolean> put(WriteBatch writeBatch) {
        return put(writeBatch, WriteOptions.DEFAULT);
    }

    @Override
    public ListenableFuture<Boolean> put(WriteBatch writeBatch, WriteOptions options) {
        return submit((future, completionTick)
                -> new BatchWriteOperation(this, writeBatch, future, completionTick, options));
    }

    @Override
    public ListenableFuture<Map<byte[], byte[]>> readRange(byte[] startKey, byte[] endKey) {
        return submit((future, completionTick) -> new ReadRangeOperation(this, startKey, endKey, future, completionTick));
    }

    /**
     * Returns the highest key which is strictly lower than the given upperBoundKey key.
     * This is useful when the storage is used to store WAL, and at startup needs the
     * last index. The WAL implementation can find the highestKey < [WAL_PREFIX]Long.MAX_VALUE
     * to initialize the WAL index, which then can be incremented for each append operation.
     * @param upperBoundKey
     * @return
     */
    @Override
    public ListenableFuture<byte[]> lowerKey(byte[] upperBoundKey) {
        return submit((future, completionTick) -> new LastKeyOperation(this, future, completionTick, upperBoundKey));
    }

    @Override
    public ListenableFuture<Void> sync() {
        return submit((future, completionTick) -> new SyncOperation(this, future, completionTick));
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
                operation.execute();
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

    // ========== Internal Operation Classes ==========

}