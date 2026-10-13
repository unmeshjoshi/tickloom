package com.tickloom.wal;

import org.junit.jupiter.api.Test;
import com.tickloom.fs.FileIO;
import com.tickloom.fs.OpenMode;
import com.tickloom.fs.SimulatedFileSystem;
import com.tickloom.future.TickCompletableFuture;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Wal on the simulated disk. What reached the disk is checked by crashing, restarting and recovering. */
class WalTest {
    private static final int HEADER = RecordCodec.HEADER_SIZE;

    private final SimulatedFileSystem fs = new SimulatedFileSystem();
    private final FileIO file;
    private final Wal wal;

    WalTest() {
        file = await(fs.open("wal", OpenMode.CREATE_NEW));
        await(fs.syncDirectory());
        wal = new Wal(file);
        assertTrue(await(wal.recover()).isEmpty());
    }

    private <T> T await(TickCompletableFuture<T> future) {
        for (int i = 0; i < 1_000 && future.isPending(); i++) fs.tick();
        assertTrue(future.isCompleted(), "not completed: " + (future.isFailed() ? future.getException() : "pending"));
        return future.getResult();
    }

    private List<Wal.Entry> crashAndRecover() {
        fs.crash();
        fs.restart();
        return await(new Wal(await(fs.open("wal", OpenMode.OPEN_EXISTING))).recover());
    }

    @Test
    void backToBackAppendsGetConsecutiveOffsets() {
        TickCompletableFuture<Long> a = wal.append(List.of(new byte[10]));
        TickCompletableFuture<Long> b = wal.append(List.of(new byte[20]));
        TickCompletableFuture<Long> c = wal.append(List.of(new byte[5], new byte[6]));
        assertEquals(HEADER + 10 + HEADER + 20 + 2 * HEADER + 11, wal.nextOffset());

        assertEquals(0, await(a));
        assertEquals(HEADER + 10, await(b));
        assertEquals(HEADER + 10 + HEADER + 20, await(c));
    }

    @Test
    void anAppendCompletesButIsNotDurableUntilSynced() {
        assertEquals(0, await(wal.append(List.of(new byte[10]))));
        assertEquals(0, wal.durableOffset());

        assertTrue(crashAndRecover().isEmpty(), "nothing was synced, so nothing survives the crash");
    }

    @Test
    void syncCoversEverythingAppendedBeforeItAndNothingAfter() {
        wal.append(List.of(new byte[10]));
        wal.append(List.of(new byte[10]));
        TickCompletableFuture<Long> sync = wal.sync();
        long covered = 2 * (HEADER + 10);
        TickCompletableFuture<Long> after = wal.append(List.of(new byte[10]));

        assertEquals(covered, await(sync));
        await(after);
        assertEquals(covered, wal.durableOffset());
        assertEquals(3 * (HEADER + 10), wal.nextOffset());

        assertEquals(2, crashAndRecover().size(), "the append after the sync call was not covered");
    }

    @Test
    void aFailedSyncPoisonsTheLog() {
        await(wal.append(List.of(new byte[10])));
        file.close();   // every later call on the file fails

        TickCompletableFuture<Long> sync = wal.sync();

        assertInstanceOf(IOException.class, sync.getException());
        assertTrue(wal.isPoisoned());
        assertTrue(wal.append(List.of(new byte[1])).isFailed());
        assertTrue(wal.sync().isFailed());
        assertEquals(0, wal.durableOffset());
    }

    @Test
    void aFailedWritePoisonsTheLog() {
        file.close();   // every later call on the file fails

        TickCompletableFuture<Long> append = wal.append(List.of(new byte[10]));

        assertInstanceOf(IOException.class, append.getException());
        assertTrue(wal.isPoisoned());
        assertTrue(wal.sync().isFailed());
    }

    @Test
    void aBadBatchFailsTheCallButDoesNotPoison() {
        assertTrue(wal.append(List.of(new byte[0])).isFailed());
        assertFalse(wal.isPoisoned());
        assertEquals(0, wal.nextOffset());
        assertEquals(0, await(wal.append(List.of(new byte[1]))), "the next append takes the unused offset");
    }

    @Test
    void appendAndSyncAreRejectedBeforeRecover() {
        Wal fresh = new Wal(await(fs.open("other", OpenMode.CREATE_NEW)));
        assertTrue(fresh.append(List.of(new byte[1])).isFailed());
        assertTrue(fresh.sync().isFailed());
    }

    @Test
    void recoverIsOnlyAllowedOnce() {
        assertTrue(wal.recover().isFailed());
    }

    @Test
    void closeClosesTheFileAfterTheCallsAlreadyIssued() {
        TickCompletableFuture<Long> append = wal.append(List.of(new byte[10]));
        TickCompletableFuture<Long> sync = wal.sync();

        TickCompletableFuture<Void> close = wal.close();
        assertTrue(close.isPending());
        fs.tick();

        assertTrue(close.isCompleted());
        assertEquals(0, append.getResult(), "the append issued before close still finished");
        assertEquals(HEADER + 10, sync.getResult(), "the sync issued before close still finished");
        assertInstanceOf(IOException.class, file.size().getException(), "the file is closed");
        assertEquals(1, crashAndRecover().size());
    }
}
