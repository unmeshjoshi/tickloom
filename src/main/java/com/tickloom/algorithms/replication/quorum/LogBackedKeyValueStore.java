package com.tickloom.algorithms.replication.quorum;

import com.tickloom.fs.FileIO;
import com.tickloom.fs.FileSystem;
import com.tickloom.fs.OpenMode;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.wal.BatchedLog;
import com.tickloom.wal.Wal;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * A key-value store that keeps every key in memory and every write in a log on disk.
 *
 * <ul>
 *   <li>{@code get} reads the map and completes at once.</li>
 *   <li>{@code put} changes the map at once, so later reads and writes in the same process see it, and returns a
 *       future that completes when the write is durable. A caller that tells anyone the write happened must wait for
 *       that future. A crash loses a write whose future had not completed.</li>
 *   <li>{@link #open()} opens the file and rebuilds the map from the log, last write wins. It runs again on a
 *       restart and starts from an empty map each time.</li>
 * </ul>
 *
 * The log only grows. There is no compaction yet. The loop owner must call {@link #tick()} and tick the file system.
 */
public class LogBackedKeyValueStore {
    private final FileSystem fileSystem;
    private final String fileName;
    private final int groupWaitTicks;

    private Map<ByteBuffer, byte[]> map = new HashMap<>();
    private FileIO file;
    private BatchedLog log;

    public LogBackedKeyValueStore(FileSystem fileSystem, String fileName, int groupWaitTicks) {
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

    /** The current value, or null. Includes writes that are not yet durable. */
    public byte[] get(byte[] key) {
        return map.get(ByteBuffer.wrap(key));
    }

    /** Completes when the write is durable. */
    public TickCompletableFuture<Void> put(byte[] key, byte[] value) {
        map.put(ByteBuffer.wrap(key.clone()), value.clone());
        return log.append(encode(key, value)).thenApply(offset -> (Void) null);
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
