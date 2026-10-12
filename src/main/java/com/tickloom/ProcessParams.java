package com.tickloom;

import com.tickloom.fs.FileSystem;
import com.tickloom.messaging.MessageBus;
import com.tickloom.network.MessageCodec;
import com.tickloom.storage.Storage;
import com.tickloom.util.Clock;
import com.tickloom.util.IdGen;

/**
 * Everything a {@link Process} is built from. {@code fileSystem} is the process's own files: the files of one
 * process are not visible to another. It may be null for a process that keeps no files.
 */
public record ProcessParams(ProcessId id, MessageBus messageBus, MessageCodec messageCodec, int timeoutTicks,
                            Clock clock, IdGen idGenerator, Storage storage, FileSystem fileSystem) {

    /** A process without files. */
    public ProcessParams(ProcessId id, MessageBus messageBus, MessageCodec messageCodec, int timeoutTicks,
                         Clock clock, IdGen idGenerator, Storage storage) {
        this(id, messageBus, messageCodec, timeoutTicks, clock, idGenerator, storage, null);
    }
}
