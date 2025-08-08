package com.tickloom.algorithms.replication.quorum;

import java.util.Arrays;
import java.util.Objects;

/**
 * Internal request sent between replicas to get a value for a specific key.
 */
public record InternalGetRequest(byte[] key) {
    public InternalGetRequest {
        Objects.requireNonNull(key, "Key cannot be null");
        if (key.length == 0) {
            throw new IllegalArgumentException("Key cannot be empty");
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        InternalGetRequest that = (InternalGetRequest) obj;
        return Arrays.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(key));
    }

    @Override
    public String toString() {
        return "InternalGetRequest{keyLength=" + key.length + "'}";
    }
}
