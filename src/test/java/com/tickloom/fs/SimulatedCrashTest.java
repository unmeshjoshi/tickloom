package com.tickloom.fs;

import org.junit.jupiter.api.Test;
import com.tickloom.fs.FileIO;
import com.tickloom.future.TickCompletableFuture;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/** A crash leaves what was synced: synced bytes and synced names, nothing else. */
class SimulatedCrashTest {
    private final SimulatedFileSystem fs = new SimulatedFileSystem();

    private <T> T await(TickCompletableFuture<T> future) {
        for (int i = 0; i < 1_000 && future.isPending(); i++) fs.tick();
        assertFalse(future.isPending(), "still pending");
        return future.getResult();
    }

    private void write(FileIO file, String text, long offset) {
        await(file.write(ByteBuffer.wrap(text.getBytes()), offset));
    }

    private String contents(FileIO file) {
        long size = await(file.size());
        ByteBuffer read = await(file.read(0, (int) size));
        return new String(read.array(), 0, read.remaining());
    }

    private void crashAndRestart() {
        fs.crash();
        fs.restart();
    }

    @Test
    void syncedBytesSurviveAndUnsyncedBytesAreLost() {
        FileIO file = await(fs.open("wal", OpenMode.CREATE_NEW));
        await(fs.syncDirectory());
        write(file, "synced", 0);
        await(file.sync());
        write(file, "-lost", 6);

        assertEquals("synced-lost", contents(file));
        crashAndRestart();

        assertEquals("synced", contents(await(fs.open("wal", OpenMode.OPEN_EXISTING))));
    }

    @Test
    void aWriteAfterTheLastSyncIsLostEvenIfItCompleted() {
        FileIO file = await(fs.open("wal", OpenMode.CREATE_NEW));
        await(fs.syncDirectory());
        write(file, "never synced", 0);

        crashAndRestart();

        assertEquals("", contents(await(fs.open("wal", OpenMode.OPEN_EXISTING))));
    }

    @Test
    void aCallStillQueuedAtTheCrashNeverTakesEffect() {
        FileIO file = await(fs.open("wal", OpenMode.CREATE_NEW));
        await(fs.syncDirectory());
        file.write(ByteBuffer.wrap("queued".getBytes()), 0);
        file.sync();   // issued, not yet complete

        crashAndRestart();

        assertEquals("", contents(await(fs.open("wal", OpenMode.OPEN_EXISTING))));
    }

    @Test
    void aTruncateIsLostUntilItIsSynced() {
        FileIO file = await(fs.open("wal", OpenMode.CREATE_NEW));
        await(fs.syncDirectory());
        write(file, "abcdef", 0);
        await(file.sync());
        await(file.truncate(2));

        crashAndRestart();

        assertEquals("abcdef", contents(await(fs.open("wal", OpenMode.OPEN_EXISTING))));
    }

    @Test
    void aCreatedFileWithoutSyncDirectoryIsGone() {
        await(fs.open("wal", OpenMode.CREATE_NEW));
        crashAndRestart();
        assertFalse(await(fs.exists("wal")));
    }

    @Test
    void aSyncedDirectoryKeepsTheName() {
        await(fs.open("wal", OpenMode.CREATE_NEW));
        await(fs.syncDirectory());
        crashAndRestart();
        assertTrue(await(fs.exists("wal")));
    }

    @Test
    void aDeletedNameComesBackWithoutSyncDirectory() {
        await(fs.open("wal", OpenMode.CREATE_NEW));
        await(fs.syncDirectory());
        await(fs.delete("wal"));
        crashAndRestart();
        assertTrue(await(fs.exists("wal")));
    }

    @Test
    void aRenameIsUndoneWithoutSyncDirectory() {
        await(fs.open("old", OpenMode.CREATE_NEW));
        await(fs.syncDirectory());
        await(fs.rename("old", "new"));
        crashAndRestart();
        assertTrue(await(fs.exists("old")));
        assertFalse(await(fs.exists("new")));
    }

    @Test
    void oldHandlesAreDeadAndCallsFailUntilRestart() {
        FileIO file = await(fs.open("wal", OpenMode.CREATE_NEW));
        fs.crash();

        TickCompletableFuture<Long> size = file.size();
        assertTrue(size.isFailed());
        assertInstanceOf(IOException.class, size.getException());
        assertTrue(fs.open("wal", OpenMode.OPEN_OR_CREATE).isFailed());

        fs.restart();
        TickCompletableFuture<Long> afterRestart = file.size();
        assertTrue(afterRestart.isFailed(), "a handle from before the crash stays dead");
    }
}
