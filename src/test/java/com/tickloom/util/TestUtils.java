package com.tickloom.util;

import com.tickloom.Tickable;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.fail;

public class TestUtils {
    private static final int noOfTicks = 1000; // Shorter timeout to see what's happening

    public static void tickUntil(List<Tickable> tickables, Supplier<Boolean> condition) {
        int tickCount = 0;
        while (!condition.get()) {
            tickables.stream().forEach(tickable -> {
                try {
                    tickable.tick();
                } catch (Exception e) {
                    // network might be closed
                    System.out.println("Tick failed (likely closed): " + e.getMessage());
                }
            });

            tickCount++;

            if (tickCount > noOfTicks) {
                fail("Timeout waiting for condition to be met.");
            }
        }
        System.out.println("Condition met after " + tickCount + " ticks");
    }
}
