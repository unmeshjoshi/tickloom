package com.tickloom.storage.rocksdb.ops;

import com.tickloom.storage.rocksdb.RocksDbStorage;

public abstract class PendingOperation implements Comparable<PendingOperation> {
    public final long completionTick;

    protected PendingOperation(long completionTick) {
        this.completionTick = completionTick;
    }

    public abstract void execute();

    public abstract void fail(RuntimeException exception);

    @Override
    public int compareTo(PendingOperation other) {
        return Long.compare(this.completionTick, other.completionTick);
    }
}
