package com.tickstep;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable identifier for any process (replica, worker, client) in the system.
 * It is just a string but conveniences exist for creating random UUID-backed IDs.
 */
public record ProcessId(String value) {

    public ProcessId {
        Objects.requireNonNull(value, "Process ID cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Process ID cannot be blank");
        }
    }

    /**
     * Returns this ID as a human-readable name (alias for {@link #value()}).
     */
    public String name() {
        return value;
    }

    /**
     * Creates a {@code ProcessId} backed by a random UUID string.
     */
    public static ProcessId random() {
        return new ProcessId(UUID.randomUUID().toString());
    }

    /**
     * Convenience factory to wrap an arbitrary string.
     */
    public static ProcessId of(String id) {
        return new ProcessId(id);
    }

    @Override
    public String toString() {
        return value;
    }
}