package com.tickloom.io;

import com.tickloom.future.TickCompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LogStoreWithLocalFileIOTest {

    @TempDir
    Path tempDir;

    private LocalFileIO fileIO;
    private LogStore logStore;

    @BeforeEach
    void setUp() throws Exception {
        fileIO = LocalFileIO.open(tempDir.resolve("wal.dat").toString());
        logStore = new LogStore(fileIO);
    }

    @AfterEach
    void tearDown() throws Exception {
        fileIO.close();
    }

    @Test
    void appendAndRead() {
        TickCompletableFuture<Long> appendFuture = logStore.append("hello".getBytes());
        assertTrue(appendFuture.isCompleted());
        assertEquals(1L, appendFuture.getResult());
        assertArrayEquals("hello".getBytes(), logStore.read(1));
    }

    @Test
    void appendMultipleEntries() {
        logStore.append("first".getBytes());
        logStore.append("second".getBytes());
        logStore.append("third".getBytes());

        assertEquals(3, logStore.lastIndex());
        assertArrayEquals("first".getBytes(), logStore.read(1));
        assertArrayEquals("second".getBytes(), logStore.read(2));
        assertArrayEquals("third".getBytes(), logStore.read(3));
    }

    @Test
    void recoverAfterCloseAndReopen() throws Exception {
        logStore.append("first".getBytes());
        logStore.append("second".getBytes());
        logStore.sync();
        String path = fileIO.getFilename();
        fileIO.close();

        LocalFileIO reopened = LocalFileIO.open(path);
        LogStore recovered = LogStore.recover(reopened);

        assertEquals(2, recovered.lastIndex());
        assertArrayEquals("first".getBytes(), recovered.read(1));
        assertArrayEquals("second".getBytes(), recovered.read(2));
        reopened.close();
    }

    @Test
    void appendAfterRecovery() throws Exception {
        logStore.append("before".getBytes());
        logStore.sync();
        String path = fileIO.getFilename();
        fileIO.close();

        LocalFileIO reopened = LocalFileIO.open(path);
        LogStore recovered = LogStore.recover(reopened);
        recovered.append("after".getBytes());

        assertEquals(2, recovered.lastIndex());
        assertArrayEquals("before".getBytes(), recovered.read(1));
        assertArrayEquals("after".getBytes(), recovered.read(2));
        reopened.close();
    }
}
