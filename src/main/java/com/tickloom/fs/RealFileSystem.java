package com.tickloom.fs;

import com.tickloom.fs.FileIO;
import com.tickloom.fs.IOThread;
import com.tickloom.fs.SingleThreadedFileIO;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.fs.TickableFileIO;
import com.tickloom.fs.TimeBoundedFileIO;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A directory on disk. Name operations run one at a time on one I/O thread, and each file it opens gets its own
 * {@link SingleThreadedFileIO} inside a {@link TimeBoundedFileIO}. tick() completes the futures of all of them.
 */
public class RealFileSystem implements TickableFileSystem {
    private final Path directory;
    private final long fileTimeoutTicks;
    private final IOThread ioThread = new IOThread();
    private final List<TickableFileIO> handles = new ArrayList<>();

    public RealFileSystem(Path directory, long fileTimeoutTicks) {
        this.directory = directory;
        this.fileTimeoutTicks = fileTimeoutTicks;
    }

    @Override
    public void tick() {
        ioThread.pollCompletions();
        for (TickableFileIO handle : handles) {
            handle.tick();
        }
    }

    @Override
    public TickCompletableFuture<FileIO> open(String name, OpenMode mode) {
        return ioThread.submit(() -> {
            Set<StandardOpenOption> options = EnumSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE);
            switch (mode) {
                case CREATE_NEW -> options.add(StandardOpenOption.CREATE_NEW);
                case OPEN_OR_CREATE -> options.add(StandardOpenOption.CREATE);
                case OPEN_EXISTING -> { }
            }
            FileChannel channel = FileChannel.open(directory.resolve(name), options);
            TickableFileIO handle = new TimeBoundedFileIO(new SingleThreadedFileIO(channel), fileTimeoutTicks);
            return handle;   // registered in thenApply below, on the loop thread
        }).thenApply(handle -> {
            handles.add(handle);
            return (FileIO) handle;
        });
    }

    @Override
    public TickCompletableFuture<Boolean> exists(String name) {
        return ioThread.submit(() -> Files.exists(directory.resolve(name)));
    }

    @Override
    public TickCompletableFuture<List<String>> list() {
        return ioThread.submit(() -> {
            try (var names = Files.list(directory)) {
                return names.map(p -> p.getFileName().toString()).sorted().toList();
            }
        });
    }

    @Override
    public TickCompletableFuture<Void> delete(String name) {
        return ioThread.submit(() -> {
            Files.delete(directory.resolve(name));
            return null;
        });
    }

    @Override
    public TickCompletableFuture<Void> rename(String from, String to) {
        return ioThread.submit(() -> {
            if (!Files.exists(directory.resolve(from))) {
                throw new NoSuchFileException(from);
            }
            Files.move(directory.resolve(from), directory.resolve(to), StandardCopyOption.ATOMIC_MOVE);
            return null;
        });
    }

    @Override
    public TickCompletableFuture<Void> syncDirectory() {
        return ioThread.submit(() -> {
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            } catch (IOException e) {
                throw e;
            }
            return null;
        });
    }

    /** Stops the name I/O thread. Close the files first. */
    public void shutdown() {
        ioThread.shutdown();
    }
}
