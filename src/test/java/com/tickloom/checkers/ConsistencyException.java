package com.tickloom.checkers;

/**
 * Exception thrown when consistency checking operations fail.
 * Provides specific information about consistency-related errors.
 */
public class ConsistencyException extends RuntimeException {
    
    public ConsistencyException(String message) {
        super(message);
    }
    
    public ConsistencyException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public ConsistencyException(Throwable cause) {
        super(cause);
    }
}
