package com.tickloom.algorithms.replication.quorum;

import java.util.Arrays;
import java.util.Objects;

/**
 * Internal request sent between replicas to set a value for a specific key.
 */
public record InternalSetRequest(byte[] key, byte[] value, long timestamp, String correlationId) {
    public InternalSetRequest {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(value, "Value cannot be null");
        Objects.requireNonNull(correlationId, "Correlation ID cannot be null");
        if (key.length == 0) {
            throw new IllegalArgumentException("Key cannot be empty");
        }
        if (correlationId.isBlank()) {
            throw new IllegalArgumentException("Correlation ID cannot be blank");
        }
        if (timestamp <= 0) {
            throw new IllegalArgumentException("Timestamp must be positive");
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        InternalSetRequest that = (InternalSetRequest) obj;
        return timestamp == that.timestamp &&
               Arrays.equals(key, that.key) &&
               Arrays.equals(value, that.value) &&
               Objects.equals(correlationId, that.correlationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(key), Arrays.hashCode(value), timestamp, correlationId);
    }

    @Override
    public String toString() {
        return "InternalSetRequest{keyLength=" + key.length + ", timestamp=" + timestamp + 
               ", correlationId='" + correlationId + "', valueLength=" + value.length + "}";
    }
}
