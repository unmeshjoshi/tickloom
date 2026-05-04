package com.tickloom.io;

import com.tickloom.future.TickCompletableFuture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Real file-backed implementation of {@link FileIO}.
 * <p>
 * Operations complete synchronously — futures resolve immediately.
 * Like FoundationDB's SimpleFile, this is the actual I/O layer
 * that can be wrapped with simulation for testing.
 */
public class LocalFileIO implements FileIO {

    private final FileChannel channel;
    private final String filename;

    private LocalFileIO(FileChannel channel, String filename) {
        this.channel = channel;
        this.filename = filename;
    }

    public static LocalFileIO open(String path) {
        try {
            FileChannel channel = FileChannel.open(
                    Path.of(path),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE
            );
            return new LocalFileIO(channel, path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public TickCompletableFuture<Integer> write(byte[] data, long offset) {
        TickCompletableFuture<Integer> future = new TickCompletableFuture<>();
        try {
            ByteBuffer buf = ByteBuffer.wrap(data);
            int written = channel.write(buf, offset);
            future.complete(written);
        } catch (IOException e) {
            future.fail(e);
        }
        return future;
    }

    @Override
    public TickCompletableFuture<byte[]> read(long offset, int length) {
        TickCompletableFuture<byte[]> future = new TickCompletableFuture<>();
        try {
            int readable = (int) Math.max(0, channel.size() - offset);
            int toRead = Math.min(length, readable);
            ByteBuffer buf = ByteBuffer.allocate(toRead);
            int bytesRead = channel.read(buf, offset);
            if (bytesRead < 0) {
                future.complete(new byte[0]);
            } else {
                buf.flip();
                byte[] result = new byte[buf.remaining()];
                buf.get(result);
                future.complete(result);
            }
        } catch (IOException e) {
            future.fail(e);
        }
        return future;
    }

    @Override
    public TickCompletableFuture<Void> sync() {
        TickCompletableFuture<Void> future = new TickCompletableFuture<>();
        try {
            channel.force(true);
            future.complete(null);
        } catch (IOException e) {
            future.fail(e);
        }
        return future;
    }

    @Override
    public TickCompletableFuture<Void> truncate(long size) {
        TickCompletableFuture<Void> future = new TickCompletableFuture<>();
        try {
            channel.truncate(size);
            future.complete(null);
        } catch (IOException e) {
            future.fail(e);
        }
        return future;
    }

    @Override
    public long size() {
        try {
            return channel.size();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String getFilename() {
        return filename;
    }

    @Override
    public void tick() {
        // No-op — real I/O completes synchronously
    }

    @Override
    public void close() throws Exception {
        channel.close();
    }
}
