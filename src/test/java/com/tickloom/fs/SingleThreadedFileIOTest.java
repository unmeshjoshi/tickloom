package com.tickloom.fs;

import com.tickloom.future.TickCompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class SingleThreadedFileIOTest {

    @TempDir
    Path tempDir;

    private FileChannel open(String name) throws IOException {
        return FileChannel.open(tempDir.resolve(name), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
    }

    // One tick is about 1 ms, so the loop sleeps that long between ticks. Bounded so a bug fails instead of hanging.
    private void tickUntilDone(TickableFileIO fileIO, TickCompletableFuture<?>... futures) throws InterruptedException {
        for (int i = 0; i < 5_000; i++) {
            boolean pending = false;
            for (TickCompletableFuture<?> f : futures) {
                pending |= f.isPending();
            }
            if (!pending) {
                return;
            }
            fileIO.tick();
            Thread.sleep(1);
        }
        fail("futures still pending after 5000 ticks");
    }

    @Test
    void write() throws Exception {
        FileChannel channel = open("test.txt");
        SingleThreadedFileIO fileIO = new SingleThreadedFileIO(channel);
        TickCompletableFuture<Integer> writeFuture = fileIO.write(ByteBuffer.wrap("test string".getBytes()), 0);
        tickUntilDone(fileIO, writeFuture);

        assertEquals(11, writeFuture.getResult());
        assertEquals("test string", Files.readString(tempDir.resolve("test.txt")));
    }

    private static String text(ByteBuffer buffer) {
        return new String(buffer.array(), buffer.position(), buffer.remaining());
    }

    @Test
    void read() throws Exception {
        SingleThreadedFileIO fileIO = new SingleThreadedFileIO(open("read.txt"));
        TickCompletableFuture<Integer> write = fileIO.write(ByteBuffer.wrap("test string".getBytes()), 0);
        tickUntilDone(fileIO, write);

        TickCompletableFuture<ByteBuffer> whole = fileIO.read(0, 11);
        TickCompletableFuture<ByteBuffer> middle = fileIO.read(5, 3);
        tickUntilDone(fileIO, whole, middle);

        assertEquals("test string", text(whole.getResult()));
        assertEquals("str", text(middle.getResult()));
    }

    @Test
    void readNearAndPastTheEndOfTheFile() throws Exception {
        SingleThreadedFileIO fileIO = new SingleThreadedFileIO(open("eof.txt"));
        TickCompletableFuture<Integer> write = fileIO.write(ByteBuffer.wrap("abcde".getBytes()), 0);
        tickUntilDone(fileIO, write);

        TickCompletableFuture<ByteBuffer> shortRead = fileIO.read(3, 10);
        TickCompletableFuture<ByteBuffer> pastEnd = fileIO.read(100, 4);
        tickUntilDone(fileIO, shortRead, pastEnd);

        assertEquals("de", text(shortRead.getResult()));
        assertEquals(0, pastEnd.getResult().remaining());
    }

    @Test
    void aReadIssuedRightAfterAWriteSeesIt() throws Exception {
        SingleThreadedFileIO fileIO = new SingleThreadedFileIO(open("fifo.txt"));
        // No waiting in between: single-thread FIFO execution means the read runs after the write.
        TickCompletableFuture<Integer> write = fileIO.write(ByteBuffer.wrap("hello".getBytes()), 0);
        TickCompletableFuture<ByteBuffer> read = fileIO.read(0, 5);

        tickUntilDone(fileIO, write, read);

        assertEquals("hello", text(read.getResult()));
    }

    @Test
    void aFailedReadFailsTheFuture() throws Exception {
        FileChannel channel = open("closed-read.txt");
        SingleThreadedFileIO fileIO = new SingleThreadedFileIO(channel);
        channel.close();

        TickCompletableFuture<ByteBuffer> read = fileIO.read(0, 4);
        tickUntilDone(fileIO, read);

        assertTrue(read.isFailed());
        assertInstanceOf(ClosedChannelException.class, read.getException());
    }

    @Test
    void syncCompletesAfterTheWritesIssuedBeforeIt() throws Exception {
        SingleThreadedFileIO fileIO = new SingleThreadedFileIO(open("sync.txt"));
        TickCompletableFuture<Integer> write1 = fileIO.write(ByteBuffer.wrap("hello ".getBytes()), 0);
        TickCompletableFuture<Integer> write2 = fileIO.write(ByteBuffer.wrap("world".getBytes()), 6);
        TickCompletableFuture<Void> sync = fileIO.sync();

        // Distinct correlation ids: all three complete, none is overwritten.
        tickUntilDone(fileIO, write1, write2, sync);

        assertTrue(write1.isCompleted());
        assertTrue(write2.isCompleted());
        assertTrue(sync.isCompleted());
        assertEquals("hello world", Files.readString(tempDir.resolve("sync.txt")));
    }

    @Test
    void syncOrderMatchesSubmissionOrder() throws Exception {
        SingleThreadedFileIO fileIO = new SingleThreadedFileIO(open("order.txt"));
        TickCompletableFuture<Integer> write = fileIO.write(ByteBuffer.wrap("x".getBytes()), 0);
        TickCompletableFuture<Void> sync = fileIO.sync();
        boolean[] writeDoneWhenSyncCompleted = new boolean[1];
        sync.whenComplete((v, e) -> writeDoneWhenSyncCompleted[0] = write.isCompleted());

        tickUntilDone(fileIO, write, sync);

        assertTrue(writeDoneWhenSyncCompleted[0]);
    }

    @Test
    void aFailedSyncFailsTheFuture() throws Exception {
        FileChannel channel = open("closed.txt");
        SingleThreadedFileIO fileIO = new SingleThreadedFileIO(channel);
        channel.close();

        TickCompletableFuture<Void> sync = fileIO.sync();
        TickCompletableFuture<Integer> write = fileIO.write(ByteBuffer.wrap(new byte[]{1}), 0);
        tickUntilDone(fileIO, sync, write);

        assertTrue(sync.isFailed());
        assertInstanceOf(ClosedChannelException.class, sync.getException());
        assertTrue(write.isFailed());
        assertInstanceOf(ClosedChannelException.class, write.getException());
    }

    @Test
    void writeCopiesTheCallersBuffer() throws Exception {
        SingleThreadedFileIO fileIO = new SingleThreadedFileIO(open("copy.txt"));
        CountDownLatch release = new CountDownLatch(1);
        fileIO.ioThread.executor.submit(() -> {
            try {
                release.await();
            } catch (InterruptedException ignored) {
            }
        });
        ByteBuffer buffer = ByteBuffer.wrap("abc".getBytes());
        TickCompletableFuture<Integer> write = fileIO.write(buffer, 0);

        // The write has not started. Reuse the buffer, as a caller is allowed to.
        buffer.array()[0] = 'X';
        release.countDown();
        tickUntilDone(fileIO, write);

        assertEquals(3, write.getResult());
        assertEquals("abc", Files.readString(tempDir.resolve("copy.txt")));
        assertEquals(0, buffer.position(), "the caller's buffer position is untouched");
    }

    @Test
    void writeUsesOnlyTheBytesBetweenPositionAndLimit() throws Exception {
        SingleThreadedFileIO fileIO = new SingleThreadedFileIO(open("slice.txt"));
        ByteBuffer buffer = ByteBuffer.wrap("__data__".getBytes());
        buffer.position(2).limit(6);
        TickCompletableFuture<Integer> write = fileIO.write(buffer, 0);
        tickUntilDone(fileIO, write);

        assertEquals(4, write.getResult());
        assertEquals("data", Files.readString(tempDir.resolve("slice.txt")));
    }

    @Test
    void sizeAndTruncate() throws Exception {
        SingleThreadedFileIO fileIO = new SingleThreadedFileIO(open("size.txt"));
        TickCompletableFuture<Integer> write = fileIO.write(ByteBuffer.wrap("0123456789".getBytes()), 0);
        TickCompletableFuture<Long> before = fileIO.size();
        TickCompletableFuture<Void> shrink = fileIO.truncate(4);
        TickCompletableFuture<Long> afterShrink = fileIO.size();
        TickCompletableFuture<Void> noGrow = fileIO.truncate(100);
        TickCompletableFuture<Long> afterNoGrow = fileIO.size();
        TickCompletableFuture<ByteBuffer> read = fileIO.read(0, 10);
        tickUntilDone(fileIO, write, before, shrink, afterShrink, noGrow, afterNoGrow, read);

        assertEquals(10L, before.getResult());
        assertEquals(4L, afterShrink.getResult());
        assertEquals(4L, afterNoGrow.getResult());
        assertEquals("0123", text(read.getResult()));
    }

    @Test
    void closeRunsAfterEarlierCallsAndRejectsLaterOnes() throws Exception {
        FileChannel channel = open("close.txt");
        SingleThreadedFileIO fileIO = new SingleThreadedFileIO(channel);
        TickCompletableFuture<Integer> write = fileIO.write(ByteBuffer.wrap("kept".getBytes()), 0);
        TickCompletableFuture<Void> closed = fileIO.close();
        TickCompletableFuture<Integer> afterClose = fileIO.write(ByteBuffer.wrap("x".getBytes()), 0);

        assertTrue(afterClose.isFailed());
        assertInstanceOf(RejectedExecutionException.class, afterClose.getException());

        tickUntilDone(fileIO, write, closed);

        assertTrue(write.isCompleted(), "the write issued before close finishes");
        assertTrue(closed.isCompleted());
        assertFalse(channel.isOpen());
        assertTrue(fileIO.ioThread.executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("kept", Files.readString(tempDir.resolve("close.txt")));
    }
}
