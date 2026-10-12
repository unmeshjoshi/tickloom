package com.tickloom.fs;

import com.tickloom.future.TickCompletableFuture;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * The real file I/O: blocking {@link FileChannel} calls run one at a time on a single thread, which gives the
 * in-order guarantee for free. It has no timeouts; wrap it in {@link TimeBoundedFileIO} for those.
 */
public class SingleThreadedFileIO implements TickableFileIO {
    private final FileChannel channel;
    final IOThread ioThread = new IOThread();

    public SingleThreadedFileIO(FileChannel channel) {
        this.channel = channel;
    }

    @Override
    public void tick() {
        ioThread.pollCompletions();
    }

    @Override
    public TickCompletableFuture<ByteBuffer> read(long offset, int length) {
        return ioThread.submit(() -> {
            ByteBuffer buffer = ByteBuffer.allocate(length);
            int total = 0;
            while (total < length) {
                int n = channel.read(buffer, offset + total);
                if (n < 0) {
                    break; // end of file
                }
                total += n;
            }
            buffer.flip();
            return buffer;
        });
    }

    @Override
    public TickCompletableFuture<Integer> write(ByteBuffer buffer, long offset) {
        // Copy now: the I/O thread runs later, and the caller is free to reuse its buffer.
        ByteBuffer data = ByteBuffer.allocate(buffer.remaining());
        data.put(buffer.duplicate());
        data.flip();
        return ioThread.submit(() -> {
            int total = data.remaining();
            while (data.hasRemaining()) {
                channel.write(data, offset + (total - data.remaining()));
            }
            return total;
        });
    }

    @Override
    public TickCompletableFuture<Void> sync() {
        return ioThread.submit(() -> {
            channel.force(true);
            return null;
        });
    }

    @Override
    public TickCompletableFuture<Long> size() {
        return ioThread.submit(channel::size);
    }

    @Override
    public TickCompletableFuture<Void> truncate(long newSize) {
        return ioThread.submit(() -> {
            channel.truncate(newSize);
            return null;
        });
    }

    @Override
    public TickCompletableFuture<Void> close() {
        TickCompletableFuture<Void> closed = ioThread.submit(() -> {
            channel.close();
            return null;
        });
        ioThread.shutdown(); // already queued calls, including the close, still run
        return closed;
    }
}
