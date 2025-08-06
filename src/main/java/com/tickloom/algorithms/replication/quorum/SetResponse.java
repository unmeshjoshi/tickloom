package com.tickloom.algorithms.replication.quorum;

import java.util.Arrays;
import java.util.Objects;

/**
 * Response to a client SET request.
 */
public record SetResponse(byte[] key, boolean success) {
    public SetResponse {
        Objects.requireNonNull(key, "Key cannot be null");
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        SetResponse that = (SetResponse) obj;
        return success == that.success && Arrays.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(key), success);
    }

    @Override
    public String toString() {
        return "SetResponse{keyLength=" + key.length + ", success=" + success + "}";
    }
}
