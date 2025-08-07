package com.tickloom.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Clock implementations.
 * Demonstrates usage patterns for production and test scenarios.
 */
class ClockTest {

    @Test
    @DisplayName("SystemClock should return actual system time")
    void systemClockShouldReturnActualSystemTime() {
        // Given
        SystemClock systemClock = new SystemClock();
        long before = System.currentTimeMillis();
        
        // When
        long clockTime = systemClock.now();
        
        // Then
        long after = System.currentTimeMillis();
        assertTrue(clockTime >= before, "Clock time should be at least the time before the call");
        assertTrue(clockTime <= after, "Clock time should be at most the time after the call");
    }

    @Test
    @DisplayName("StubClock should allow manual time control")
    void stubClockShouldAllowManualTimeControl() {
        // Given
        StubClock stubClock = new StubClock(1000L);
        
        // When & Then
        assertEquals(1000L, stubClock.now(), "Should return initial time");
        
        stubClock.setTime(2000L);
        assertEquals(2000L, stubClock.now(), "Should return updated time");
        
        stubClock.advance(500L);
        assertEquals(2500L, stubClock.now(), "Should advance by specified amount");
        
        stubClock.tick();
        assertEquals(2501L, stubClock.now(), "Should advance by 1ms on tick");
    }

    @Test
    @DisplayName("StubClock should start at zero by default")
    void stubClockShouldStartAtZeroByDefault() {
        // Given
        StubClock stubClock = new StubClock();
        
        // When & Then
        assertEquals(0L, stubClock.now(), "Should start at time 0");
    }

    @Test
    @DisplayName("StubClock should support time-based testing scenarios")
    void stubClockShouldSupportTimeBasedTestingScenarios() {
        // Given
        StubClock stubClock = new StubClock();
        
        // Scenario: Testing timeouts
        long startTime = stubClock.now();
        assertEquals(0L, startTime, "Should start at 0");
        
        // Simulate 5 seconds passing
        stubClock.advance(5000L);
        long afterTimeout = stubClock.now();
        assertEquals(5000L, afterTimeout, "Should be 5 seconds later");
        
        // Scenario: Testing specific timestamps  
        long specificTime = 1609459200000L; // 2021-01-01 00:00:00 UTC
        stubClock.setTime(specificTime);
        assertEquals(specificTime, stubClock.now(), "Should handle specific timestamps");
        
        // Scenario: Testing incremental time progression
        for (int i = 1; i <= 10; i++) {
            stubClock.tick();
            assertEquals(specificTime + i, stubClock.now(), 
                "Should increment by 1ms each tick");
        }
    }
}
