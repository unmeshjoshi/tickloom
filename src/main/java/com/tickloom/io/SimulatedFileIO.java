package com.tickloom.io;

import com.tickloom.future.TickCompletableFuture;

import java.util.Arrays;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Simulated file I/O for deterministic testing.
 * <p>
 * Maintains two copies of the data:
 * <ul>
 *   <li>{@code volatileData} — page cache, updated on write, visible to reads</li>
 *   <li>{@code durableData} — on-disk state, updated on sync</li>
 * </ul>
 * Calling {@link #crash()} discards unsynced writes by resetting volatile data
 * to the last synced durable state.
 */
public class SimulatedFileIO implements FileIO {

    private byte[] volatileData;
    private byte[] durableData;

    private final String filename;
    private final Random random;
    private final int writeDelayTicks;
    private final int readDelayTicks;
    private final int syncDelayTicks;
    private final double failureRate;

    private final PriorityQueue<PendingOp> pendingOps = new PriorityQueue<>();
    private long currentTick = 0;

    public SimulatedFileIO(String filename, Random random) {
        this(filename, random, 0, 0, 0, 0.0);
    }

    public SimulatedFileIO(String filename, Random random, int writeDelayTicks, int readDelayTicks,
                           int syncDelayTicks, double failureRate) {
        this.filename = filename;
        this.random = random;
        this.writeDelayTicks = writeDelayTicks;
        this.readDelayTicks = readDelayTicks;
        this.syncDelayTicks = syncDelayTicks;
        this.failureRate = failureRate;
        this.volatileData = new byte[0];
        this.durableData = new byte[0];
    }

    @Override
    public TickCompletableFuture<Integer> write(byte[] data, long offset) {
        TickCompletableFuture<Integer> future = new TickCompletableFuture<>();
        pendingOps.offer(new WriteOp(future, completionTick(writeDelayTicks), data, offset));
        return future;
    }

    @Override
    public TickCompletableFuture<byte[]> read(long offset, int length) {
        TickCompletableFuture<byte[]> future = new TickCompletableFuture<>();
        pendingOps.offer(new ReadOp(future, completionTick(readDelayTicks), offset, length));
        return future;
    }

    @Override
    public TickCompletableFuture<Void> sync() {
        TickCompletableFuture<Void> future = new TickCompletableFuture<>();
        pendingOps.offer(new SyncOp(future, completionTick(syncDelayTicks)));
        return future;
    }

    @Override
    public TickCompletableFuture<Void> truncate(long size) {
        TickCompletableFuture<Void> future = new TickCompletableFuture<>();
        pendingOps.offer(new TruncateOp(future, completionTick(writeDelayTicks), size));
        return future;
    }

    @Override
    public long size() {
        return volatileData.length;
    }

    @Override
    public String getFilename() {
        return filename;
    }

    @Override
    public void tick() {
        currentTick++;
        while (!pendingOps.isEmpty() && pendingOps.peek().completionTick <= currentTick) {
            PendingOp op = pendingOps.poll();
            if (failureRate > 0.0 && random.nextDouble() < failureRate) {
                op.fail(new RuntimeException("Simulated I/O failure"));
            } else {
                op.execute();
            }
        }
    }

    /**
     * Simulates a crash — unsynced writes are lost.
     */
    public void crash() {
        volatileData = Arrays.copyOf(durableData, durableData.length);
        pendingOps.clear();
    }

    // -- internal --

    private long completionTick(int delay) {
        return currentTick + delay;
    }

    private void ensureCapacity(long requiredSize) {
        if (requiredSize > volatileData.length) {
            volatileData = Arrays.copyOf(volatileData, (int) requiredSize);
        }
    }

    // -- pending operations --

    private abstract static class PendingOp implements Comparable<PendingOp> {
        final long completionTick;

        PendingOp(long completionTick) {
            this.completionTick = completionTick;
        }

        abstract void execute();
        abstract void fail(RuntimeException e);

        @Override
        public int compareTo(PendingOp other) {
            return Long.compare(this.completionTick, other.completionTick);
        }
    }

    private class WriteOp extends PendingOp {
        final TickCompletableFuture<Integer> future;
        final byte[] data;
        final long offset;

        WriteOp(TickCompletableFuture<Integer> future, long completionTick, byte[] data, long offset) {
            super(completionTick);
            this.future = future;
            this.data = data;
            this.offset = offset;
        }

        @Override
        void execute() {
            ensureCapacity(offset + data.length);
            System.arraycopy(data, 0, volatileData, (int) offset, data.length);
            future.complete(data.length);
        }

        @Override
        void fail(RuntimeException e) {
            future.fail(e);
        }
    }

    private class ReadOp extends PendingOp {
        final TickCompletableFuture<byte[]> future;
        final long offset;
        final int length;

        ReadOp(TickCompletableFuture<byte[]> future, long completionTick, long offset, int length) {
            super(completionTick);
            this.future = future;
            this.offset = offset;
            this.length = length;
        }

        @Override
        void execute() {
            int readLength = Math.min(length, Math.max(0, volatileData.length - (int) offset));
            byte[] result = new byte[readLength];
            if (readLength > 0) {
                System.arraycopy(volatileData, (int) offset, result, 0, readLength);
            }
            future.complete(result);
        }

        @Override
        void fail(RuntimeException e) {
            future.fail(e);
        }
    }

    private class TruncateOp extends PendingOp {
        final TickCompletableFuture<Void> future;
        final long size;

        TruncateOp(TickCompletableFuture<Void> future, long completionTick, long size) {
            super(completionTick);
            this.future = future;
            this.size = size;
        }

        @Override
        void execute() {
            volatileData = Arrays.copyOf(volatileData, (int) size);
            future.complete(null);
        }

        @Override
        void fail(RuntimeException e) {
            future.fail(e);
        }
    }

    private class SyncOp extends PendingOp {
        final TickCompletableFuture<Void> future;

        SyncOp(TickCompletableFuture<Void> future, long completionTick) {
            super(completionTick);
            this.future = future;
        }

        @Override
        void execute() {
            durableData = Arrays.copyOf(volatileData, volatileData.length);
            future.complete(null);
        }

        @Override
        void fail(RuntimeException e) {
            future.fail(e);
        }
    }
}
