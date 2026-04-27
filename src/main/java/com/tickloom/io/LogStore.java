package com.tickloom.io;

import com.tickloom.future.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only WAL built on top of {@link FileIO}.
 * <p>
 * Entry format on disk: [4 bytes length][payload bytes]
 * <p>
 * Writes are async (delegated to FileIO). Reads are from an in-memory
 * index that tracks each entry's offset — no disk read needed during
 * normal operation.
 */
public class LogStore {

    private static final int LENGTH_PREFIX_SIZE = 4;

    private final FileIO fileIO;
    private final List<byte[]> entries = new ArrayList<>();
    private long writeOffset = 0;

    public LogStore(FileIO fileIO) {
        this.fileIO = fileIO;
    }

    /**
     * Recovers a LogStore by scanning the file from the beginning.
     * Reads length-prefixed entries sequentially until EOF or incomplete frame.
     */
    public static LogStore recover(FileIO fileIO) {
        LogStore logStore = new LogStore(fileIO);
        long offset = 0;
        long fileSize = fileIO.size();

        while (offset + LENGTH_PREFIX_SIZE <= fileSize) {
            // read length prefix
            ListenableFuture<byte[]> lenFuture = fileIO.read(offset, LENGTH_PREFIX_SIZE);
            fileIO.tick();
            if (!lenFuture.isCompleted()) break;

            int entryLength = ByteBuffer.wrap(lenFuture.getResult()).getInt();
            if (entryLength <= 0 || offset + LENGTH_PREFIX_SIZE + entryLength > fileSize) {
                break; // incomplete or corrupt entry
            }

            // read payload
            ListenableFuture<byte[]> entryFuture = fileIO.read(offset + LENGTH_PREFIX_SIZE, entryLength);
            fileIO.tick();
            if (!entryFuture.isCompleted()) break;

            logStore.entries.add(entryFuture.getResult());
            offset += LENGTH_PREFIX_SIZE + entryLength;
        }

        logStore.writeOffset = offset;
        return logStore;
    }

    /**
     * Appends an entry to the log.
     *
     * @return future containing the 1-based index of the appended entry
     */
    public ListenableFuture<Long> append(byte[] entry) {
        byte[] frame = frame(entry);
        long entryOffset = writeOffset;

        writeOffset += frame.length;
        entries.add(entry);
        long entryIndex = entries.size();

        return fileIO.write(frame, entryOffset).thenApply(bytesWritten -> entryIndex);
    }

    /**
     * Reads an entry by 1-based index. Returns null if index is out of range.
     * Reads from in-memory cache — no disk I/O.
     */
    public byte[] read(long index) {
        if (index < 1 || index > this.entries.size()) {
            return null;
        }
        return entries.get((int) (index - 1));
    }

    /**
     * Index of the last appended entry, or 0 if empty.
     */
    public long lastIndex() {
        return entries.size();
    }

    /**
     * Flushes all written entries to durable storage.
     */
    public ListenableFuture<Void> sync() {
        return fileIO.sync();
    }

    private byte[] frame(byte[] entry) {
        ByteBuffer buf = ByteBuffer.allocate(LENGTH_PREFIX_SIZE + entry.length);
        buf.putInt(entry.length);
        buf.put(entry);
        return buf.array();
    }

}
