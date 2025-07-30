package com.tickloom.util;

/**
 * TigerBeetle-style timeout implementation that manages its own internal tick counter.
 * 
 * This class encapsulates timeout logic and eliminates the need for components
 * to maintain their own tick counters for timeout management.
 * 
 * Usage:
 * Timeout timeout = new Timeout("request-timeout", 10);
 * timeout.start();
 * 
 * // In tick loop:
 * timeout.tick();
 * if (timeout.fired()) {
 *     // Handle timeout
 *     timeout.reset();
 * }
 */
public final class Timeout {
    
    private final String name;
    private final long durationTicks;
    private long ticks = 0;
    private boolean ticking = false;
    private long startTick = 0;
    
    /**
     * Creates a new timeout with the specified duration.
     * 
     * @param name human-readable name for debugging
     * @param durationTicks number of ticks before timeout fires
     */
    public Timeout(String name, long durationTicks) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Name cannot be null or empty");
        }
        if (durationTicks <= 0) {
            throw new IllegalArgumentException("Duration must be positive");
        }
        
        this.name = name;
        this.durationTicks = durationTicks;
    }
    
    /**
     * Starts the timeout. The timeout will fire after durationTicks calls to tick().
     */
    public void start() {
        ticking = true;
        startTick = ticks;
    }
    
    /**
     * Increments the internal tick counter.
     * This should be called by the simulation loop.
     */
    public void tick() {
        ticks++;
    }
    
    /**
     * Checks if the timeout has fired.
     * 
     * @return true if the timeout has fired, false otherwise
     */
    public boolean fired() {
        if (!ticking) {
            return false;
        }
        return (ticks - startTick) >= durationTicks;
    }
    
    /**
     * Resets the timeout. The timeout will start counting from the current tick.
     */
    public void reset() {
        startTick = ticks;
    }
    
    /**
     * Stops the timeout. It will no longer fire until start() is called again.
     */
    public void stop() {
        ticking = false;
    }
    
    /**
     * Gets the name of this timeout.
     */
    public String getName() {
        return name;
    }
    
    /**
     * Gets the duration in ticks.
     */
    public long getDurationTicks() {
        return durationTicks;
    }
    
    /**
     * Gets the current tick count.
     */
    public long getTicks() {
        return ticks;
    }
    
    /**
     * Checks if the timeout is currently active.
     */
    public boolean isTicking() {
        return ticking;
    }
    
    /**
     * Gets the number of ticks remaining before timeout fires.
     * Returns 0 if not ticking or already fired.
     */
    public long getRemainingTicks() {
        if (!ticking) {
            return 0;
        }
        long elapsed = ticks - startTick;
        return Math.max(0, durationTicks - elapsed);
    }
    
    @Override
    public String toString() {
        return String.format("Timeout[name=%s, duration=%d, ticking=%s, remaining=%d]", 
                           name, durationTicks, ticking, getRemainingTicks());
    }
} 