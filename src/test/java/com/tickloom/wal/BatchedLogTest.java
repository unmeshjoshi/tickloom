package com.tickloom.wal;

import org.junit.jupiter.api.Test;
import com.tickloom.fs.FileIO;
import com.tickloom.fs.OpenMode;
import com.tickloom.fs.SimulatedFileSystem;
import com.tickloom.future.TickCompletableFuture;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BatchedLog on a Wal on the simulated disk. A simulated call completes on the next tick, so a sync issued on
 * tick n completes on tick n + 1. What reached the disk is checked by crashing, restarting and recovering.
 */
class BatchedLogTest {
    private static final int WAIT = 3;
    private static final int RECORD = RecordCodec.HEADER_SIZE + 4;

    private final SimulatedFileSystem fs = new SimulatedFileSystem();
    private final FileIO file;
    private final BatchedLog log;

    BatchedLogTest() {
        file = await(fs.open("wal", OpenMode.CREATE_NEW));
        await(fs.syncDirectory());
        Wal wal = new Wal(file);
        await(wal.recover());
        log = new BatchedLog(wal, WAIT);
    }

    private <T> T await(TickCompletableFuture<T> future) {
        for (int i = 0; i < 1_000 && future.isPending(); i++) fs.tick();
        assertTrue(future.isCompleted(), "not completed: " + (future.isFailed() ? future.getException() : "pending"));
        return future.getResult();
    }

    private void tick(int n) {
        for (int i = 0; i < n; i++) {
            fs.tick();
            log.tick();
        }
    }

    private List<Wal.Entry> crashAndRecover() {
        fs.crash();
        fs.restart();
        return await(new Wal(await(fs.open("wal", OpenMode.OPEN_EXISTING))).recover());
    }

    @Test
    void appendsAreWrittenAtOnceButNotSyncedBeforeTheGroupWait() {
        TickCompletableFuture<Long> a = log.append(new byte[4]);
        TickCompletableFuture<Long> b = log.append(new byte[4]);
        TickCompletableFuture<Long> c = log.append(new byte[4]);
        tick(WAIT);   // the writes are done; the sync goes out on this tick but has not completed

        assertTrue(a.isPending() && b.isPending() && c.isPending());
        assertTrue(crashAndRecover().isEmpty(), "nothing was synced, so nothing survives the crash");
    }

    @Test
    void theWaitEndsWithOneSyncThatCompletesAllInOrder() {
        TickCompletableFuture<Long> a = log.append(new byte[4]);
        TickCompletableFuture<Long> b = log.append(new byte[4]);
        TickCompletableFuture<Long> c = log.append(new byte[4]);
        tick(WAIT + 1);

        assertEquals(0, a.getResult());
        assertEquals(RECORD, b.getResult());
        assertEquals(2 * RECORD, c.getResult());
        assertEquals(3, crashAndRecover().size());
    }

    @Test
    void appendsDuringASyncWaitForTheNextOne() {
        TickCompletableFuture<Long> first = log.append(new byte[4]);
        tick(WAIT);   // the sync for the first record is in flight
        TickCompletableFuture<Long> second = log.append(new byte[4]);
        tick(1);

        assertTrue(first.isCompleted());
        assertTrue(second.isPending(), "the second record was not covered by the first sync");

        tick(WAIT);
        assertEquals(RECORD, second.getResult());
        assertEquals(2, crashAndRecover().size());
    }

    @Test
    void aFailedSyncFailsAllWaitersAndLaterAppends() {
        TickCompletableFuture<Long> a = log.append(new byte[4]);
        TickCompletableFuture<Long> b = log.append(new byte[4]);
        tick(1);   // the writes are done
        file.close();   // every later call on the file fails, so the sync will
        tick(WAIT - 1);

        assertInstanceOf(IOException.class, a.getException());
        assertInstanceOf(IOException.class, b.getException());
        assertTrue(log.append(new byte[4]).isFailed());
    }

    @Test
    void aFailedWriteFailsAllWaitersAndLaterAppends() {
        file.close();   // every later call on the file fails

        TickCompletableFuture<Long> a = log.append(new byte[4]);

        assertInstanceOf(IOException.class, a.getException());
        assertTrue(log.append(new byte[4]).isFailed());
    }

    @Test
    void closeSyncsTheRecordsStillWaitingBeforeClosing() {
        TickCompletableFuture<Long> waiting = log.append(new byte[4]);

        TickCompletableFuture<Void> close = log.close();
        fs.tick();   // only the file is ticked, so the sync must come from close(), not from the batching in log.tick()

        assertTrue(close.isCompleted());
        assertEquals(0, waiting.getResult(), "the record was made durable before the file closed");
        assertTrue(log.append(new byte[4]).isFailed());
        assertEquals(1, crashAndRecover().size());
    }
}
