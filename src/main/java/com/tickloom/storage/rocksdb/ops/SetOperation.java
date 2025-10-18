package com.tickloom.storage.rocksdb.ops;

import com.tickloom.future.ListenableFuture;
import com.tickloom.storage.Storage;
import com.tickloom.storage.rocksdb.RocksDbStorage;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteOptions;

public class SetOperation extends PendingOperation {
    private final RocksDbStorage rocksDbStorage;
    private final byte[] key;
    private final byte[] value;
    private final ListenableFuture<Boolean> future;
    private final Storage.WriteOptions options;

    public SetOperation(RocksDbStorage rocksDbStorage, byte[] key, byte[] value, ListenableFuture<Boolean> future,
                        long completionTick, Storage.WriteOptions options) {
        super(completionTick);
        this.rocksDbStorage = rocksDbStorage;
        this.key = key;
        this.value = value;
        this.future = future;
        this.options = options;
    }

    @Override
    public void execute() {
        try (WriteOptions writeOptions = createRockDbWriteOptions()) {

            rocksDbStorage.db.put(writeOptions, key, value);
            System.out.println("RocksDbStorage: SET operation completed for key " + java.util.Arrays.toString(key) +
                    " with options: sync=" + options.sync() + ", disableWAL=" + options.disableWAL());
            future.complete(true);

        } catch (RocksDBException e) {
            future.fail(new RuntimeException("RocksDB SET operation failed", e));
        }
    }

    private WriteOptions createRockDbWriteOptions() {
        WriteOptions writeOptions = new WriteOptions();
        writeOptions.setSync(options.sync());
        writeOptions.setDisableWAL(options.disableWAL());
        return writeOptions;
    }

    @Override
    public void fail(RuntimeException exception) {
        future.fail(exception);
    }
}
