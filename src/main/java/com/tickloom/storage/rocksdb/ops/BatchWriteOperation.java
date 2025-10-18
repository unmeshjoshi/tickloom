package com.tickloom.storage.rocksdb.ops;

import com.tickloom.future.ListenableFuture;
import com.tickloom.storage.Storage;
import com.tickloom.storage.WriteBatch;
import com.tickloom.storage.rocksdb.RocksDbStorage;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteOptions;

import java.util.Map;

public class BatchWriteOperation extends PendingOperation {
    private final RocksDbStorage rocksDbStorage;
    private final WriteBatch writeBatch;
    private final ListenableFuture<Boolean> future;
    private final Storage.WriteOptions options;

    public BatchWriteOperation(RocksDbStorage rocksDbStorage, WriteBatch writeBatch, ListenableFuture<Boolean> future,
                               long completionTick, Storage.WriteOptions options) {
        super(completionTick);
        this.rocksDbStorage = rocksDbStorage;
        this.writeBatch = writeBatch;
        this.future = future;
        this.options = options;
    }

    @Override
    public void execute() {
        try (WriteOptions writeOptions = createRockDbWriteOptions(options);
             org.rocksdb.WriteBatch batch = createRockDbBatch(writeBatch)) {

            rocksDbStorage.db.write(writeOptions, batch);

            batch.close();

            System.out.println("RocksDbStorage: BATCH operation completed with " + writeBatch.size() + " operations");
            future.complete(true);

        } catch (RocksDBException e) {
            future.fail(new RuntimeException("RocksDB BATCH operation failed", e));
        }
    }

    private org.rocksdb.WriteBatch createRockDbBatch(WriteBatch writeBatch) throws RocksDBException {
        org.rocksdb.WriteBatch batch = new org.rocksdb.WriteBatch();
        // Process all key-value pairs in the batch
        Map<byte[], byte[]> operations = writeBatch.getOperations();
        for (Map.Entry<byte[], byte[]> entry : operations.entrySet()) {
            byte[] key = entry.getKey();
            byte[] value = entry.getValue();

            if (value == null) {
                // Delete operation
                batch.delete(key);
            } else {
                // Put operation
                batch.put(key, value);
            }
        }
        return batch;
    }

    private WriteOptions createRockDbWriteOptions(Storage.WriteOptions options) {
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
