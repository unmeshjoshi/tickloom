package com.tickloom.id;

import com.tickloom.ProcessId;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class ProcessIdTest {

    @Test
    void createsRandomProcessIdNotBlank() {
        ProcessId pid = ProcessId.random();
        assertNotNull(pid);
        assertFalse(pid.name().isBlank());
    }

    @Test
    void createsProcessIdWithCustomString() {
        ProcessId pid = ProcessId.of("test-process");
        assertEquals("test-process", pid.name());
    }

    @Test
    void randomProcessIdsAreUnique() {
        int sampleSize = 1000;
        Set<String> ids = new HashSet<>(sampleSize);
        for (int i = 0; i < sampleSize; i++) {
            ids.add(ProcessId.random().name());
        }
        assertEquals(sampleSize, ids.size(), "Expected all generated process IDs to be unique");
    }
}