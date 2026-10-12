package com.tickloom.wal;

import org.junit.jupiter.api.Test;
import com.tickloom.future.TickCompletableFuture;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WalTest {
    private final RecordingFileIO file = new RecordingFileIO();
    private final Wal wal = new Wal(file);

    private static final int HEADER = RecordCodec.HEADER_SIZE;

    @org.junit.jupiter.api.BeforeEach
    void recoverEmptyFile() {
        TickCompletableFuture<List<Wal.Entry>> recovery = wal.recover();
        file.ops.get(0).future().complete(ByteBuffer.allocate(0));
        assertTrue(recovery.getResult().isEmpty());
        file.ops.clear();
    }

    @Test
    void backToBackAppendsReachTheFileAtConsecutiveOffsets() {
        TickCompletableFuture<Long> a = wal.append(List.of(new byte[10]));
        TickCompletableFuture<Long> b = wal.append(List.of(new byte[20]));
        TickCompletableFuture<Long> c = wal.append(List.of(new byte[5], new byte[6]));

        List<RecordingFileIO.Op> writes = file.ofKind("write");
        assertEquals(0, writes.get(0).offset());
        assertEquals(HEADER + 10, writes.get(1).offset());
        assertEquals(HEADER + 10 + HEADER + 20, writes.get(2).offset());
        assertEquals(HEADER + 10 + HEADER + 20 + 2 * HEADER + 11, wal.nextOffset());

        // Completing out of order still reports each batch's own first offset.
        writes.get(2).future().complete(1);
        writes.get(0).future().complete(1);
        assertEquals(0, a.getResult());
        assertTrue(b.isPending());
        assertEquals(HEADER + 10 + HEADER + 20, c.getResult());
    }

    @Test
    void appendCompletesBeforeAnySyncAndDurableOffsetStaysPut() {
        TickCompletableFuture<Long> append = wal.append(List.of(new byte[10]));
        file.ops.get(0).future().complete(1);

        assertTrue(append.isCompleted());
        assertEquals(0, wal.durableOffset());
        assertTrue(file.ofKind("sync").isEmpty());
    }

    @Test
    void syncCoversEverythingAppendedBeforeItAndNothingAfter() {
        wal.append(List.of(new byte[10]));
        wal.append(List.of(new byte[10]));
        TickCompletableFuture<Long> sync = wal.sync();
        long covered = 2 * (HEADER + 10);
        wal.append(List.of(new byte[10]));   // after the sync call

        file.ofKind("sync").get(0).future().complete(null);

        assertEquals(covered, sync.getResult());
        assertEquals(covered, wal.durableOffset());
        assertEquals(3 * (HEADER + 10), wal.nextOffset());
    }

    @Test
    void theFileSeesNoSyncUnlessTheCallerAsks() {
        wal.append(List.of(new byte[10]));
        file.ops.get(0).future().complete(1);
        assertTrue(file.ofKind("sync").isEmpty());
    }

    @Test
    void aFailedSyncPoisonsTheLog() {
        wal.append(List.of(new byte[10]));
        TickCompletableFuture<Long> sync = wal.sync();
        IOException failure = new IOException("disk on fire");
        file.ofKind("sync").get(0).future().fail(failure);

        assertSame(failure, sync.getException());
        assertTrue(wal.isPoisoned());
        assertTrue(wal.append(List.of(new byte[1])).isFailed());
        assertTrue(wal.sync().isFailed());
        assertEquals(0, wal.durableOffset());
    }

    @Test
    void aFailedWritePoisonsTheLog() {
        TickCompletableFuture<Long> append = wal.append(List.of(new byte[10]));
        file.ops.get(0).future().fail(new IOException("bad sector"));

        assertTrue(append.isFailed());
        assertTrue(wal.isPoisoned());
        assertTrue(wal.sync().isFailed());
    }

    @Test
    void aBadBatchFailsTheCallButDoesNotPoison() {
        assertTrue(wal.append(List.of(new byte[0])).isFailed());
        assertFalse(wal.isPoisoned());
        assertEquals(0, wal.nextOffset());
        assertTrue(file.ops.isEmpty());
    }

    @Test
    void appendAndSyncAreRejectedBeforeRecover() {
        Wal fresh = new Wal(new RecordingFileIO());
        assertTrue(fresh.append(List.of(new byte[1])).isFailed());
        assertTrue(fresh.sync().isFailed());
    }

    @Test
    void recoverIsOnlyAllowedOnce() {
        assertTrue(wal.recover().isFailed());
    }
}
