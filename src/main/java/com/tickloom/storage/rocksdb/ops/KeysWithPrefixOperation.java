package com.tickloom.storage.rocksdb.ops;

import com.tickloom.future.ListenableFuture;
import com.tickloom.storage.rocksdb.RocksDbStorage;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksIterator;

import java.util.ArrayList;
import java.util.List;

public class KeysWithPrefixOperation extends PendingOperation {
    private final RocksDbStorage rocksDbStorage;
    private final byte[] prefix;
    private final ListenableFuture<List<byte[]>> future;

    public KeysWithPrefixOperation(RocksDbStorage rocksDbStorage, byte[] prefix, ListenableFuture<List<byte[]>> future, long completionTick) {
        super(completionTick);
        this.rocksDbStorage = rocksDbStorage;
        this.prefix = prefix;
        this.future = future;
    }

    @Override
    public void execute() {
        List<byte[]> result = new ArrayList<>();

        ReadOptions readOptions = new ReadOptions();
        try (RocksIterator iterator = rocksDbStorage.db.newIterator(readOptions)) {
            iterator.seek(prefix);

            while (iterator.isValid()) {
                byte[] key = iterator.key();
                if (startsWith(key, prefix)) {
                    result.add(key);
                } else {
                    break; // No more keys with this prefix
                }
                iterator.next();
            }
        }

        System.out.println("RocksDbStorage: KEYS_WITH_PREFIX operation completed - prefix: " + java.util.Arrays.toString(prefix) +
                ", count: " + result.size());

        future.complete(result);
    }

    @Override
    public void fail(RuntimeException exception) {
        future.fail(exception);
    }

    private boolean startsWith(byte[] array, byte[] prefix) {
        if (array.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (array[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
