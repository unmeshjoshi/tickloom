package com.tickloom.fs;

import com.tickloom.ProcessId;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.messaging.RequestCallback;
import com.tickloom.messaging.RequestWaitingList;
import java.nio.ByteBuffer;

/**
 * Bounds how long a caller waits: if the inner call has not completed within the timeout, the returned future fails
 * with TimeoutException. The inner operation is NOT cancelled and may still take effect later.
 */
public class TimeBoundedFileIO implements TickableFileIO {
    // Timeouts are counted in ticks, like TigerBeetle's. One tick is meant to be about 1 ms, so 1_000 is ~1 s.
    // A healthy disk answers in well under 10 ms, and databases such as etcd already treat an fsync slower than about
    // 1 s as a sick disk. A timeout here means "treat the disk as failed", so it must be shorter than the layer above's
    // own failure detection (for example a leader election timeout), or a node with a stalled disk keeps leadership.
    static final long DEFAULT_TIMEOUT_TICKS = 1_000;

    private final TickableFileIO inner;
    // Calls waiting for the inner file, each with its own tick-based timeout. Loop thread only.
    final RequestWaitingList<Long, Object> requestWaitingList;
    private long nextId = 0;

    public TimeBoundedFileIO(TickableFileIO inner) {
        this(inner, DEFAULT_TIMEOUT_TICKS);
    }

    public TimeBoundedFileIO(TickableFileIO inner, long timeoutTicks) {
        this.inner = inner;
        this.requestWaitingList = new RequestWaitingList<>(timeoutTicks);
    }

    @Override
    public void tick() {
        inner.tick();                 // inner completions first, so a finished call beats a timeout in the same tick
        requestWaitingList.tick();    // then expire whatever is still waiting
    }

    @Override
    public TickCompletableFuture<ByteBuffer> read(long offset, int length) {
        return track(inner.read(offset, length));
    }

    @Override
    public TickCompletableFuture<Integer> write(ByteBuffer buffer, long offset) {
        return track(inner.write(buffer, offset));
    }

    @Override
    public TickCompletableFuture<Void> sync() {
        return track(inner.sync());
    }

    @Override
    public TickCompletableFuture<Long> size() {
        return track(inner.size());
    }

    @Override
    public TickCompletableFuture<Void> truncate(long newSize) {
        return track(inner.truncate(newSize));
    }

    @Override
    public TickCompletableFuture<Void> close() {
        return track(inner.close());
    }

    /** The returned future completes from whichever comes first: the inner result or the timeout. */
    private <T> TickCompletableFuture<T> track(TickCompletableFuture<T> innerFuture) {
        TickCompletableFuture<T> outer = new TickCompletableFuture<>();
        long id = nextId++;
        // Register before listening, so an inner future that has already failed still finds its entry.
        requestWaitingList.add(id, completing(outer));
        innerFuture.whenComplete((result, error) -> {
            if (error != null) {
                requestWaitingList.handleError(id, error instanceof Exception e ? e : new RuntimeException(error));
            } else {
                // No entry means the call already timed out, so the late result is dropped.
                requestWaitingList.handleResponse(id, result);
            }
        });
        return outer;
    }

    // The result always comes from the inner future paired with this one in track, so the cast is safe.
    @SuppressWarnings("unchecked")
    private static <T> RequestCallback<Object> completing(TickCompletableFuture<T> future) {
        return new RequestCallback<>() {
            @Override
            public void onResponse(Object result, ProcessId fromNode) {
                future.complete((T) result);
            }

            @Override
            public void onError(Exception error) {
                future.fail(error);
            }
        };
    }
}
