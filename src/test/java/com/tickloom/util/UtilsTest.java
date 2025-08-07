package com.tickloom.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UtilsTest {

    @Test
    @DisplayName("Should generate unique correlation IDs")
    void shouldGenerateUniqueCorrelationIds() {
        Set<String> generatedIds = new HashSet<>();
        int iterations = 1000;
        
        // Generate many correlation IDs
        for (int i = 0; i < iterations; i++) {
            String correlationId = Utils.generateCorrelationId();
            assertNotNull(correlationId, "Correlation ID should not be null");
            assertFalse(correlationId.isEmpty(), "Correlation ID should not be empty");
            assertTrue(generatedIds.add(correlationId), "Correlation ID should be unique: " + correlationId);
        }
        
        assertEquals(iterations, generatedIds.size(), "All correlation IDs should be unique");
    }

    @Test
    @DisplayName("Should generate valid UUID format correlation IDs")
    void shouldGenerateValidUuidFormatCorrelationIds() {
        for (int i = 0; i < 10; i++) {
            String correlationId = Utils.generateCorrelationId();
            
            // Should be able to parse as UUID (this will throw if invalid format)
            assertDoesNotThrow(() -> UUID.fromString(correlationId), 
                "Correlation ID should be valid UUID format: " + correlationId);
        }
    }

    @Test
    @DisplayName("Should generate prefixed correlation IDs")
    void shouldGeneratePrefixedCorrelationIds() {
        String prefix = "test-prefix";
        String correlationId = Utils.generateCorrelationId(prefix);
        
        assertNotNull(correlationId, "Prefixed correlation ID should not be null");
        assertTrue(correlationId.startsWith(prefix + "-"), 
            "Correlation ID should start with prefix: " + correlationId);
        
        // Extract UUID part (everything after prefix-)
        String uuidPart = correlationId.substring(prefix.length() + 1);
        assertDoesNotThrow(() -> UUID.fromString(uuidPart), 
            "UUID part should be valid UUID format: " + uuidPart);
    }

    @Test
    @DisplayName("Should generate different correlation IDs with same prefix")
    void shouldGenerateDifferentCorrelationIdsWithSamePrefix() {
        String prefix = "internal";
        String id1 = Utils.generateCorrelationId(prefix);
        String id2 = Utils.generateCorrelationId(prefix);
        
        assertNotEquals(id1, id2, "Correlation IDs with same prefix should be different");
        assertTrue(id1.startsWith(prefix + "-"), "First ID should have prefix");
        assertTrue(id2.startsWith(prefix + "-"), "Second ID should have prefix");
    }

    @Test
    @DisplayName("Should handle empty prefix")
    void shouldHandleEmptyPrefix() {
        String correlationId = Utils.generateCorrelationId("");
        
        assertNotNull(correlationId, "Correlation ID should not be null");
        assertTrue(correlationId.startsWith("-"), "Should start with dash when prefix is empty");
        
        // Extract UUID part (everything after -)
        String uuidPart = correlationId.substring(1);
        assertDoesNotThrow(() -> UUID.fromString(uuidPart), 
            "UUID part should be valid UUID format: " + uuidPart);
    }

    @Test
    @DisplayName("Should handle null prefix gracefully")
    void shouldHandleNullPrefix() {
        // This should not throw - let's see what happens
        assertDoesNotThrow(() -> Utils.generateCorrelationId(null), 
            "Should handle null prefix gracefully");
    }
}
