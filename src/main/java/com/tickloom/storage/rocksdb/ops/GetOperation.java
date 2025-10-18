package com.tickloom.storage.rocksdb.ops;

import com.tickloom.future.ListenableFuture;
import com.tickloom.storage.rocksdb.RocksDbStorage;
import org.rocksdb.RocksDBException;

public class GetOperation extends PendingOperation {
    private final RocksDbStorage rocksDbStorage;
    private final byte[] key;
    private final ListenableFuture<byte[]> future;

    public GetOperation(RocksDbStorage rocksDbStorage, byte[] key, ListenableFuture<byte[]> future, long completionTick) {
        super(completionTick);
        this.rocksDbStorage = rocksDbStorage;
        this.key = key;
        this.future = future;
    }

    @Override
    public void execute() {
        try {
            byte[] value = rocksDbStorage.db.get(key);
            System.out.println("RocksDbStorage: GET operation completed for key " + java.util.Arrays.toString(key));
            future.complete(value);
        } catch (RocksDBException e) {
            future.fail(new RuntimeException("RocksDB GET operation failed", e));
        }
    }

    @Override
    public void fail(RuntimeException exception) {
        future.fail(exception);
    }
}
