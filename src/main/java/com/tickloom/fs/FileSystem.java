package com.tickloom.fs;

import com.tickloom.fs.FileIO;
import com.tickloom.future.TickCompletableFuture;

import java.util.List;

/*
 * FileSystem contract: names. A FileIO is the bytes of one open file; the FileSystem owns which names exist.
 *
 * Namespace
 *   - One flat directory: names only, no subdirectories and no permissions.
 *   - open returns a FileIO. CREATE_NEW fails with FileAlreadyExistsException if the name exists, OPEN_EXISTING fails
 *     with NoSuchFileException if it does not, OPEN_OR_CREATE does neither.
 *   - delete and rename fail with NoSuchFileException if the source name does not exist. rename replaces an existing
 *     target. A file that is open when it is deleted stays usable through its handle.
 *   - list returns the names in sorted order.
 *
 * Durability
 *   - A created, deleted or renamed name is visible at once and durable only after syncDirectory completes.
 *     FileIO.sync on the file does not make its name durable. After a crash a name that was not made durable
 *     may be missing, and a deleted one may be back.
 *   - syncDirectory covers every name change issued before it.
 *
 * Ordering and threading
 *   - Calls on one FileSystem execute and complete in the order issued. Order against calls on a file handle is not
 *     defined.
 *   - Call every method from the loop thread. Futures complete only inside tick().
 *
 * Handles
 *   - The FileSystem owns the handles it hands out and ticks them: tick() on the FileSystem is enough.
 */
public interface FileSystem {
    TickCompletableFuture<FileIO> open(String name, OpenMode mode);

    TickCompletableFuture<Boolean> exists(String name);

    TickCompletableFuture<List<String>> list();

    TickCompletableFuture<Void> delete(String name);

    TickCompletableFuture<Void> rename(String from, String to);

    /** Completes when every name change issued before this call is durable. A failure is fatal. */
    TickCompletableFuture<Void> syncDirectory();
}
