package com.tickloom.fs;

import com.tickloom.future.TickCompletableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs blocking calls one at a time, in submission order, on a single thread. The thread never completes a future:
 * when a call finishes it only queues the completion, and the owner's thread runs it in {@link #pollCompletions()}.
 */
public final class IOThread {
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    // The only structure shared with the owner's thread: tasks that complete futures.
    private final LinkedBlockingQueue<Runnable> completions = new LinkedBlockingQueue<>();

    public <T> TickCompletableFuture<T> submit(Callable<T> call) {
        TickCompletableFuture<T> future = new TickCompletableFuture<>();
        try {
            executor.submit(() -> {
                try {
                    T result = call.call();
                    completions.add(() -> future.complete(result));
                } catch (Throwable e) { // a RuntimeException must not vanish inside the executor
                    completions.add(() -> future.fail(e));
                }
            });
        } catch (RejectedExecutionException e) {
            future.fail(e); // shut down; nothing was queued
        }
        return future;
    }

    /** Runs the completions of finished calls, in the order they finished. Owner's thread only. */
    public void pollCompletions() {
        Runnable completion;
        while ((completion = completions.poll()) != null) {
            completion.run();
        }
    }

    /** Already queued calls still run. Later submits are rejected. */
    public void shutdown() {
        executor.shutdown();
    }
}
