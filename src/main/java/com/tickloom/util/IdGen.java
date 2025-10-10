package com.tickloom.util;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

/**
 * Deterministic ID generator for correlation IDs.
 * Uses a counter-based approach with deterministic hashing to ensure
 * reproducible results for testing while maintaining uniqueness.
 */
public class IdGen {
    private final String processId;
    private final RandomGenerator random;

    public IdGen(String processId, RandomGenerator random) {
        this.processId = processId;
        this.random = random;
    }
    
    /**
     * Generates a deterministic correlation ID.
     * Same inputs will always produce the same sequence of IDs.
     * 
     * @return a deterministic correlation ID as a string
     */
    public String generateCorrelationId() {
        return processId + "-" + deterministicRandomUUID();
    }
    
    /**
     * Generates a correlation ID with a prefix.
     * Useful for categorizing different types of correlations.
     * 
     * @param prefix the prefix to add to the correlation ID
     * @return a prefixed correlation ID
     */
    public String generateCorrelationId(String prefix) {
        String uuid = deterministicRandomUUID();
        return prefix + "-" + processId + "-" + uuid;
    }
    
    /**
     * Creates a deterministic hash from the given inputs.
     * Uses the Random instance to ensure deterministic behavior when seeded.
     */
    private String deterministicRandomUUID() {
        // Use the random instance to generate deterministic values
        // This ensures that with the same seed, we get the same sequence
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return UUID.nameUUIDFromBytes(bytes).toString();
    }
}
