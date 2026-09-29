package com.tickloom.storage.rocksdb.ops;

import com.tickloom.storage.rocksdb.RocksDbStorage;

public abstract class PendingOperation implements Comparable<PendingOperation> {
    public final long completionTick;
    private long sequenceNumber;

    protected PendingOperation(long completionTick) {
        this.completionTick = completionTick;
    }

    /**
     * Assigned by the storage when the operation is queued; breaks ties between
     * operations due in the same tick so they run in issue order.
     */
    public void assignSequenceNumber(long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public abstract void execute();

    public abstract void fail(RuntimeException exception);

    @Override
    public int compareTo(PendingOperation other) {
        int tickComparison = Long.compare(this.completionTick, other.completionTick);
        if (tickComparison != 0) {
            return tickComparison;
        }
        return Long.compare(this.sequenceNumber, other.sequenceNumber);
    }
}
