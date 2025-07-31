package com.tickloom.storage;

import java.util.Arrays;

/**
 * A record that wraps a byte array for use as a Map key.
 * This is necessary because byte[] arrays use identity-based equality,
 * but we need content-based equality for proper Map key behavior.
 * Makes a defensive copy to prevent external modifications.
 */
public record BytesKey(byte[] bytes) {
    
    public BytesKey {
        if (bytes == null) {
            throw new IllegalArgumentException("Bytes cannot be null");
        }
        // Make a defensive copy to prevent external modifications
        bytes = bytes.clone();
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        BytesKey other = (BytesKey) obj;
        return Arrays.equals(bytes, other.bytes);
    }
    
    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
    
    @Override
    public String toString() {
        return "BytesKey{" + Arrays.toString(bytes) + "}";
    }
} 