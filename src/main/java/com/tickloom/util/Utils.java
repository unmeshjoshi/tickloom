package com.tickloom.util;

import java.util.UUID;

/**
 * Utility class providing common functionality across the codebase.
 */
public class Utils {
    
    /**
     * Generates a unique correlation ID using UUID.
     * This is the industry best practice for correlation IDs as they are:
     * - Globally unique
     * - Thread-safe
     * - Not dependent on system time
     * - Suitable for distributed systems
     * 
     * @return a unique correlation ID as a string
     */
    public static String generateCorrelationId() {
        return UUID.randomUUID().toString();
    }
    
    /**
     * Generates a correlation ID with a prefix.
     * Useful for categorizing different types of correlations.
     * 
     * @param prefix the prefix to add to the correlation ID
     * @return a prefixed correlation ID
     */
    public static String generateCorrelationId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString();
    }
}
