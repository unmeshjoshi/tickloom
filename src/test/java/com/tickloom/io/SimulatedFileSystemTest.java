package com.tickloom.io;

import com.tickloom.future.TickCompletableFuture;
import org.junit.jupiter.api.Test;

import java.nio.file.NoSuchFileException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SimulatedFileSystemTest {

    @Test
    void unsyncedFileDataIsLostOnCrash() {
        SimulatedFileSystem fs = new SimulatedFileSystem(new Random(42));

        // Create the file and make its directory entry durable
        FileIO file = complete(fs, fs.open("data/log", true));
        complete(fs, fs.syncDirectory("data"));

        complete(fs, file.write("unsynced".getBytes(), 0));
        assertArrayEquals("unsynced".getBytes(), complete(fs, file.read(0, 8)), "Unsynced data should be readable before crash");

        fs.crash();

        FileIO reopened = complete(fs, fs.open("data/log", false));
        assertEquals(0, reopened.size(), "Unsynced data should be lost on crash");
    }

    @Test
    void renamedFileIsVisibleUnderNewNameOnly() {
        SimulatedFileSystem fs = new SimulatedFileSystem(new Random(42));

        FileIO file = complete(fs, fs.open("data/state.tmp", true));
        complete(fs, file.write("v1".getBytes(), 0));

        complete(fs, fs.rename("data/state.tmp", "data/state"));

        FileIO renamed = complete(fs, fs.open("data/state", false));
        assertArrayEquals("v1".getBytes(), complete(fs, renamed.read(0, 2)), "Data should be readable under the new name");

        TickCompletableFuture<FileIO> old = fs.open("data/state.tmp", false);
        fs.tick();
        assertTrue(old.isFailed(), "Old name should no longer exist after rename");
        assertInstanceOf(NoSuchFileException.class, old.getException());
    }

    private static <T> T complete(SimulatedFileSystem fs, TickCompletableFuture<T> future) {
        fs.tick();
        assertTrue(future.isCompleted(), "Operation should complete on tick");
        return future.getResult();
    }
}
