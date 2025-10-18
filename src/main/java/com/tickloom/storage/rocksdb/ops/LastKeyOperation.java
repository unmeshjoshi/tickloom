package com.tickloom.storage.rocksdb.ops;

import com.tickloom.future.ListenableFuture;
import com.tickloom.storage.rocksdb.RocksDbStorage;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksIterator;

public class LastKeyOperation extends PendingOperation {
    private final RocksDbStorage rocksDbStorage;
    private final ListenableFuture<byte[]> future;

    public LastKeyOperation(RocksDbStorage rocksDbStorage, ListenableFuture<byte[]> future, long completionTick) {
        super(completionTick);
        this.rocksDbStorage = rocksDbStorage;
        this.future = future;
    }

    @Override
    public void execute() {
        byte[] lastKey = null;

        ReadOptions readOptions = new ReadOptions();
        try (RocksIterator iterator = rocksDbStorage.db.newIterator(readOptions)) {
            iterator.seekToLast();

            if (iterator.isValid()) {
                lastKey = iterator.key();
            }
        }

        System.out.println("RocksDbStorage: LAST_KEY operation completed - result: " +
                (lastKey != null ? java.util.Arrays.toString(lastKey) : "null"));

        future.complete(lastKey);
    }

    @Override
    public void fail(RuntimeException exception) {
        future.fail(exception);
    }
}
