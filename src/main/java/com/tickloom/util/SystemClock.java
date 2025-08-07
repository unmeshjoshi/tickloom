package com.tickloom.util;

/**
 * Production implementation of Clock that uses the system time.
 */
public class SystemClock implements Clock {
    
    @Override
    public long now() {
        return System.currentTimeMillis();
    }
}
