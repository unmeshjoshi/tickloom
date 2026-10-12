package com.tickloom.fs;

import com.tickloom.future.TickCompletableFuture;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class TimeBoundedFileIOTest {

    /** An inner file the test drives by hand: calls stay pending until the test completes them. */
    static class ManualFileIO implements TickableFileIO {
        final List<TickCompletableFuture<Object>> issued = new ArrayList<>();
        Runnable onTick = () -> {};

        @SuppressWarnings("unchecked")
        private <T> TickCompletableFuture<T> issue() {
            TickCompletableFuture<Object> future = new TickCompletableFuture<>();
            issued.add(future);
            return (TickCompletableFuture<T>) future;
        }

        @Override public TickCompletableFuture<ByteBuffer> read(long offset, int length) { return issue(); }
        @Override public TickCompletableFuture<Integer> write(ByteBuffer buffer, long offset) { return issue(); }
        @Override public TickCompletableFuture<Void> sync() { return issue(); }
        @Override public TickCompletableFuture<Long> size() { return issue(); }
        @Override public TickCompletableFuture<Void> truncate(long newSize) { return issue(); }
        @Override public TickCompletableFuture<Void> close() { return issue(); }
        @Override public void tick() { onTick.run(); }
    }

    private static final int TIMEOUT_TICKS = 3;
    private final ManualFileIO inner = new ManualFileIO();
    private final TimeBoundedFileIO fileIO = new TimeBoundedFileIO(inner, TIMEOUT_TICKS);

    private void tick(int times) {
        for (int i = 0; i < times; i++) {
            fileIO.tick();
        }
    }

    @Test
    void aCallCompletesWithTheInnerResult() {
        TickCompletableFuture<Integer> write = fileIO.write(ByteBuffer.wrap(new byte[4]), 0);
        assertTrue(write.isPending());

        inner.issued.get(0).complete(4);

        assertEquals(4, write.getResult());
        assertTrue(fileIO.requestWaitingList.isEmpty());
    }

    @Test
    void aFailedInnerCallFailsTheFutureWithTheSameException() {
        TickCompletableFuture<Void> sync = fileIO.sync();
        Exception failure = new java.io.IOException("disk on fire");

        inner.issued.get(0).fail(failure);

        assertTrue(sync.isFailed());
        assertSame(failure, sync.getException());
        assertTrue(fileIO.requestWaitingList.isEmpty());
    }

    @Test
    void aCallAlreadyFailedByTheInnerFileFailsTheFutureAtOnce() {
        // The inner file may reject a call straight away, before track() gets to listen.
        ManualFileIO rejecting = new ManualFileIO() {
            @Override public TickCompletableFuture<Void> sync() {
                TickCompletableFuture<Void> failed = new TickCompletableFuture<>();
                failed.fail(new IllegalStateException("closed"));
                return failed;
            }
        };
        TimeBoundedFileIO bounded = new TimeBoundedFileIO(rejecting, TIMEOUT_TICKS);

        TickCompletableFuture<Void> sync = bounded.sync();

        assertTrue(sync.isFailed());
        assertInstanceOf(IllegalStateException.class, sync.getException());
        assertTrue(bounded.requestWaitingList.isEmpty());
    }

    @Test
    void aCallTimesOutOnTheTickItsDeadlineIsReached() {
        TickCompletableFuture<Integer> write = fileIO.write(ByteBuffer.wrap(new byte[1]), 0);

        tick(TIMEOUT_TICKS - 1);
        assertTrue(write.isPending(), "must not time out before tick " + TIMEOUT_TICKS);

        tick(1);
        assertTrue(write.isFailed());
        assertInstanceOf(TimeoutException.class, write.getException());
        assertTrue(fileIO.requestWaitingList.isEmpty());
    }

    @Test
    void aLateInnerResultAfterTheTimeoutIsDropped() {
        TickCompletableFuture<Integer> write = fileIO.write(ByteBuffer.wrap(new byte[1]), 0);
        tick(TIMEOUT_TICKS);
        assertTrue(write.isFailed());

        // The inner write was never cancelled. When it finishes, the caller's future must not change or throw.
        inner.issued.get(0).complete(1);

        assertTrue(write.isFailed());
        assertInstanceOf(TimeoutException.class, write.getException());
    }

    @Test
    void aCallThatFinishesOnTheDeadlineTickBeatsTheTimeout() {
        TickCompletableFuture<Integer> write = fileIO.write(ByteBuffer.wrap(new byte[1]), 0);
        tick(TIMEOUT_TICKS - 1);

        // The inner file completes the call while it ticks, which happens before the timeouts are checked.
        inner.onTick = () -> inner.issued.get(0).complete(1);
        tick(1);

        assertTrue(write.isCompleted());
        assertEquals(1, write.getResult());
    }

    @Test
    void eachCallHasItsOwnDeadline() {
        TickCompletableFuture<Integer> first = fileIO.write(ByteBuffer.wrap(new byte[1]), 0);
        tick(2);
        TickCompletableFuture<Void> second = fileIO.sync();

        tick(1);   // first reaches its deadline, second has only waited one tick
        assertTrue(first.isFailed());
        assertTrue(second.isPending());

        tick(2);
        assertTrue(second.isFailed());
        assertInstanceOf(TimeoutException.class, second.getException());
    }

    @Test
    void everyOperationIsBounded() {
        List<TickCompletableFuture<?>> calls = List.of(
                fileIO.read(0, 1), fileIO.write(ByteBuffer.wrap(new byte[1]), 0), fileIO.sync(),
                fileIO.size(), fileIO.truncate(0), fileIO.close());

        tick(TIMEOUT_TICKS);

        for (TickCompletableFuture<?> call : calls) {
            assertTrue(call.isFailed());
            assertInstanceOf(TimeoutException.class, call.getException());
        }
    }
}
