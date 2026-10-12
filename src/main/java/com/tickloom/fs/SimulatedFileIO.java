package com.tickloom.fs;

import com.tickloom.future.TickCompletableFuture;
import com.tickloom.fs.TickableFileIO;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Callable;

/**
 * A file in memory, on the tick model. A call is queued when it is made and takes effect, and completes its
 * future, on the next {@code tick()}, in the order issued. The same {@code FileIO} contract as the real file.
 *
 * What it adds over the real one: {@link SimulatedFile} keeps what is durable apart from what is visible, so a
 * crash (see {@link SimulatedFileSystem#crash()}) loses every write that was never synced.
 *
 * After the file is closed, or after a crash, new calls fail with an IOException.
 */
public class SimulatedFileIO implements TickableFileIO {
    private final SimulatedFile file;
    private final Deque<Runnable> queue = new ArrayDeque<>();
    private boolean closed;
    private boolean crashed;

    SimulatedFileIO(SimulatedFile file) {
        this.file = file;
    }

    /** A standalone file with no filesystem around it. */
    public static SimulatedFileIO standalone() {
        return new SimulatedFileIO(new SimulatedFile());
    }

    @Override
    public void tick() {
        // Only the calls queued before this tick: a call made from a callback completes on the next tick.
        for (int due = queue.size(); due > 0; due--) {
            queue.poll().run();
        }
    }

    @Override
    public TickCompletableFuture<ByteBuffer> read(long offset, int length) {
        return submit(() -> ByteBuffer.wrap(file.read(offset, length)));
    }

    @Override
    public TickCompletableFuture<Integer> write(ByteBuffer buffer, long offset) {
        // Copy now: the write takes effect later, and the caller may reuse its buffer.
        byte[] data = new byte[buffer.remaining()];
        buffer.duplicate().get(data);
        return submit(() -> {
            file.write(offset, data);
            return data.length;
        });
    }

    @Override
    public TickCompletableFuture<Void> sync() {
        return submit(() -> {
            file.sync();
            return null;
        });
    }

    @Override
    public TickCompletableFuture<Long> size() {
        return submit(file::size);
    }

    @Override
    public TickCompletableFuture<Void> truncate(long newSize) {
        return submit(() -> {
            file.truncate(newSize);
            return null;
        });
    }

    @Override
    public TickCompletableFuture<Void> close() {
        TickCompletableFuture<Void> result = submit(() -> null);
        closed = true;   // queued behind the earlier calls, which still finish; later calls fail
        return result;
    }

    /** The process died: calls still queued never complete, and the handle is dead. */
    void crash() {
        crashed = true;
        queue.clear();
    }

    private <T> TickCompletableFuture<T> submit(Callable<T> action) {
        TickCompletableFuture<T> future = new TickCompletableFuture<>();
        if (crashed || closed) {
            future.fail(new IOException(crashed ? "file system crashed" : "file is closed"));
            return future;
        }
        queue.add(() -> {
            T result;
            try {
                result = action.call();
            } catch (Throwable e) {
                future.fail(e);
                return;
            }
            future.complete(result);
        });
        return future;
    }
}
