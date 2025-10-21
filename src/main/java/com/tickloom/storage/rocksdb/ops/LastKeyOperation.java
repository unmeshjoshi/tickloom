package com.tickloom.storage.rocksdb.ops;

import com.tickloom.future.ListenableFuture;
import com.tickloom.storage.rocksdb.RocksDbStorage;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksIterator;


/**
 * When data for multiple tenants is stored in the same RocksDB instance,
 * its generally stored with a prefix for tenant id. For example,
 * CockroachDB, TiKV and DragonBoat use this approach to store write ahead log
 * for multiple key ranges and shards in the same RocksDB instance.
 * RangeID or ShardID is used as the prefix to mark data for each range or shard.
 * Then for each type of data stored for that shard, a different prefix is appended
 * for each type of data,
 * e.g. CockroachDB uses key "rftl" for raft log, "rftr" for RaftReplicaID
 * and "rftt" for RaftTruncatedState.
 * When this is used for WAL, which is truncated when snapshot is stored,
 * we need to make sure to store truncation state which records the log entry upto
 * which the log truncation happened. At start, the lastLogIndex needs
 * to be initialised so that every append can increment log index.
 * For truncated log, as the log can be empty, the index recorded
 * in the truncatedState is used.
 *
 * e.g. cockroachdb
 * // LoadLastEntryID loads the ID of the last entry in the raft log. Returns the
 * // passed in RaftTruncatedState if the log has no entries. RaftTruncatedState
 * // must have been just read, or otherwise exist in memory and be consistent with
 * // the content of the log.
 * func (sl StateLoader) LoadLastEntryID(
 * 	ctx context.Context, reader storage.Reader, ts kvserverpb.RaftTruncatedState,
 * ) (EntryID, error) {
 * 	prefix := sl.RaftLogPrefix()
 * 	// NB: raft log has no intents.
 * 	iter, err := reader.NewMVCCIterator(
 * 		ctx, storage.MVCCKeyIterKind, storage.IterOptions{
 * 			LowerBound: prefix, ReadCategory: fs.ReplicationReadCategory})
 * 	if err != nil {
 * 		return EntryID{}, err
 * 	    }
 * 	defer iter.Close()
 *
 * 	var last EntryID
 * 	iter.SeekLT(storage.MakeMVCCMetadataKey(keys.RaftLogKeyFromPrefix(prefix, math.MaxUint64)))
 * 	if ok, _ := iter.Valid(); ok {
 * 		key := iter.UnsafeKey().Key
 * 		if len(key) < len(prefix) {
 * 			log.KvExec.Fatalf(ctx, "unable to decode Raft log index key: len(%s) < len(%s)", key.String(), prefix.String())
 *        }
 * 		suffix := key[len(prefix):]
 * 		var err error
 * 		last.Index, err = keys.DecodeRaftLogKeyFromSuffix(suffix)
 * 		if err != nil {
 * 			log.KvExec.Fatalf(ctx, "unable to decode Raft log index key: %s; %v", key.String(), err)
 *        }
 * 		v, err := iter.UnsafeValue()
 * 		if err != nil {
 * 			log.KvExec.Fatalf(ctx, "unable to read Raft log entry %d (%s): %v", last.Index, key.String(), err)
 *        }
 * 		entry, err := raftlog.RaftEntryFromRawValue(v)
 * 		if err != nil {
 * 			log.KvExec.Fatalf(ctx, "unable to decode Raft log entry %d (%s): %v", last.Index, key.String(), err)
 *        }
 * 		last.Term = kvpb.RaftTerm(entry.Term)
 *    }
 *
 * 	if last.Index == 0 {
 * 		// The log is empty, which means we are either starting from scratch
 * 		// or the entire log has been truncated away.
 * 		return ts, nil
 *    }
 * 	return last, nil
 * }
 */
public class LastKeyOperation extends PendingOperation {
    private final RocksDbStorage rocksDbStorage;
    private final ListenableFuture<byte[]> future;
    private final byte[] keyUpperBound;

    public LastKeyOperation(RocksDbStorage rocksDbStorage, ListenableFuture<byte[]> future, long completionTick, byte[] keyUpperBound) {
        super(completionTick);
        this.rocksDbStorage = rocksDbStorage;
        this.future = future;
        this.keyUpperBound = keyUpperBound;
    }

    @Override
    public void execute() {
        byte[] lastKey = null;

        ReadOptions readOptions = new ReadOptions();

        try (RocksIterator iterator = rocksDbStorage.db.newIterator(readOptions)) {
            iterator.seekForPrev(keyUpperBound);
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
