package com.tickloom.io;

import com.tickloom.future.TickCompletableFuture;
import org.junit.jupiter.api.Test;

import java.nio.file.NoSuchFileException;
import java.util.Random;

import static com.tickloom.util.TestUtils.tickUntilComplete;
import static org.junit.jupiter.api.Assertions.*;

/**
 * WAL durability on the simulated filesystem: what survives a crash depends on
 * whether the log was synced and whether its directory entry was made durable.
 */
class LogStoreOnSimulatedFileSystemTest {

    private final SimulatedFileSystem fs = new SimulatedFileSystem(new Random(42));

    @Test
    void syncedEntriesSurviveCrash() {
        LogStore wal = createWal();

        tickUntilComplete(fs, wal.append("x=1".getBytes()));
        tickUntilComplete(fs, wal.sync());

        fs.crash();

        LogStore recovered = reopenWal();
        assertEquals(1, recovered.lastIndex());
        assertArrayEquals("x=1".getBytes(), recovered.read(1));
    }

    @Test
    void unsyncedEntriesAreLostOnCrash() {
        LogStore wal = createWal();

        tickUntilComplete(fs, wal.append("x=1".getBytes()));
        tickUntilComplete(fs, wal.sync());
        tickUntilComplete(fs, wal.append("x=2".getBytes()));

        fs.crash();

        LogStore recovered = reopenWal();
        assertEquals(1, recovered.lastIndex(), "Only the synced entry should survive");
        assertArrayEquals("x=1".getBytes(), recovered.read(1));
    }

    @Test
    void crashWhileSyncIsPendingLosesEntry() {
        LogStore wal = createWal();

        wal.append("x=1".getBytes());
        TickCompletableFuture<Void> sync = wal.sync();
        assertTrue(sync.isPending(), "Sync should not complete until the filesystem ticks");

        fs.crash();

        assertTrue(sync.isPending(), "Sync pending at crash never completes");
        assertEquals(0, reopenWal().lastIndex());
    }

    @Test
    void walFileIsLostWithoutDirectorySyncEvenIfDataWasSynced() {
        FileIO file = tickUntilComplete(fs, fs.open("data/wal", true));
        LogStore wal = new LogStore(file);

        tickUntilComplete(fs, wal.append("x=1".getBytes()));
        tickUntilComplete(fs, wal.sync());

        fs.crash();

        TickCompletableFuture<FileIO> reopen = fs.open("data/wal", false);
        fs.tick();
        assertTrue(reopen.isFailed(), "WAL file whose creation was never made durable should be gone");
        assertInstanceOf(NoSuchFileException.class, reopen.getException());
    }

    private LogStore createWal() {
        FileIO file = tickUntilComplete(fs, fs.open("data/wal", true));
        tickUntilComplete(fs, fs.syncDirectory("data"));
        return new LogStore(file);
    }

    private LogStore reopenWal() {
        FileIO file = tickUntilComplete(fs, fs.open("data/wal", false));
        return LogStore.recover(file);
    }
}
