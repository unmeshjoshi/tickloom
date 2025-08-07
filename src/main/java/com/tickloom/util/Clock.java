package com.tickloom.util;

/**
 * Clock abstraction for time-related operations.
 * This allows for deterministic testing by providing stub implementations
 * while using system time in production.
 */
public interface Clock {
    
    /**
     * Returns the current time in milliseconds since the Unix epoch.
     * 
     * @return current time in milliseconds
     */
    long now();
}
