package com.tickloom.io;

import com.tickloom.future.TickCompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SimulatedFileIOTest {

    private SimulatedFileIO fileIO;

    @BeforeEach
    void setUp() {
        fileIO = new SimulatedFileIO("test.dat", new Random(42));
    }

    @Test
    void writeAndReadBack() {
        byte[] data = "hello".getBytes();

        TickCompletableFuture<Integer> writeFuture = fileIO.write(data, 0);
        fileIO.tick();
        assertTrue(writeFuture.isCompleted());
        assertEquals(5, writeFuture.getResult());

        TickCompletableFuture<byte[]> readFuture = fileIO.read(0, 5);
        fileIO.tick();
        assertTrue(readFuture.isCompleted());
        assertArrayEquals(data, readFuture.getResult());
    }

    @Test
    void writeAtOffset() {
        fileIO.write("aaaa".getBytes(), 0);
        fileIO.tick();

        fileIO.write("bb".getBytes(), 2);
        fileIO.tick();

        TickCompletableFuture<byte[]> readFuture = fileIO.read(0, 4);
        fileIO.tick();

        assertArrayEquals("aabb".getBytes(), readFuture.getResult());
    }

    @Test
    void writeExtendsFile() {
        fileIO.write("abc".getBytes(), 0);
        fileIO.tick();
        assertEquals(3, fileIO.size());

        fileIO.write("de".getBytes(), 10);
        fileIO.tick();
        assertEquals(12, fileIO.size());
    }

    @Test
    void readBeyondSize() {
        fileIO.write("abc".getBytes(), 0);
        fileIO.tick();

        TickCompletableFuture<byte[]> readFuture = fileIO.read(0, 100);
        fileIO.tick();

        // should return only what's available
        assertEquals(3, readFuture.getResult().length);
        assertArrayEquals("abc".getBytes(), readFuture.getResult());
    }

    @Test
    void unsyncedWritesLostOnCrash() {
        fileIO.write("before".getBytes(), 0);
        fileIO.tick();

        fileIO.sync();
        fileIO.tick();

        fileIO.write("after!".getBytes(), 0);
        fileIO.tick();

        // verify overwrite visible before crash
        TickCompletableFuture<byte[]> readBeforeCrash = fileIO.read(0, 6);
        fileIO.tick();
        assertArrayEquals("after!".getBytes(), readBeforeCrash.getResult());

        // crash — unsynced write lost
        fileIO.crash();

        TickCompletableFuture<byte[]> readAfterCrash = fileIO.read(0, 6);
        fileIO.tick();
        assertArrayEquals("before".getBytes(), readAfterCrash.getResult());
    }

    @Test
    void syncMakesWritesDurable() {
        fileIO.write("durable".getBytes(), 0);
        fileIO.tick();

        fileIO.sync();
        fileIO.tick();

        fileIO.crash();

        TickCompletableFuture<byte[]> readFuture = fileIO.read(0, 7);
        fileIO.tick();
        assertArrayEquals("durable".getBytes(), readFuture.getResult());
    }

    @Test
    void delayedCompletion() {
        SimulatedFileIO delayed = new SimulatedFileIO("delayed.dat", new Random(42), 2, 1, 3, 0.0);

        TickCompletableFuture<Integer> writeFuture = delayed.write("test".getBytes(), 0);

        delayed.tick(); // tick 1
        assertFalse(writeFuture.isCompleted());

        delayed.tick(); // tick 2 — write completes
        assertTrue(writeFuture.isCompleted());

        TickCompletableFuture<byte[]> readFuture = delayed.read(0, 4);

        delayed.tick(); // tick 3 — read completes (1 tick delay)
        assertTrue(readFuture.isCompleted());
        assertArrayEquals("test".getBytes(), readFuture.getResult());
    }

    @Test
    void truncateShrinks() {
        fileIO.write("abcdef".getBytes(), 0);
        fileIO.tick();
        assertEquals(6, fileIO.size());

        TickCompletableFuture<Void> truncateFuture = fileIO.truncate(3);
        fileIO.tick();

        assertTrue(truncateFuture.isCompleted());
        assertEquals(3, fileIO.size());

        TickCompletableFuture<byte[]> readFuture = fileIO.read(0, 3);
        fileIO.tick();
        assertArrayEquals("abc".getBytes(), readFuture.getResult());
    }

    @Test
    void truncateUnsyncedLostOnCrash() {
        fileIO.write("abcdef".getBytes(), 0);
        fileIO.tick();
        fileIO.sync();
        fileIO.tick();

        fileIO.truncate(3);
        fileIO.tick();

        fileIO.crash();

        // size should revert to durable state
        assertEquals(6, fileIO.size());
        TickCompletableFuture<byte[]> readFuture = fileIO.read(0, 6);
        fileIO.tick();
        assertArrayEquals("abcdef".getBytes(), readFuture.getResult());
    }

    @Test
    void overwriteSameOffset() {
        fileIO.write("first_".getBytes(), 0);
        fileIO.tick();

        fileIO.write("second".getBytes(), 0);
        fileIO.tick();

        TickCompletableFuture<byte[]> readFuture = fileIO.read(0, 6);
        fileIO.tick();
        assertArrayEquals("second".getBytes(), readFuture.getResult());
    }
}
