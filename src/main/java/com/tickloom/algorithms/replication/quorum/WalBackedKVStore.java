package com.tickloom.algorithms.replication.quorum;

import com.tickloom.fs.FileIO;
import com.tickloom.fs.FileSystem;
import com.tickloom.fs.OpenMode;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.wal.BatchedLog;
import com.tickloom.wal.Wal;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A key-value store that keeps every key in memory and every write in a log on disk.
 *
 * <ul>
 *   <li>The map changes only after the write is durable. {@code get} reads the map, so it never returns a value
 *       that a crash could take back.</li>
 *   <li>{@code put} appends to the log and returns a future that completes once the record is durable and the map
 *       is updated. Writes become durable, and are applied, in the order they were put.</li>
 *   <li>{@link #getLatest(byte[])} also sees writes that are not durable yet. It is for a caller that must decide
 *       about a new write by comparing it with every write already accepted, such as a last-write-wins check, so
 *       two concurrent writes to one key are not both judged against the old value. Do not use it to answer
 *       reads.</li>
 *   <li>{@link #open()} opens the file and rebuilds the map from the log, last write wins. It runs again on a
 *       restart and starts from an empty map each time.</li>
 * </ul>
 *
 * The log only grows. There is no compaction yet. The loop owner must call {@link #tick()} and tick the file system.
 */
public class WalBackedKVStore {
    private final FileSystem fileSystem;
    private final String fileName;
    private final int groupWaitTicks;

    private static final class Pending {
        final byte[] value;
        final long sequence;
        final List<TickCompletableFuture<Void>> waiters = new ArrayList<>();

        Pending(byte[] value, long sequence) {
            this.value = value;
            this.sequence = sequence;
        }
    }

    private Map<ByteBuffer, byte[]> map = new HashMap<>();
    // Writes in the log pipeline but not yet durable, newest per key. Only for getLatest.
    private final Map<ByteBuffer, Pending> pending = new HashMap<>();
    private long nextSequence;
    private FileIO file;
    private BatchedLog log;
    private int writesNotYetDurable;

    public WalBackedKVStore(FileSystem fileSystem, String fileName, int groupWaitTicks) {
        this.fileSystem = fileSystem;
        this.fileName = fileName;
        this.groupWaitTicks = groupWaitTicks;
    }

    /** Completes when the map is rebuilt from the log. Nothing else may be called before that. */
    public TickCompletableFuture<Void> open() {
        if (file != null) {
            file.close();   // a restart on the same instance: let go of the old handle
        }
        map = new HashMap<>();
        log = null;
        writesNotYetDurable = 0;
        pending.clear();
        return fileSystem.open(fileName, OpenMode.OPEN_OR_CREATE)
                // A new file's name is durable only after the directory is synced.
                .thenCompose(opened -> fileSystem.syncDirectory().thenApply(ignored -> opened))
                .thenCompose(opened -> {
                    file = opened;
                    Wal wal = new Wal(opened);
                    return wal.recover().thenApply(entries -> {
                        entries.forEach(entry -> replay(entry.payload()));
                        log = new BatchedLog(wal, groupWaitTicks);
                        return (Void) null;
                    });
                });
    }

    /** The durable value, or null. */
    public byte[] get(byte[] key) {
        return map.get(ByteBuffer.wrap(key));
    }

    /** The newest accepted value, durable or not, or null. For deciding about a new write, not for reads. */
    public byte[] getLatest(byte[] key) {
        Pending inFlight = pending.get(ByteBuffer.wrap(key));
        return inFlight != null ? inFlight.value : get(key);
    }

    /**
     * Completes when the newest accepted write for the key is durable: at once if nothing is in flight, and with
     * the failure if that write fails. A caller that skips a write because a newer one is already accepted waits
     * on this before it reports success, so it never reports a write as safe that a crash could still take back.
     */
    public TickCompletableFuture<Void> whenLatestDurable(byte[] key) {
        Pending inFlight = pending.get(ByteBuffer.wrap(key));
        if (inFlight == null) {
            return TickCompletableFuture.completed(null);
        }
        TickCompletableFuture<Void> waiter = new TickCompletableFuture<>();
        inFlight.waiters.add(waiter);
        return waiter;
    }

    /** Completes when the write is durable and in the map. */
    public TickCompletableFuture<Void> put(byte[] key, byte[] value) {
        ByteBuffer mapKey = ByteBuffer.wrap(key.clone());
        byte[] mapValue = value.clone();
        long sequence = nextSequence++;
        Pending entry = new Pending(mapValue, sequence);
        pending.put(mapKey, entry);
        writesNotYetDurable++;
        TickCompletableFuture<Void> result = new TickCompletableFuture<>();
        log.append(encode(key, value)).whenComplete((offset, error) -> {
            writesNotYetDurable--;
            Pending latest = pending.get(mapKey);
            if (latest == entry) {
                pending.remove(mapKey);   // a newer put to the same key keeps its own entry
            }
            if (error == null) {
                map.put(mapKey, mapValue);
            }
            for (TickCompletableFuture<Void> waiter : entry.waiters) {
                if (error == null) waiter.complete(null);
                else waiter.fail(error);
            }
            if (error == null) result.complete(null);
            else result.fail(error);
        });
        return result;
    }

    /** True while some write has been put but is not yet durable. */
    public boolean hasWritesNotYetDurable() {
        return writesNotYetDurable > 0;
    }

    public void tick() {
        if (log != null) {
            log.tick();
        }
    }

    // [keyLength: 4][key][value]. The header keeps the payload non-empty even for an empty key and value.
    private static byte[] encode(byte[] key, byte[] value) {
        return ByteBuffer.allocate(4 + key.length + value.length).putInt(key.length).put(key).put(value).array();
    }

    private void replay(byte[] payload) {
        ByteBuffer in = ByteBuffer.wrap(payload);
        byte[] key = new byte[in.getInt()];
        in.get(key);
        byte[] value = new byte[in.remaining()];
        in.get(value);
        map.put(ByteBuffer.wrap(key), value);
    }
}
