package com.tickloom.algorithms.replication.quorum;

import java.util.Arrays;
import java.util.Objects;

/**
 * Response to a clientId GET request.
 */
public record GetResponse(byte[] key, byte[] value, boolean found) {
    public GetResponse {
        Objects.requireNonNull(key, "Key cannot be null");
        // name can be null when not found
        if (found && value == null) {
            throw new IllegalArgumentException("Value cannot be null when found is true");
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GetResponse that = (GetResponse) obj;
        return found == that.found &&
               Arrays.equals(key, that.key) &&
               Arrays.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(key), Arrays.hashCode(value), found);
    }

    @Override
    public String toString() {
        return "GetResponse{keyLength=" + key.length + ", found=" + found + 
               ", valueLength=" + (value != null ? value.length : 0) + "}";
    }
}
