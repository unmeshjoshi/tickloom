package com.tickloom.algorithms.replication.quorum;

import java.util.Arrays;
import java.util.Objects;

/**
 * Client request to set a value for a specific key.
 */
public record SetRequest(byte[] key, byte[] value) {
    public SetRequest {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(value, "Value cannot be null");
        if (key.length == 0) {
            throw new IllegalArgumentException("Key cannot be empty");
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        SetRequest that = (SetRequest) obj;
        return Arrays.equals(key, that.key) && Arrays.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(key), Arrays.hashCode(value));
    }

    @Override
    public String toString() {
        return "SetRequest{keyLength=" + key.length + ", valueLength=" + value.length + "}";
    }
}
