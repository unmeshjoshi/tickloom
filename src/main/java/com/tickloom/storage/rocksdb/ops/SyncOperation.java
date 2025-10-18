package com.tickloom.storage.rocksdb.ops;

import com.tickloom.future.ListenableFuture;
import com.tickloom.storage.rocksdb.RocksDbStorage;
import org.rocksdb.RocksDBException;

public class SyncOperation extends PendingOperation {
    private final RocksDbStorage rocksDbStorage;
    private final ListenableFuture<Void> future;

    public SyncOperation(RocksDbStorage rocksDbStorage, ListenableFuture<Void> future, long completionTick) {
        super(completionTick);
        this.rocksDbStorage = rocksDbStorage;
        this.future = future;
    }

    @Override
    public void execute() {
        try {
            rocksDbStorage.db.syncWal();
            System.out.println("RocksDbStorage: SYNC operation completed");
            future.complete(null);
        } catch (RocksDBException e) {
            future.fail(new RuntimeException("RocksDB SYNC operation failed", e));
        }
    }

    @Override
    public void fail(RuntimeException exception) {
        future.fail(exception);
    }
}
