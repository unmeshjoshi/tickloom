package com.tickloom.io;

import com.tickloom.future.TickCompletableFuture;

import java.nio.file.NoSuchFileException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;

/**
 * Simulated filesystem for deterministic testing.
 * <p>
 * Keeps two views of the namespace:
 * <ul>
 *   <li>{@code visibleFiles} — what open() sees; updated immediately on create and rename</li>
 *   <li>{@code durableFiles} — what survives a crash; updated on syncDirectory()</li>
 * </ul>
 * File contents are {@link SimulatedFileIO}s, which apply the same volatile/durable split to data.
 * Calling {@link #crash()} resets the namespace and every file to their durable state.
 */
public class SimulatedFileSystem implements FileSystem {

    private final Random random;

    private Map<String, SimulatedFileIO> visibleFiles = new LinkedHashMap<>();
    private final Map<String, SimulatedFileIO> durableFiles = new LinkedHashMap<>();

    // Namespace operations complete on the next tick, in issue order
    private final Queue<Runnable> pendingOps = new ArrayDeque<>();

    public SimulatedFileSystem(Random random) {
        this.random = random;
    }

    @Override
    public TickCompletableFuture<FileIO> open(String path, boolean create) {
        TickCompletableFuture<FileIO> future = new TickCompletableFuture<>();
        pendingOps.add(() -> {
            SimulatedFileIO file = visibleFiles.get(path);
            if (file == null && create) {
                file = new SimulatedFileIO(path, random);
                visibleFiles.put(path, file);
            }
            if (file == null) {
                future.fail(new NoSuchFileException(path));
            } else {
                future.complete(file);
            }
        });
        return future;
    }

    @Override
    public TickCompletableFuture<Void> rename(String from, String to) {
        TickCompletableFuture<Void> future = new TickCompletableFuture<>();
        pendingOps.add(() -> {
            SimulatedFileIO file = visibleFiles.remove(from);
            if (file == null) {
                future.fail(new NoSuchFileException(from));
                return;
            }
            visibleFiles.put(to, file);
            future.complete(null);
        });
        return future;
    }

    @Override
    public TickCompletableFuture<Void> syncDirectory(String directory) {
        TickCompletableFuture<Void> future = new TickCompletableFuture<>();
        pendingOps.add(() -> {
            visibleFiles.forEach((path, file) -> {
                if (parentOf(path).equals(directory)) {
                    durableFiles.put(path, file);
                }
            });
            future.complete(null);
        });
        return future;
    }

    @Override
    public void tick() {
        List<Runnable> dueOps = new ArrayList<>(pendingOps);
        pendingOps.clear();
        dueOps.forEach(Runnable::run);

        for (SimulatedFileIO file : visibleFiles.values()) {
            file.tick();
        }
    }

    /**
     * Simulates a machine crash — pending operations never complete, and the namespace
     * and file contents revert to what was last made durable.
     */
    public void crash() {
        pendingOps.clear();
        for (SimulatedFileIO file : visibleFiles.values()) {
            file.crash();
        }
        visibleFiles = new LinkedHashMap<>(durableFiles);
    }

    private static String parentOf(String path) {
        int lastSeparator = path.lastIndexOf('/');
        return lastSeparator < 0 ? "" : path.substring(0, lastSeparator);
    }
}
