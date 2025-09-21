package com.tickloom.algorithms.replication.quorum;

import java.util.Arrays;
import java.util.Objects;

/**
 * Client request to get a name for a specific key.
 */
public record GetRequest(byte[] key) {
    public GetRequest {
        Objects.requireNonNull(key, "Key cannot be null");
        if (key.length == 0) {
            throw new IllegalArgumentException("Key cannot be empty");
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GetRequest that = (GetRequest) obj;
        return Arrays.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(key);
    }

    @Override
    public String toString() {
        return "GetRequest{keyLength=" + key.length + "}";
    }
}
