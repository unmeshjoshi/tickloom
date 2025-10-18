package com.tickloom.storage.rocksdb.ops;

import com.tickloom.future.ListenableFuture;
import com.tickloom.storage.rocksdb.RocksDbStorage;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksIterator;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class ReadRangeOperation extends PendingOperation {
    private final RocksDbStorage rocksDbStorage;
    private final byte[] startKey;
    private final byte[] endKey;
    private final ListenableFuture<Map<byte[], byte[]>> future;

    public ReadRangeOperation(RocksDbStorage rocksDbStorage, byte[] startKey, byte[] endKey, ListenableFuture<Map<byte[], byte[]>> future,
                              long completionTick) {
        super(completionTick);
        this.rocksDbStorage = rocksDbStorage;
        this.startKey = startKey;
        this.endKey = endKey;
        this.future = future;
    }

    @Override
    public void execute() {
        Map<byte[], byte[]> result = new HashMap<>();

        ReadOptions readOptions = new ReadOptions();
        try (RocksIterator iterator = rocksDbStorage.db.newIterator(readOptions)) {
            iterator.seek(startKey);

            while (iterator.isValid()) {
                byte[] key = iterator.key();
                if (Arrays.compare(key, endKey) >= 0) {
                    break; // Reached or exceeded end key
                }

                result.put(key, iterator.value());
                iterator.next();
            }
        }

        System.out.println("RocksDbStorage: RANGE operation completed - start: " + Arrays.toString(startKey) +
                ", end: " + Arrays.toString(endKey) + ", count: " + result.size());

        future.complete(result);
    }

    @Override
    public void fail(RuntimeException exception) {
        future.fail(exception);
    }
}
