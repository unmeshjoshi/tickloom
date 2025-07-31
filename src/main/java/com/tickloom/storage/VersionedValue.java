package com.tickloom.storage;

import java.util.Arrays;

/**
 * Represents a value with its associated timestamp version.
 * The timestamp represents the system time when this value was created or last modified.
 */
public record VersionedValue(byte[] value, long timestamp) {
    
    public VersionedValue {
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }
        // Note: We allow negative timestamps for simulation scenarios where 
        // relative time might be used
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        VersionedValue that = (VersionedValue) obj;
        return timestamp == that.timestamp && 
               Arrays.equals(value, that.value);
    }
    
    @Override
    public int hashCode() {
        return Arrays.hashCode(value) * 31 + Long.hashCode(timestamp);
    }
    
    @Override
    public String toString() {
        return "VersionedValue{" +
                "value.length=" + value.length +
                ", timestamp=" + timestamp +
                '}';
    }
} 