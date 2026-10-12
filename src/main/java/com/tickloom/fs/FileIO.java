package com.tickloom.fs;

import com.tickloom.future.TickCompletableFuture;
import java.nio.ByteBuffer;

/*
 * FileIO contract
 *
 * Durability
 *   - write completes when the bytes are visible to reads. They are not
 *     necessarily durable.
 *   - sync completes when everything written before it is durable. It covers
 *     every write issued before it, because ops on a file execute and complete
 *     in submission order.
 *   - Don't assume anything about durability from a write future or from a
 *     timeout. Only a completed sync tells you the data is durable.
 *   - A failed sync is fatal. Don't retry it.
 *   - truncate is a change like any write: it is visible at once and durable only after a completed sync.
 *
 * Buffers and sizes
 *   - write copies the bytes between the buffer's position and limit when it is called. The caller's buffer is not
 *     modified and may be reused at once.
 *   - write completes with the full byte count, or fails. It never completes with a short count.
 *   - read completes with a buffer holding only the bytes actually read: shorter than asked at the end of the file,
 *     empty at or past it.
 *   - truncate only shrinks. A size at or above the current size changes nothing.
 *
 * Lifecycle
 *   - close is queued behind the calls already issued, so they finish first. Calls issued after close fail.
 *
 * Ordering
 *   - Calls on one instance execute and complete in the order they were issued.
 *
 * Not guaranteed
 *   - Order across different files or different instances.
 *   - A timed-out operation is not cancelled. It may still execute, and a later sync will cover it.
 *   - The order in which unsynced writes reach the disk after a crash.
 *
 * Caller rule
 *   - Don't issue an op that depends on another before its future completes, unless you rely on the order above.
 */
public interface FileIO {

    /**
     * Reads up to length bytes at offset. The buffer is ready to consume and holds only the bytes actually read:
     * shorter than length at the end of the file, empty at or past it.
     */
    TickCompletableFuture<ByteBuffer> read(long offset, int length);

    TickCompletableFuture<Integer> write(ByteBuffer buffer, long offset);

    /** Completes when everything written before this call is durable. A failure is fatal; do not retry. */
    TickCompletableFuture<Void> sync();

    TickCompletableFuture<Long> size();

    /** Shrinks the file to newSize. Does nothing if newSize is not smaller than the current size. */
    TickCompletableFuture<Void> truncate(long newSize);

    /** Closes the file after the calls issued before it. Calls issued afterwards fail. */
    TickCompletableFuture<Void> close();
}
