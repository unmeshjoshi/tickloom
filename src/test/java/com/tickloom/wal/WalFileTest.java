package com.tickloom.wal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.tickloom.fs.OpenMode;
import com.tickloom.fs.RealFileSystem;
import com.tickloom.fs.FileIO;
import com.tickloom.future.TickCompletableFuture;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The layers on a real directory: RealFileSystem opens the file, then Wal, then BatchedLog. */
class WalFileTest {
    @TempDir
    Path dir;
    private RealFileSystem fileSystem;

    private RealFileSystem fileSystem() {
        if (fileSystem == null) fileSystem = new RealFileSystem(dir, 5_000);
        return fileSystem;
    }

    @AfterEach
    void stop() {
        if (fileSystem != null) fileSystem.shutdown();
    }

    private void tickUntilDone(BatchedLog log, TickCompletableFuture<?>... futures) throws InterruptedException {
        for (int i = 0; i < 5_000; i++) {
            boolean pending = false;
            for (TickCompletableFuture<?> f : futures) pending |= f.isPending();
            if (!pending) return;
            fileSystem().tick();
            if (log != null) log.tick();
            Thread.sleep(1);
        }
        fail("still pending");
    }

    private <T> T await(TickCompletableFuture<T> future) throws Exception {
        tickUntilDone(null, future);
        if (future.isFailed()) throw (Exception) future.getException();
        return future.getResult();
    }

    private Wal recovered(FileIO file, List<Wal.Entry> into) throws Exception {
        Wal wal = new Wal(file);
        into.addAll(await(wal.recover()));
        return wal;
    }

    @Test
    void syncedRecordsComeBackInOrderWithTheSameOffsets() throws Exception {
        FileIO file = await(fileSystem().open("wal", OpenMode.CREATE_NEW));
        BatchedLog log = new BatchedLog(recovered(file, new ArrayList<>()), 2);

        TickCompletableFuture<Long> a = log.append("first".getBytes());
        TickCompletableFuture<Long> b = log.append("second".getBytes());
        assertTrue(a.isPending(), "not durable before any sync");
        tickUntilDone(log, a, b);
        await(file.close());

        List<Wal.Entry> entries = new ArrayList<>();
        Wal wal = recovered(await(fileSystem().open("wal", OpenMode.OPEN_EXISTING)), entries);

        assertEquals(2, entries.size());
        assertEquals(a.getResult(), entries.get(0).offset());
        assertArrayEquals("first".getBytes(), entries.get(0).payload());
        assertEquals(b.getResult(), entries.get(1).offset());
        assertArrayEquals("second".getBytes(), entries.get(1).payload());
        assertEquals(wal.nextOffset(), wal.durableOffset());
    }

    @Test
    void recoveryStopsAtATornTail() throws Exception {
        FileIO file = await(fileSystem().open("wal", OpenMode.CREATE_NEW));
        Wal wal = recovered(file, new ArrayList<>());
        await(wal.append(List.of("good".getBytes())));
        await(wal.sync());
        long end = wal.nextOffset();

        // Half of a second record, written by hand after the last valid one.
        byte[] second = new RecordCodec(1024).encode(List.of("never finished".getBytes()));
        await(file.write(ByteBuffer.wrap(second, 0, second.length - 4), end));
        await(file.close());

        List<Wal.Entry> entries = new ArrayList<>();
        Wal recovered = recovered(await(fileSystem().open("wal", OpenMode.OPEN_EXISTING)), entries);

        assertEquals(1, entries.size());
        assertArrayEquals("good".getBytes(), entries.get(0).payload());
        assertEquals(end, recovered.nextOffset());
        FileIO reopened = await(fileSystem().open("wal", OpenMode.OPEN_EXISTING));
        assertEquals(end, await(reopened.size()), "recovery cut off the torn tail");
    }
}
