package com.tickloom.algorithms.replication.quorum;

import java.util.Arrays;
import java.util.Objects;

/**
 * Internal response sent between replicas for SET requests.
 */
public record InternalSetResponse(byte[] key, boolean success, String correlationId) {
    public InternalSetResponse {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(correlationId, "Correlation ID cannot be null");
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        InternalSetResponse that = (InternalSetResponse) obj;
        return success == that.success &&
               Arrays.equals(key, that.key) &&
               Objects.equals(correlationId, that.correlationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(key), success, correlationId);
    }

    @Override
    public String toString() {
        return "InternalSetResponse{keyLength=" + key.length + ", success=" + success + 
               ", correlationId='" + correlationId + "'}";
    }
}
