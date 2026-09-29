package com.tickloom.storage;

import com.tickloom.future.TickCompletableFuture;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SimulatedStorageTest {

    @Test
    void shouldApplyOperationsDueInSameTickInIssueOrder() {
        SimulatedStorage storage = new SimulatedStorage(new Random(42));
        byte[] key = "key".getBytes();

        for (int i = 0; i < 12; i++) {
            storage.put(key, ("value-" + i).getBytes());
        }
        TickCompletableFuture<byte[]> get = storage.get(key);

        storage.tick();

        assertTrue(get.isCompleted());
        assertEquals("value-11", new String(get.getResult()));
    }
}
