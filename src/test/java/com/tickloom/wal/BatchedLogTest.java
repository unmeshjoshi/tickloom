package com.tickloom.wal;

import org.junit.jupiter.api.Test;
import com.tickloom.future.TickCompletableFuture;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BatchedLogTest {
    private static final int WAIT = 3;
    private final RecordingFileIO file = new RecordingFileIO();
    private final BatchedLog log;

    BatchedLogTest() {
        Wal wal = new Wal(file);
        wal.recover();
        file.ops.get(0).future().complete(ByteBuffer.allocate(0));
        file.ops.clear();
        log = new BatchedLog(wal, WAIT);
    }

    private void tick(int n) {
        for (int i = 0; i < n; i++) log.tick();
    }

    private void completeWrites() {
        file.ofKind("write").forEach(op -> {
            if (op.future().isPending()) op.future().complete(1);
        });
    }

    @Test
    void threeAppendsInOneTickGiveThreePendingFuturesAndNoSync() {
        TickCompletableFuture<Long> a = log.append(new byte[4]);
        TickCompletableFuture<Long> b = log.append(new byte[4]);
        TickCompletableFuture<Long> c = log.append(new byte[4]);
        completeWrites();
        tick(WAIT - 1);

        assertTrue(a.isPending() && b.isPending() && c.isPending());
        assertTrue(file.ofKind("sync").isEmpty());
        assertEquals(3, file.ofKind("write").size());   // the writes went out at once
    }

    @Test
    void theWaitEndsWithOneSyncThatCompletesAllInOrder() {
        TickCompletableFuture<Long> a = log.append(new byte[4]);
        TickCompletableFuture<Long> b = log.append(new byte[4]);
        TickCompletableFuture<Long> c = log.append(new byte[4]);
        completeWrites();
        tick(WAIT);

        assertEquals(1, file.ofKind("sync").size());
        assertTrue(a.isPending());   // written, not durable

        file.ofKind("sync").get(0).future().complete(null);

        int record = RecordCodec.HEADER_SIZE + 4;
        assertEquals(0, a.getResult());
        assertEquals(record, b.getResult());
        assertEquals(2 * record, c.getResult());
    }

    @Test
    void aLoneAppendWaitsExactlyTheGroupWait() {
        log.append(new byte[4]);
        completeWrites();
        tick(WAIT - 1);
        assertTrue(file.ofKind("sync").isEmpty());
        tick(1);
        assertEquals(1, file.ofKind("sync").size());
    }

    @Test
    void appendsDuringASyncWaitForTheNextOne() {
        TickCompletableFuture<Long> first = log.append(new byte[4]);
        completeWrites();
        tick(WAIT);
        TickCompletableFuture<Long> second = log.append(new byte[4]);   // sync 1 is in flight
        completeWrites();
        tick(WAIT + 2);
        assertEquals(1, file.ofKind("sync").size(), "at most one sync in flight");

        file.ofKind("sync").get(0).future().complete(null);
        assertTrue(first.isCompleted());
        assertTrue(second.isPending(), "the second record was not covered by the first sync");

        tick(1);
        assertEquals(2, file.ofKind("sync").size());
        file.ofKind("sync").get(1).future().complete(null);
        assertTrue(second.isCompleted());
    }

    @Test
    void aFailedSyncFailsAllWaitersAndLaterAppends() {
        TickCompletableFuture<Long> a = log.append(new byte[4]);
        TickCompletableFuture<Long> b = log.append(new byte[4]);
        completeWrites();
        tick(WAIT);
        IOException failure = new IOException("disk on fire");
        file.ofKind("sync").get(0).future().fail(failure);

        assertSame(failure, a.getException());
        assertSame(failure, b.getException());
        assertTrue(log.append(new byte[4]).isFailed());
    }

    @Test
    void aFailedWriteFailsAllWaiters() {
        TickCompletableFuture<Long> a = log.append(new byte[4]);
        TickCompletableFuture<Long> b = log.append(new byte[4]);
        IOException failure = new IOException("bad sector");
        file.ofKind("write").get(0).future().fail(failure);

        assertTrue(a.isFailed());
        assertTrue(b.isFailed());
        tick(WAIT);
        assertTrue(file.ofKind("sync").isEmpty());
    }
}
