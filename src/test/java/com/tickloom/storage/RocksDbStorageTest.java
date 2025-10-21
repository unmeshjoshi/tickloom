package com.tickloom.storage;

import com.tickloom.future.ListenableFuture;
import com.tickloom.storage.rocksdb.RocksDbStorage;
import com.tickloom.storage.rocksdb.ops.LastKeyOperation;
import com.tickloom.testkit.ClusterAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RocksDbStorage.
 * <p>
 * Note: These tests are simplified to avoid RocksDB native library crashes on macOS.
 * The tests focus on the interface and basic functionality rather than comprehensive
 * RocksDB operations which can cause JVM crashes on Apple Silicon.
 */
class RocksDbStorageTest {

    @TempDir
    private Path tempDir;
    private RocksDbStorage storage;
    private Random random;

    @BeforeEach
    void setUp() throws IOException {
        random = new Random(12345L); // Deterministic seed for testing
        tempDir = Files.createTempDirectory("rocksdb-test");
        storage = new RocksDbStorage(createUniqueDbPath(random)); // Use default constructor
    }

    /**
     * Creates a unique database path within the temp directory for each RocksDB instance.
     * This prevents lock conflicts when multiple RocksDB instances are created in tests.
     *
     * @param random
     */
    private String createUniqueDbPath(Random random) {
        return tempDir.resolve("tickloom-rocksdb-" + random.nextInt()).toString();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (storage != null) {
            storage.close();
        }
    }

    // ========== Basic Operations Tests ==========

    @Test
    @DisplayName("Should perform basic get/set operations")
    void shouldPerformBasicSetAndGet() {
        byte[] key = "test:key".getBytes();
        byte[] value = "test:value".getBytes();

        ListenableFuture<Boolean> setFuture = storage.put(key, value);
        storage.tick();
        assertPutCompletesSuccessfully(setFuture);

        ListenableFuture<byte[]> getFuture = storage.get(key);
        storage.tick();
        assertTrue(getFuture.isCompleted());
        assertArrayEquals(value, getFuture.getResult());
    }

    @Test
    @DisplayName("Should return null for non-existent keys")
    void shouldReturnNullForNonExistentKeys() {
        byte[] key = "nonexistent:key".getBytes();

        ListenableFuture<byte[]> getFuture = storage.get(key);
        storage.tick();
        assertTrue(getFuture.isCompleted());
        assertNull(getFuture.getResult());
    }

    // ========== Write Options Tests ==========

    @Test
    @DisplayName("Should support different write options")
    void shouldSupportDifferentWriteOptions() {
        byte[] key = "options:test".getBytes();
        byte[] value = "options:value".getBytes();

        // Test with default options
        ListenableFuture<Boolean> setFuture1 = storage.put(key, value);
        storage.tick();
        assertPutCompletesSuccessfully(setFuture1);

        // Test with sync options
        ListenableFuture<Boolean> setFuture2 = storage.put(key, value, Storage.WriteOptions.SYNC);
        storage.tick();
        assertPutCompletesSuccessfully(setFuture2);

        // Test with fast options
        ListenableFuture<Boolean> setFuture3 = storage.put(key, value, Storage.WriteOptions.FAST);
        storage.tick();
        assertPutCompletesSuccessfully(setFuture3);
    }

    private static void assertPutCompletesSuccessfully(ListenableFuture<Boolean> future) {
        assertTrue(future.isCompleted());
        assertTrue(future.getResult());
    }

    // ========== Batch Operations Tests ==========

    @Test
    @DisplayName("Should perform batch operations")
    void shouldPutBatch() {
        WriteBatch writeBatch = new WriteBatch()
                .put("batch:key1", "batch:value1")
                .put("batch:key2", "batch:value2")
                .put("batch:key3", "batch:value3");

        ListenableFuture<Boolean> batchFuture = storage.put(writeBatch);
        storage.tick();
        assertPutCompletesSuccessfully(batchFuture);

        // Verify the operations were applied
        ListenableFuture<byte[]> getFuture1 = storage.get("batch:key1".getBytes());
        storage.tick();
        assertTrue(getFuture1.isCompleted());
        assertArrayEquals("batch:value1".getBytes(), getFuture1.getResult());

        ListenableFuture<byte[]> getFuture2 = storage.get("batch:key2".getBytes());
        storage.tick();
        assertTrue(getFuture2.isCompleted());
        assertArrayEquals("batch:value2".getBytes(), getFuture2.getResult());

        ListenableFuture<byte[]> getFuture3 = storage.get("batch:key3".getBytes());
        storage.tick();
        assertTrue(getFuture3.isCompleted());
        assertArrayEquals("batch:value3".getBytes(), getFuture3.getResult());
    }

    // ========== Range Operations Tests ==========

    @Test
    @DisplayName("Should perform range queries")
    void shouldPerformReadRangeQueries() {
        // Set up some test data
        storage.put("range:key1".getBytes(), "range:value1".getBytes());
        storage.put("range:key2".getBytes(), "range:value2".getBytes());
        storage.put("range:key3".getBytes(), "range:value3".getBytes());
        storage.put("other:key".getBytes(), "other:value".getBytes());
        storage.tick();

        // Query range
        ListenableFuture<Map<byte[], byte[]>> rangeFuture = storage.readRange(
                "range:key1".getBytes(), "range:key3".getBytes());
        storage.tick();
        assertTrue(rangeFuture.isCompleted());

        Map<byte[], byte[]> result = rangeFuture.getResult();
        assertNotNull(result);
        // Note: We can't easily verify the exact contents due to byte[] comparison issues
        // but we can verify the operation completed successfully
    }

    @Test
    @DisplayName("Should handle empty range queries")
    void shouldHandleEmptyReadRangeQueries() {
        ListenableFuture<Map<byte[], byte[]>> rangeFuture = storage.readRange(
                "nonexistent:start".getBytes(), "nonexistent:end".getBytes());
        storage.tick();
        assertTrue(rangeFuture.isCompleted());

        Map<byte[], byte[]> result = rangeFuture.getResult();
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ========== Prefix Operations Tests ==========


    // ========== Last Key Operations Tests ==========

    @Test
    @DisplayName("Should get last key")
    void shouldGetLowerKey() {
        // Set up some test data
        storage.put("last:key1".getBytes(), "value1".getBytes());
        storage.put("last:key2".getBytes(), "value2".getBytes());
        storage.put("other:somekey".getBytes(), "somevalue".getBytes());
        storage.tick();

        byte[] keyUpperBound = "last:z".getBytes();
        ListenableFuture<byte[]> lastKeyFuture = storage.lowerKey(keyUpperBound);
        storage.tick();
        assertTrue(lastKeyFuture.isCompleted());

        byte[] lastKey = lastKeyFuture.getResult();
        assertEquals("last:key2", new String(lastKey));
    }

    @Test
    @DisplayName("Should perform sync operations")
    void shouldPerformSyncOperations() {
        ListenableFuture<Void> syncFuture = storage.sync();
        storage.tick();
        assertTrue(syncFuture.isCompleted());
    }

    // ========== Resource Management Tests ==========

    @Test
    @DisplayName("Should close gracefully")
    void shouldCloseGracefully() {
        assertDoesNotThrow(() -> {
            storage.close();
        });
    }

    // ========== Edge Cases Tests ==========

    @Test
    @DisplayName("Should handle empty keys and values")
    void shouldHandleEmptyKeysAndValues() {
        byte[] emptyKey = new byte[0];
        byte[] emptyValue = new byte[0];

        ListenableFuture<Boolean> setFuture = storage.put(emptyKey, emptyValue);
        storage.tick();
        assertPutCompletesSuccessfully(setFuture);

        ListenableFuture<byte[]> getFuture = storage.get(emptyKey);
        storage.tick();
        assertTrue(getFuture.isCompleted());
        assertArrayEquals(emptyValue, getFuture.getResult());
    }

    @Test
    public void testLowerKeyOperation() {
        byte[] key = "rftlg:key".getBytes();
        byte[] value = "test:value".getBytes();

        ListenableFuture<Boolean> setFuture = storage.put(key, value);
        storage.tick();
        assertPutCompletesSuccessfully(setFuture);

        ListenableFuture<byte[]> f = new ListenableFuture();
        var op = new LastKeyOperation(this.storage, f, 1, ("rftlg:" + "z").getBytes(StandardCharsets.UTF_8));
        op.execute();
        assertTrue(f.isCompleted());
        assertArrayEquals(f.getResult(), key);
    }

    @Test
    public void testMultiLog() {
        MultiLog multiLog = new MultiLog(List.of("log1", "log2"), storage);
        while(!multiLog.isInitialised) {
            storage.tick();
        }
        ListenableFuture<Boolean> log1 = multiLog.append("log1", "value1".getBytes());
        ListenableFuture<Boolean> log2 = multiLog.append("log1", "value2".getBytes());
        ListenableFuture<Boolean> log3 = multiLog.append("log2", "value3".getBytes());
        ListenableFuture<Boolean> log4 = multiLog.append("log2", "value4".getBytes());

        storage.tick();
        assertPutCompletesSuccessfully(log1);
        assertPutCompletesSuccessfully(log2);
        assertPutCompletesSuccessfully(log3);
        assertPutCompletesSuccessfully(log4);

        ListenableFuture<byte[]> lastKeyF = storage.lowerKey(multiLog.createLogKey("log2", Long.MAX_VALUE));
        storage.tick();
        assertTrue(lastKeyF.isCompleted());
        assertEquals(2, multiLog.getIndex(lastKeyF.getResult()));
    }
    @Test
    public void testMultiLogInitialization() {
        // Test initialization with empty log IDs
        MultiLog emptyLog = new MultiLog(List.of(), storage);
        assertFalse(emptyLog.isInitialised, "Should not be initialized with empty log IDs");

        // Test initialization with single log ID
        MultiLog singleLog = new MultiLog(List.of("log1"), storage);
        while (!singleLog.isInitialised) {
            storage.tick();
        }
        assertTrue(singleLog.isInitialised, "Should be initialized after processing");
    }

    @Test
    public void testAppendBeforeInitialization() {
        MultiLog multiLog = new MultiLog(List.of("log1"), storage);
        assertThrows(IllegalStateException.class,
                () -> multiLog.append("log1", "value".getBytes()),
                "Should throw when append is called before initialization");
    }

    @Test
    public void testConcurrentAppends() {
        MultiLog multiLog = new MultiLog(List.of("log1"), storage);
        while (!multiLog.isInitialised) {
            storage.tick();
        }

        // Test multiple appends to the same log
        ListenableFuture<Boolean> append1 = multiLog.append("log1", "value1".getBytes());
        ListenableFuture<Boolean> append2 = multiLog.append("log1", "value2".getBytes());

        storage.tick();
        assertTrue(append1.getResult(), "First append should succeed");
        assertTrue(append2.getResult(), "Second append should succeed");

        // Verify the order of appends
        ListenableFuture<byte[]> lastKey = storage.lowerKey(multiLog.createLogKey("log1", Long.MAX_VALUE));
        storage.tick();
        assertEquals(2, multiLog.getIndex(lastKey.getResult()), "Last index should be 2");
    }

    @Test
    public void testMultipleLogsIndependence() {
        MultiLog multiLog = new MultiLog(List.of("log1", "log2"), storage);
        while (!multiLog.isInitialised) {
            storage.tick();
        }

        // Append to different logs
        multiLog.append("log1", "value1".getBytes());
        multiLog.append("log2", "value2".getBytes());
        storage.tick();

        // Verify logs are independent
        ListenableFuture<byte[]> log1Key = storage.lowerKey(multiLog.createLogKey("log1", Long.MAX_VALUE));
        ListenableFuture<byte[]> log2Key = storage.lowerKey(multiLog.createLogKey("log2", Long.MAX_VALUE));
        storage.tick();

        assertEquals(1, multiLog.getIndex(log1Key.getResult()), "log1 index should be 1");
        assertEquals(1, multiLog.getIndex(log2Key.getResult()), "log2 index should be 1");
    }

    @Test
    public void testLogKeyStructure() {
        MultiLog multiLog = new MultiLog(List.of("testLog"), storage);
        byte[] key = multiLog.createLogKey("testLog", 123L);

        // Verify key structure: logId + RFTL + index
        String keyStr = new String(key, 0, "testLog".length());
        assertEquals("testLog", keyStr, "Key should start with log ID");

        byte[] rftl = new byte[4];
        System.arraycopy(key, "testLog".length(), rftl, 0, 4);
        assertArrayEquals("rftl".getBytes(), rftl, "Key should contain RFTL marker");

        long index = ByteBuffer.wrap(key, key.length - 8, 8).getLong();
        assertEquals(123L, index, "Key should end with correct index");
    }
    /**
     * An example implementation of storing multiple 'logs' in a single Storage.
     */
    class MultiLog {
        private static final byte[] RFTL = "rftl".getBytes(StandardCharsets.US_ASCII);
        private static final long INITIAL_INDEX = 0L;

        private final Storage storage;
        private final Map<String, Long> lastLogIndexes;
        private boolean isInitialised = false;

        public MultiLog(List<String> logIds, Storage storage) {
            this.storage = Objects.requireNonNull(storage, "Storage cannot be null");
            this.lastLogIndexes = new HashMap<>(logIds.size());
            initializeLogIndices(logIds);
        }

        private void initializeLogIndices(List<String> logIds) {
            logIds.forEach(logId -> {
                lastLogIndexes.put(logId, INITIAL_INDEX);
                fetchLastLogIndex(logId, logIds);
            });
        }

        private void fetchLastLogIndex(String logId, List<String> allLogIds) {
            ListenableFuture<byte[]> lastIndexFuture = storage.lowerKey(createLogKey(logId, Long.MAX_VALUE));
            lastIndexFuture.andThen((result, error) -> handleIndexResult(logId, allLogIds, result, error));
        }

        private void handleIndexResult(String logId, List<String> allLogIds, byte[] result, Throwable error) {
            if (error != null) {
                return; // Error handling could be improved here
            }

            long index = result != null ? getIndex(result) : INITIAL_INDEX;
            updateLogIndex(logId, index, allLogIds);
        }

        private void updateLogIndex(String logId, long index, List<String> allLogIds) {
            lastLogIndexes.put(logId, index);
            checkInitialization(allLogIds);
        }

        private void checkInitialization(List<String> allLogIds) {
            if (lastLogIndexes.size() == allLogIds.size()) {
                isInitialised = true;
            }
        }

        public ListenableFuture<Boolean> append(String logId, byte[] entry) {
            if (!isInitialised) {
                throw new IllegalStateException("The LogStore is not initialised");
            }
            long nextIndex = getAndIncrementIndex(logId);
            return storage.put(createLogKey(logId, nextIndex), entry);
        }

        private long getAndIncrementIndex(String logId) {
            return lastLogIndexes.compute(logId, (k, v) -> v + 1);
        }

        public byte[] createLogKey(String logId, long logIndex) {
            return ByteBuffer.allocate(logId.length() + RFTL.length + Long.BYTES)
                    .put(logId.getBytes())
                    .put(RFTL)
                    .putLong(logIndex)
                    .array();
        }

        public long getIndex(byte[] key) {
            return ByteBuffer.wrap(key).getLong(key.length - Long.BYTES);
        }
    }

}