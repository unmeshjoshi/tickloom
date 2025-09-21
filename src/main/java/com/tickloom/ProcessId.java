package com.tickloom;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Immutable identifier for any process (replica, worker, clientId) in the system.
 * It is just a string but conveniences exist for creating random UUID-backed IDs.
 * Uniqueness is guaranteed by an internal UUID.
 */
public record ProcessId(String name, Long id) {
    static Map<String, ProcessId> processIdMap = new ConcurrentHashMap<>();

    private static final AtomicLong counter = new AtomicLong(0);

    private ProcessId(String name) {
        this(name, counter.getAndIncrement());
    }

    /**
     * Returns this ID as a human-readable name (alias for {@link #name()}).
     */
    public String name() {
        return name;
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
    public static ProcessId of(String name) {
        return processIdMap.computeIfAbsent(name, ProcessId::new);
    }

    @Override
    public String toString() {
        return name;
    }
}