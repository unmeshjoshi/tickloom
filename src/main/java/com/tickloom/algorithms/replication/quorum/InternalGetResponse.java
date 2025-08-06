package com.tickloom.algorithms.replication.quorum;

import com.tickloom.storage.VersionedValue;

import java.util.Arrays;
import java.util.Objects;

/**
 * Internal response sent between replicas for GET requests.
 */
public record InternalGetResponse(byte[] key, VersionedValue value, String correlationId) {
    public InternalGetResponse {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(correlationId, "Correlation ID cannot be null");
        // value can be null when not found
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        InternalGetResponse that = (InternalGetResponse) obj;
        return Arrays.equals(key, that.key) &&
               Objects.equals(value, that.value) &&
               Objects.equals(correlationId, that.correlationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(key), value, correlationId);
    }

    @Override
    public String toString() {
        return "InternalGetResponse{keyLength=" + key.length + ", correlationId='" + correlationId + 
               "', hasValue=" + (value != null) + "}";
    }
}
