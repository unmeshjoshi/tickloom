package com.tickloom.storage;

import com.tickloom.future.ListenableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Example test demonstrating the new storage functionality.
 * This test shows how to use the enhanced Storage interface with
 * batch operations, range queries, and prefix operations.
 */
class StorageExampleTest {
    
    private SimulatedStorage storage;
    private Random random;
    
    @BeforeEach
    void setUp() {
        random = new Random(12345L); // Deterministic seed for testing
        storage = new SimulatedStorage(random, 0, 0.0); // No delays, no failures
    }
    
    @Test
    @DisplayName("Example: Basic storage operations")
    void exampleBasicOperations() {
        // Basic get/set operations
        byte[] key1 = "user:123".getBytes();
        byte[] value1 = "John Doe".getBytes();
        
        ListenableFuture<Boolean> setFuture = storage.put(key1, value1);
        ListenableFuture<byte[]> getFuture = storage.get(key1);
        
        // Process the operations
        storage.tick();
        
        // Verify results
        assertTrue(setFuture.isCompleted());
        assertTrue(getFuture.isCompleted());
        assertTrue(setFuture.getResult());
        assertArrayEquals(value1, getFuture.getResult());
    }
    
    @Test
    @DisplayName("Example: Batch operations")
    void examplePutOperations() {
        // Create batch write operations using WriteBatch
        WriteBatch writeBatch = new WriteBatch()
            .put("user:1", "Alice")
            .put("user:2", "Bob")
            .put("user:3", "Charlie");
        
        ListenableFuture<Boolean> batchFuture = storage.put(writeBatch);
        
        // Process the batch operation
        storage.tick();
        
        assertTrue(batchFuture.getResult());
        
        // Verify individual keys were set
        ListenableFuture<byte[]> getFuture1 = storage.get("user:1".getBytes());
        ListenableFuture<byte[]> getFuture2 = storage.get("user:2".getBytes());
        ListenableFuture<byte[]> getFuture3 = storage.get("user:3".getBytes());
        
        storage.tick();
        
        assertEquals("Alice", new String(getFuture1.getResult()));
        assertEquals("Bob", new String(getFuture2.getResult()));
        assertEquals("Charlie", new String(getFuture3.getResult()));
    }
    
    @Test
    @DisplayName("Example: Batch operations with deletes")
    void examplePutOperationsWithDeletes() {
        // First, set some initial data
        storage.put("temp:key1".getBytes(), "temp:value1".getBytes());
        storage.put("temp:key2".getBytes(), "temp:value2".getBytes());
        storage.tick();
        
        // Create batch with mixed operations
        WriteBatch writeBatch = new WriteBatch()
            .put("new:key1", "new:value1")
            .delete("temp:key1")  // Delete existing key
            .put("temp:key2", "updated:value2");  // Update existing key
        
        ListenableFuture<Boolean> batchFuture = storage.put(writeBatch);
        storage.tick();
        
        assertTrue(batchFuture.getResult());
        
        // Verify operations were applied
        ListenableFuture<byte[]> getNewKey = storage.get("new:key1".getBytes());
        ListenableFuture<byte[]> getDeletedKey = storage.get("temp:key1".getBytes());
        ListenableFuture<byte[]> getUpdatedKey = storage.get("temp:key2".getBytes());
        
        storage.tick();
        
        assertEquals("new:value1", new String(getNewKey.getResult()));
        assertNull(getDeletedKey.getResult()); // Should be deleted
        assertEquals("updated:value2", new String(getUpdatedKey.getResult()));
    }
    
    @Test
    @DisplayName("Example: Range queries")
    void exampleReadRangeQueries() {
        // Set up some data with ordered keys
        storage.put("user:001".getBytes(), "User 1".getBytes());
        storage.put("user:002".getBytes(), "User 2".getBytes());
        storage.put("user:003".getBytes(), "User 3".getBytes());
        storage.put("user:010".getBytes(), "User 10".getBytes());
        storage.tick();
        
        // Query range from user:001 to user:005 (exclusive)
        ListenableFuture<Map<byte[], byte[]>> rangeFuture = storage.readRange(
            "user:001".getBytes(), 
            "user:005".getBytes()
        );
        
        storage.tick();
        
        Map<byte[], byte[]> result = rangeFuture.getResult();
        assertEquals(3, result.size());
        
        // Check keys by content since byte[] doesn't implement equals/hashCode properly
        boolean found001 = false, found002 = false, found003 = false, found010 = false;
        for (byte[] key : result.keySet()) {
            String keyStr = new String(key);
            if (keyStr.equals("user:001")) found001 = true;
            if (keyStr.equals("user:002")) found002 = true;
            if (keyStr.equals("user:003")) found003 = true;
            if (keyStr.equals("user:010")) found010 = true;
        }
        
        assertTrue(found001, "Should contain user:001");
        assertTrue(found002, "Should contain user:002");
        assertTrue(found003, "Should contain user:003");
        assertFalse(found010, "Should not contain user:010");
    }
    
    @Test
    @DisplayName("Example: Prefix queries")
    void examplePrefixQueries() {
        // Set up data with different prefixes
        storage.put("user:alice".getBytes(), "Alice".getBytes());
        storage.put("user:bob".getBytes(), "Bob".getBytes());
        storage.put("order:123".getBytes(), "Order 123".getBytes());
        storage.put("user:charlie".getBytes(), "Charlie".getBytes());
        storage.tick();
        
        // Find all keys with "user:" prefix
        ListenableFuture<List<byte[]>> prefixFuture = storage.keysWithPrefix("user:".getBytes());
        
        storage.tick();
        
        List<byte[]> keys = prefixFuture.getResult();
        assertEquals(3, keys.size());
        
        // Verify all keys start with "user:"
        for (byte[] key : keys) {
            String keyStr = new String(key);
            assertTrue(keyStr.startsWith("user:"));
        }
    }
    
    @Test
    @DisplayName("Example: Write options")
    void exampleWriteOptions() {
        byte[] key = "critical:data".getBytes();
        byte[] value = "Important Data".getBytes();
        
        // Use SYNC write options for critical data
        ListenableFuture<Boolean> syncFuture = storage.put(key, value, Storage.WriteOptions.SYNC);
        
        // Use FAST write options for high-performance writes
        ListenableFuture<Boolean> fastFuture = storage.put("temp:data".getBytes(), "Temp Data".getBytes(),
                                                          Storage.WriteOptions.FAST);
        
        storage.tick();
        
        assertTrue(syncFuture.getResult());
        assertTrue(fastFuture.getResult());
    }
    
    @Test
    @DisplayName("Example: Last key operation")
    void exampleLastKeyOperation() {
        // Set up some ordered data
        storage.put("a".getBytes(), "First".getBytes());
        storage.put("z".getBytes(), "Last".getBytes());
        storage.put("m".getBytes(), "Middle".getBytes());
        storage.tick();
        
        // Get the last key (lexicographically)
        ListenableFuture<byte[]> lastKeyFuture = storage.lastKey();
        
        storage.tick();
        
        byte[] lastKey = lastKeyFuture.getResult();
        assertArrayEquals("z".getBytes(), lastKey);
    }
    
    @Test
    @DisplayName("Example: Versioned values with SimulatedStorage")
    void exampleVersionedValues() {
        byte[] key = "versioned:key".getBytes();
        byte[] value1 = "Version 1".getBytes();
        byte[] value2 = "Version 2".getBytes();
        
        // First write
        ListenableFuture<Boolean> set1 = storage.put(key, value1);
        storage.tick();
        assertTrue(set1.getResult());
        
        // Second write (should succeed due to newer timestamp)
        ListenableFuture<Boolean> set2 = storage.put(key, value2);
        storage.tick();
        assertTrue(set2.getResult());
        
        // Verify we get the latest value
        ListenableFuture<byte[]> getFuture = storage.get(key);
        storage.tick();
        assertArrayEquals(value2, getFuture.getResult());
    }
}
