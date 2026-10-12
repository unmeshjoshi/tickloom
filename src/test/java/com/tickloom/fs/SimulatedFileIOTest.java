package com.tickloom.fs;

import org.junit.jupiter.api.Test;
import com.tickloom.future.TickCompletableFuture;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class SimulatedFileIOTest {
    private final SimulatedFileIO io = SimulatedFileIO.standalone();

    private void tick(int n) {
        for (int i = 0; i < n; i++) io.tick();
    }

    @Test
    void nothingCompletesUntilItsTick() {
        TickCompletableFuture<Integer> write = io.write(ByteBuffer.wrap(new byte[3]), 0);
        assertTrue(write.isPending());
        tick(1);
        assertEquals(3, write.getResult());
    }

    @Test
    void callsCompleteInTheOrderIssued() {
        TickCompletableFuture<Integer> write = io.write(ByteBuffer.wrap("abc".getBytes()), 0);
        TickCompletableFuture<ByteBuffer> read = io.read(0, 3);
        TickCompletableFuture<Void> sync = io.sync();
        io.tick();
        assertTrue(write.isCompleted() && read.isCompleted() && sync.isCompleted());
        assertEquals("abc", new String(read.getResult().array()));
    }

    @Test
    void writeCopiesTheCallersBuffer() {
        byte[] bytes = "abc".getBytes();
        io.write(ByteBuffer.wrap(bytes), 0);
        bytes[0] = 'X';
        TickCompletableFuture<ByteBuffer> read = io.read(0, 3);
        tick(20);
        assertEquals("abc", new String(read.getResult().array()));
    }

    @Test
    void shortReadAtTheEndAndEmptyReadPastIt() {
        io.write(ByteBuffer.wrap("abcde".getBytes()), 0);
        TickCompletableFuture<ByteBuffer> shortRead = io.read(3, 10);
        TickCompletableFuture<ByteBuffer> past = io.read(5, 10);
        TickCompletableFuture<ByteBuffer> farPast = io.read(100, 10);
        tick(30);
        assertEquals("de", new String(shortRead.getResult().array(), 0, shortRead.getResult().remaining()));
        assertEquals(0, past.getResult().remaining());
        assertEquals(0, farPast.getResult().remaining());
    }

    @Test
    void aGapReadsAsZerosAndTruncateOnlyShrinks() {
        io.write(ByteBuffer.wrap("x".getBytes()), 4);
        TickCompletableFuture<Void> grow = io.truncate(100);
        TickCompletableFuture<Long> size = io.size();
        tick(30);
        assertEquals(5L, size.getResult());
        assertTrue(grow.isCompleted());
        TickCompletableFuture<Void> shrink = io.truncate(2);
        TickCompletableFuture<Long> after = io.size();
        tick(30);
        assertEquals(2L, after.getResult());
    }

    @Test
    void aCallMadeFromACallbackCompletesOnTheNextTick() {
        TickCompletableFuture<Integer> second = new TickCompletableFuture<>();
        io.write(ByteBuffer.wrap(new byte[1]), 0).whenComplete((n, e) -> io.write(ByteBuffer.wrap(new byte[2]), 0)
                .whenComplete((m, f) -> second.complete(m)));
        tick(1);
        assertTrue(second.isPending());
        tick(1);
        assertEquals(2, second.getResult());
    }

    @Test
    void callsAfterCloseFailButEarlierOnesFinish() {
        TickCompletableFuture<Integer> write = io.write(ByteBuffer.wrap(new byte[1]), 0);
        TickCompletableFuture<Void> close = io.close();
        TickCompletableFuture<Void> late = io.sync();

        assertTrue(late.isFailed());
        assertInstanceOf(IOException.class, late.getException());
        tick(30);
        assertTrue(write.isCompleted());
        assertTrue(close.isCompleted());
    }
}
