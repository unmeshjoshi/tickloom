package com.tickloom.wal;

import com.tickloom.fs.FileIO;
import com.tickloom.future.TickCompletableFuture;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** A file the test drives by hand: it records every call, and each call stays pending until the test completes it. */
class RecordingFileIO implements FileIO {
    record Op(String kind, long offset, byte[] data, TickCompletableFuture<Object> future) {}

    final List<Op> ops = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private <T> TickCompletableFuture<T> record(String kind, long offset, byte[] data) {
        TickCompletableFuture<Object> future = new TickCompletableFuture<>();
        ops.add(new Op(kind, offset, data, future));
        return (TickCompletableFuture<T>) future;
    }

    List<Op> ofKind(String kind) {
        return ops.stream().filter(op -> op.kind().equals(kind)).toList();
    }

    @Override public TickCompletableFuture<ByteBuffer> read(long offset, int length) { return record("read", offset, null); }

    @Override public TickCompletableFuture<Integer> write(ByteBuffer buffer, long offset) {
        byte[] copy = new byte[buffer.remaining()];
        buffer.duplicate().get(copy);
        return record("write", offset, copy);
    }

    @Override public TickCompletableFuture<Void> sync() { return record("sync", -1, null); }
    @Override public TickCompletableFuture<Long> size() { return record("size", -1, null); }
    @Override public TickCompletableFuture<Void> truncate(long newSize) { return record("truncate", newSize, null); }
    @Override public TickCompletableFuture<Void> close() { return record("close", -1, null); }
}
