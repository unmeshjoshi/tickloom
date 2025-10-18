package com.tickloom.algorithms.replication.quorum;

import java.util.Arrays;
import java.util.Objects;

/**
 * Internal response sent between replicas for GET requests.
 */
public record InternalGetResponse(byte[] key, byte[] value) {
    public InternalGetResponse {
        Objects.requireNonNull(key, "Key cannot be null");
        // name can be null when not found
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        InternalGetResponse that = (InternalGetResponse) obj;
        return Arrays.equals(key, that.key) &&
               Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(key), value);
    }

    @Override
    public String toString() {
        return "InternalGetResponse{keyLength=" + key.length + ", correlationId='"  +
               "', hasValue=" + (value != null) + "}";
    }
}
