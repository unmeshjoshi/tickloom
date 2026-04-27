package com.tickloom.io;

import com.tickloom.future.ListenableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class LogStoreTest {

    private SimulatedFileIO fileIO;
    private LogStore logStore;

    @BeforeEach
    void setUp() {
        fileIO = new SimulatedFileIO("wal.dat", new Random(42));
        logStore = new LogStore(fileIO);
    }

    @Test
    void appendAndRead() {
        ListenableFuture<Long> appendFuture = logStore.append("hello".getBytes());
        fileIO.tick();

        assertTrue(appendFuture.isCompleted());
        assertEquals(1L, appendFuture.getResult());

        byte[] entry = logStore.read(1);
        assertArrayEquals("hello".getBytes(), entry);
    }

    @Test
    void appendMultipleEntries() {
        logStore.append("first".getBytes());
        logStore.append("second".getBytes());
        logStore.append("third".getBytes());
        fileIO.tick();

        assertEquals(3, logStore.lastIndex());

        assertArrayEquals("first".getBytes(), logStore.read(1));
        assertArrayEquals("second".getBytes(), logStore.read(2));
        assertArrayEquals("third".getBytes(), logStore.read(3));
    }

    @Test
    void lastIndexStartsAtZero() {
        assertEquals(0, logStore.lastIndex());
    }

    @Test
    void readInvalidIndexReturnsNull() {
        assertNull(logStore.read(1));
        assertNull(logStore.read(0));
        assertNull(logStore.read(-1));
    }

    @Test
    void recoverFromFile() {
        logStore.append("first".getBytes());
        logStore.append("second".getBytes());
        logStore.append("third".getBytes());
        fileIO.tick();

        logStore.sync();
        fileIO.tick();

        // simulate crash + restart: new LogStore over same FileIO
        fileIO.crash();
        LogStore recovered = LogStore.recover(fileIO);

        assertEquals(3, recovered.lastIndex());
        assertArrayEquals("first".getBytes(), recovered.read(1));
        assertArrayEquals("second".getBytes(), recovered.read(2));
        assertArrayEquals("third".getBytes(), recovered.read(3));
    }

    @Test
    void recoverSkipsUnsyncedEntries() {
        logStore.append("synced".getBytes());
        fileIO.tick();
        logStore.sync();
        fileIO.tick();

        logStore.append("unsynced".getBytes());
        fileIO.tick();

        fileIO.crash();
        LogStore recovered = LogStore.recover(fileIO);

        assertEquals(1, recovered.lastIndex());
        assertArrayEquals("synced".getBytes(), recovered.read(1));
    }

    @Test
    void recoverEmptyFile() {
        LogStore recovered = LogStore.recover(fileIO);
        assertEquals(0, recovered.lastIndex());
    }

    @Test
    void appendAfterRecovery() {
        logStore.append("before".getBytes());
        fileIO.tick();
        logStore.sync();
        fileIO.tick();

        fileIO.crash();
        LogStore recovered = LogStore.recover(fileIO);

        recovered.append("after".getBytes());
        fileIO.tick();

        assertEquals(2, recovered.lastIndex());
        assertArrayEquals("before".getBytes(), recovered.read(1));
        assertArrayEquals("after".getBytes(), recovered.read(2));
    }
}
