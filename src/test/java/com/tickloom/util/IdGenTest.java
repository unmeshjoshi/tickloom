package com.tickloom.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IdGenTest {

    @Test
    @DisplayName("Should generate unique correlation IDs")
    void shouldGenerateUniqueCorrelationIds() {
        Set<String> generatedIds = new HashSet<>();
        int iterations = 1000;
        IdGen idGen = new IdGen("test-process", new Random(12345L));
        
        // Generate many correlation IDs
        for (int i = 0; i < iterations; i++) {
            String correlationId = idGen.generateCorrelationId();
            assertNotNull(correlationId, "Correlation ID should not be null");
            assertFalse(correlationId.isEmpty(), "Correlation ID should not be empty");
            assertTrue(generatedIds.add(correlationId), "Correlation ID should be unique: " + correlationId);
        }
        
        assertEquals(iterations, generatedIds.size(), "All correlation IDs should be unique");
    }

    @Test
    @DisplayName("Should generate deterministic correlation IDs with same seed")
    void shouldGenerateDeterministicCorrelationIds() {
        IdGen idGen1 = new IdGen("test-process", new Random(12345L));
        IdGen idGen2 = new IdGen("test-process", new Random(12345L));
        
        // Generate same sequence of IDs
        for (int i = 0; i < 10; i++) {
            String id1 = idGen1.generateCorrelationId();
            String id2 = idGen2.generateCorrelationId();
            assertEquals(id1, id2, "Same seed should produce same IDs: " + id1);
        }
    }

    @Test
    @DisplayName("Should generate prefixed correlation IDs")
    void shouldGeneratePrefixedCorrelationIds() {
        String prefix = "test-prefix";
        IdGen idGen = new IdGen("test-process", new Random());
        String correlationId = idGen.generateCorrelationId(prefix);
        
        assertNotNull(correlationId, "Prefixed correlation ID should not be null");
        assertTrue(correlationId.startsWith(prefix + "-"), 
            "Correlation ID should start with prefix: " + correlationId);
    }

    @Test
    @DisplayName("Should generate different correlation IDs with same prefix")
    void shouldGenerateDifferentCorrelationIdsWithSamePrefix() {
        String prefix = "internal";
        IdGen idGen = new IdGen("test-process", new Random());
        String id1 = idGen.generateCorrelationId(prefix);
        String id2 = idGen.generateCorrelationId(prefix);
        
        assertNotEquals(id1, id2, "Correlation IDs with same prefix should be different");
        assertTrue(id1.startsWith(prefix + "-"), "First ID should have prefix");
        assertTrue(id2.startsWith(prefix + "-"), "Second ID should have prefix");
    }

    @Test
    @DisplayName("Should handle empty prefix")
    void shouldHandleEmptyPrefix() {
        IdGen idGen = new IdGen("test-process", new Random());
        String correlationId = idGen.generateCorrelationId("");
        
        assertNotNull(correlationId, "Correlation ID should not be null");
        assertTrue(correlationId.startsWith("-"), "Should start with dash when prefix is empty");
    }

    @Test
    @DisplayName("Should handle null prefix gracefully")
    void shouldHandleNullPrefix() {
        IdGen idGen = new IdGen("test-process", new Random());
        // This should not throw - let's see what happens
        assertDoesNotThrow(() -> idGen.generateCorrelationId(null),
            "Should handle null prefix gracefully");
    }
}
