package com.tickloom.wal;

import com.tickloom.fs.FileIO;
import com.tickloom.future.TickCompletableFuture;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Write-ahead log: the mechanism, not the policy.
 *
 * Owns: the record format, the offsets (a record's offset is its position in the file), append, sync,
 * durableOffset and recovery. It never decides when to sync; the caller does ({@code BatchedLog} or a consensus
 * replica). It treats payloads as opaque bytes.
 *
 * Relies on the layer below: calls on one {@link FileIO} execute and complete in the order issued, so one
 * {@code sync} covers every write issued before it, even writes whose futures have not completed yet.
 * It takes a {@code FileIO}, so it cannot tick the file. Whoever owns the loop ticks it, and a Wal on a file
 * nobody drives just hangs.
 *
 * Assumes a sector is written atomically. It does not pad records to sector boundaries.
 *
 * A failed write or sync poisons the log. Recover from disk with a new Wal.
 * Call every method from the loop thread.
 */
public class Wal {
    public record Entry(long offset, byte[] payload) {}

    private enum State {NEW, READY, POISONED}

    private static final int READ_CHUNK = 64 * 1024;

    private final FileIO file;
    private final RecordCodec codec;
    private State state = State.NEW;
    private Throwable poison;
    private long nextOffset;
    private long durableOffset;

    public Wal(FileIO file) {
        this(file, RecordCodec.DEFAULT_MAX_PAYLOAD);
    }

    public Wal(FileIO file, int maxPayload) {
        this.file = file;
        this.codec = new RecordCodec(maxPayload);
    }

    /** Where the next appended record will start. */
    public long nextOffset() {
        return nextOffset;
    }

    /** Everything before this offset is durable. */
    public long durableOffset() {
        return durableOffset;
    }

    public boolean isPoisoned() {
        return state == State.POISONED;
    }

    /**
     * Writes the records back to back and completes with the offset of the first one when the write completes.
     * Complete does not mean durable; only a completed {@link #sync()} does.
     */
    public TickCompletableFuture<Long> append(List<byte[]> payloads) {
        TickCompletableFuture<Long> rejected = rejectUnlessReady();
        if (rejected != null) {
            return rejected;
        }
        byte[] encoded;
        try {
            encoded = codec.encode(payloads);
        } catch (IllegalArgumentException e) {
            return failed(e);   // a bad batch is the caller's mistake and does not poison the log
        }
        // Claim the range now, so back-to-back appends get consecutive offsets whatever order the writes finish in.
        long offset = nextOffset;
        nextOffset = offset + encoded.length;

        TickCompletableFuture<Long> result = new TickCompletableFuture<>();
        file.write(ByteBuffer.wrap(encoded), offset).whenComplete((written, failure) -> {
            if (failure != null) {
                poison(failure);
                result.fail(failure);
            } else {
                result.complete(offset);
            }
        });
        return result;
    }

    /**
     * Makes everything appended before this call durable and completes with the new durable offset.
     * Appends made after the call are not covered.
     */
    public TickCompletableFuture<Long> sync() {
        TickCompletableFuture<Long> rejected = rejectUnlessReady();
        if (rejected != null) {
            return rejected;
        }
        long covered = nextOffset;
        TickCompletableFuture<Long> result = new TickCompletableFuture<>();
        file.sync().whenComplete((ignored, failure) -> {
            if (failure != null) {
                poison(failure);
                result.fail(failure);
            } else {
                durableOffset = Math.max(durableOffset, covered);
                result.complete(durableOffset);
            }
        });
        return result;
    }

    /**
     * Reads the file from the start and returns the valid records in order. The first invalid or incomplete record
     * ends the log. Sets nextOffset and durableOffset to the end of the last valid record, then cuts off whatever
     * follows (a torn record, or records that survived a crash after an earlier one was lost) and syncs, so a later
     * append can never join up with old bytes and bring back a record that was never acknowledged.
     */
    public TickCompletableFuture<List<Entry>> recover() {
        if (state != State.NEW) {
            return failed(new IllegalStateException("recover() is only allowed on a new Wal, state is " + state));
        }
        TickCompletableFuture<List<Entry>> result = new TickCompletableFuture<>();
        readAll(new byte[0], 0, result);
        return result;
    }

    // Reads the file in chunks until a short read, then decodes. The whole log is held in memory for now.
    private void readAll(byte[] soFar, long position, TickCompletableFuture<List<Entry>> result) {
        file.read(position, READ_CHUNK).whenComplete((chunk, failure) -> {
            if (failure != null) {
                poison(failure);
                result.fail(failure);
                return;
            }
            byte[] all = new byte[soFar.length + chunk.remaining()];
            System.arraycopy(soFar, 0, all, 0, soFar.length);
            chunk.get(all, soFar.length, chunk.remaining());
            if (all.length - soFar.length < READ_CHUNK) {
                finishRecovery(all, result);
            } else {
                readAll(all, position + READ_CHUNK, result);
            }
        });
    }

    private void finishRecovery(byte[] bytes, TickCompletableFuture<List<Entry>> result) {
        List<Entry> entries = decodeAll(bytes);
        if (nextOffset == bytes.length) {
            state = State.READY;
            result.complete(entries);
            return;
        }
        // Not READY yet: nothing may be appended until the tail is gone and the cut is durable.
        file.truncate(nextOffset).whenComplete((ignored, truncateFailure) -> {
            if (truncateFailure != null) {
                poison(truncateFailure);
                result.fail(truncateFailure);
                return;
            }
            file.sync().whenComplete((synced, syncFailure) -> {
                if (syncFailure != null) {
                    poison(syncFailure);
                    result.fail(syncFailure);
                    return;
                }
                state = State.READY;
                result.complete(entries);
            });
        });
    }

    private List<Entry> decodeAll(byte[] bytes) {
        List<Entry> entries = new ArrayList<>();
        int position = 0;
        while (true) {
            RecordCodec.Decoded decoded = codec.decode(bytes, position, bytes.length);
            if (decoded.status() != RecordCodec.Status.OK) {
                break;
            }
            entries.add(new Entry(position, decoded.payload()));
            position += decoded.size();
        }
        nextOffset = position;
        durableOffset = position;
        return entries;
    }

    private <T> TickCompletableFuture<T> rejectUnlessReady() {
        return switch (state) {
            case READY -> null;
            case NEW -> failed(new IllegalStateException("call recover() first"));
            case POISONED -> failed(new IllegalStateException("log is poisoned", poison));
        };
    }

    private void poison(Throwable cause) {
        if (state != State.POISONED) {
            state = State.POISONED;
            poison = cause;
        }
    }

    private static <T> TickCompletableFuture<T> failed(Throwable error) {
        TickCompletableFuture<T> future = new TickCompletableFuture<>();
        future.fail(error);
        return future;
    }
}
