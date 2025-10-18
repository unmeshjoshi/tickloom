package com.tickloom.storage;

import java.util.*;

/**
 * Represents a batch of key-value pairs to be written atomically.
 * This class encapsulates a collection of key-value pairs that will be
 * written together in a single atomic operation.
 * 
 * WriteBatch provides a clean API for building batches of writes
 * and ensures type safety for storage operations.
 */
public class WriteBatch {
    private final Map<byte[], byte[]> operations;
    
    /**
     * Creates an empty WriteBatch.
     */
    public WriteBatch() {
        this.operations = new HashMap<>();
    }
    
    /**
     * Creates a WriteBatch with the given key-value pairs.
     * 
     * @param operations the initial key-value pairs
     */
    public WriteBatch(Map<byte[], byte[]> operations) {
        this.operations = new HashMap<>(operations);
    }

    /**
     * Adds a key-value pair to the batch.
     * 
     * @param key the key to write
     * @param value the value to write
     * @return this WriteBatch for method chaining
     */
    public WriteBatch put(byte[] key, byte[] value) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }
        operations.put(key, value);
        return this;
    }
    
    /**
     * Adds a key-value pair to the batch using String keys and values.
     * Convenience method for common use cases.
     * 
     * @param key the key to write (will be converted to bytes)
     * @param value the value to write (will be converted to bytes)
     * @return this WriteBatch for method chaining
     */
    public WriteBatch put(String key, String value) {
        return put(key.getBytes(), value.getBytes());
    }
    
    /**
     * Removes a key from the batch (marks it for deletion).
     * 
     * @param key the key to remove
     * @return this WriteBatch for method chaining
     */
    public WriteBatch delete(byte[] key) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        operations.put(key, null); // Use null value to indicate deletion
        return this;
    }
    
    /**
     * Removes a key from the batch using a String key.
     * Convenience method for common use cases.
     * 
     * @param key the key to remove (will be converted to bytes)
     * @return this WriteBatch for method chaining
     */
    public WriteBatch delete(String key) {
        return delete(key.getBytes());
    }
    
    /**
     * Gets the number of operations in this batch.
     * 
     * @return the number of operations
     */
    public int size() {
        return operations.size();
    }
    
    /**
     * Checks if this batch is empty.
     * 
     * @return true if the batch is empty, false otherwise
     */
    public boolean isEmpty() {
        return operations.isEmpty();
    }
    
    /**
     * Clears all operations from this batch.
     * 
     * @return this WriteBatch for method chaining
     */
    public WriteBatch clear() {
        operations.clear();
        return this;
    }
    
    /**
     * Gets the internal operations map.
     * This method is package-private and should only be used by storage implementations.
     * 
     * @return the internal operations map
     */
    public Map<byte[], byte[]> getOperations() {
        return operations;
    }
    
    /**
     * Creates a copy of this WriteBatch.
     * 
     * @return a new WriteBatch with the same operations
     */
    public WriteBatch copy() {
        return new WriteBatch(operations);
    }
    
    @Override
    public String toString() {
        return "WriteBatch{size=" + operations.size() + "}";
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        WriteBatch that = (WriteBatch) obj;
        return Objects.equals(operations, that.operations);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(operations);
    }
}
