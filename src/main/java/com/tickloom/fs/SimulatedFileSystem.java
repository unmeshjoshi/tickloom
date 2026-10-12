package com.tickloom.fs;

import com.tickloom.fs.FileIO;
import com.tickloom.future.TickCompletableFuture;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/**
 * A flat directory in memory, on the tick model.
 *
 * Names: {@code names} is what calls see, {@code durableNames} is what is on disk. A create, delete or rename
 * changes only {@code names}; {@link #syncDirectory()} copies it to {@code durableNames}.
 *
 * {@link #crash()} is a power cut: calls still queued never complete, every open handle dies, the names go back
 * to {@code durableNames} and every file goes back to its last synced bytes. {@link #restart()} lets the process
 * open its files again.
 */
public class SimulatedFileSystem implements TickableFileSystem {
    private final Deque<Runnable> queue = new ArrayDeque<>();
    private final List<SimulatedFileIO> handles = new ArrayList<>();
    private TreeMap<String, SimulatedFile> names = new TreeMap<>();
    private TreeMap<String, SimulatedFile> durableNames = new TreeMap<>();
    private boolean crashed;

    @Override
    public void tick() {
        // Only the calls queued before this tick: a call made from a callback completes on the next tick.
        for (int due = queue.size(); due > 0; due--) {
            queue.poll().run();
        }
        for (SimulatedFileIO handle : handles) {
            handle.tick();
        }
    }

    @Override
    public TickCompletableFuture<FileIO> open(String name, OpenMode mode) {
        return submit(() -> {
            SimulatedFile file = names.get(name);
            switch (mode) {
                case CREATE_NEW -> {
                    if (file != null) throw new FileAlreadyExistsException(name);
                }
                case OPEN_EXISTING -> {
                    if (file == null) throw new NoSuchFileException(name);
                }
                case OPEN_OR_CREATE -> { }
            }
            if (file == null) {
                file = new SimulatedFile();
                names.put(name, file);
            }
            SimulatedFileIO handle = new SimulatedFileIO(file);
            handles.add(handle);
            return (FileIO) handle;
        });
    }

    @Override
    public TickCompletableFuture<Boolean> exists(String name) {
        return submit(() -> names.containsKey(name));
    }

    @Override
    public TickCompletableFuture<List<String>> list() {
        return submit(() -> List.copyOf(names.keySet()));
    }

    @Override
    public TickCompletableFuture<Void> delete(String name) {
        return submit(() -> {
            if (names.remove(name) == null) throw new NoSuchFileException(name);
            return null;
        });
    }

    @Override
    public TickCompletableFuture<Void> rename(String from, String to) {
        return submit(() -> {
            SimulatedFile file = names.remove(from);
            if (file == null) throw new NoSuchFileException(from);
            names.put(to, file);
            return null;
        });
    }

    @Override
    public TickCompletableFuture<Void> syncDirectory() {
        return submit(() -> {
            durableNames = new TreeMap<>(names);
            return null;
        });
    }

    /** True when no name operation is queued. File handles have their own queues. */
    public boolean isIdle() {
        return queue.isEmpty();
    }

    /** Power cut. Open handles die, queued calls never complete, and the disk is what was last synced. */
    public void crash() {
        for (SimulatedFileIO handle : handles) {
            handle.crash();
        }
        handles.clear();
        queue.clear();
        names = new TreeMap<>(durableNames);
        Map<SimulatedFile, Boolean> seen = new IdentityHashMap<>();
        for (SimulatedFile file : names.values()) {
            if (seen.put(file, true) == null) {
                file.crash();
            }
        }
        crashed = true;
    }

    /** The process comes back. It must open its files again. */
    public void restart() {
        crashed = false;
    }

    private <T> TickCompletableFuture<T> submit(Callable<T> action) {
        TickCompletableFuture<T> future = new TickCompletableFuture<>();
        if (crashed) {
            future.fail(new IOException("file system crashed"));
            return future;
        }
        queue.add(() -> {
            T result;
            try {
                result = action.call();
            } catch (Throwable e) {
                future.fail(e);
                return;
            }
            future.complete(result);
        });
        return future;
    }
}
