package com.tickloom;

import com.tickloom.fs.FileSystem;
import com.tickloom.fs.SimulatedFileSystem;
import com.tickloom.messaging.MessageBus;
import com.tickloom.network.MessageCodec;
import com.tickloom.storage.Storage;
import com.tickloom.util.Clock;
import com.tickloom.util.IdGen;

import java.util.Objects;

/**
 * Everything a {@link Process} is built from. {@code fileSystem} is the process's own files: the files of one
 * process are not visible to another. Whoever owns the loop must tick it, or file calls never complete.
 */
public record ProcessParams(ProcessId id, MessageBus messageBus, MessageCodec messageCodec, int timeoutTicks,
                            Clock clock, IdGen idGenerator, Storage storage, FileSystem fileSystem) {

    public ProcessParams {
        Objects.requireNonNull(fileSystem, "fileSystem");
    }

    /**
     * A process with a new, empty {@link SimulatedFileSystem} of its own. Nothing ticks it unless the caller does,
     * so a process that opens files needs the caller to pass a filesystem it ticks, as {@code Cluster} does.
     */
    public ProcessParams(ProcessId id, MessageBus messageBus, MessageCodec messageCodec, int timeoutTicks,
                         Clock clock, IdGen idGenerator, Storage storage) {
        this(id, messageBus, messageCodec, timeoutTicks, clock, idGenerator, storage, new SimulatedFileSystem());
    }
}
