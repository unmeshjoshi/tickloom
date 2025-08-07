package com.tickloom.util;

/**
 * Stub implementation of Clock for testing purposes.
 * Allows manual control over time progression for deterministic tests.
 */
public class StubClock implements Clock {
    
    private long currentTime;
    
    /**
     * Creates a StubClock with initial time set to 0.
     */
    public StubClock() {
        this(0);
    }
    
    /**
     * Creates a StubClock with the specified initial time.
     * 
     * @param initialTime the initial time in milliseconds
     */
    public StubClock(long initialTime) {
        this.currentTime = initialTime;
    }
    
    @Override
    public long now() {
        return currentTime;
    }
    
    /**
     * Sets the current time to the specified value.
     * 
     * @param time the time to set in milliseconds
     */
    public void setTime(long time) {
        this.currentTime = time;
    }
    
    /**
     * Advances the clock by the specified amount.
     * 
     * @param millis the number of milliseconds to advance
     */
    public void advance(long millis) {
        this.currentTime += millis;
    }
    
    /**
     * Advances the clock by one millisecond.
     */
    public void tick() {
        advance(1);
    }
}
