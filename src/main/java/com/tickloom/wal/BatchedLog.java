package com.tickloom.wal;

import com.tickloom.future.TickCompletableFuture;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The sync policy on top of a {@link Wal}, in the style of BookKeeper's journal.
 *
 * Owns: when to sync (the oldest waiter has waited {@code maxGroupWaitTicks} ticks, with at most one sync in
 * flight) and the waiters. {@code append} writes at once and returns a future that completes when the record is
 * durable, with the record's offset. Records appended while a sync is in flight wait for the next one.
 *
 * Relies on the Wal: its writes and syncs complete in order, so a sync covers every record appended before it.
 * The loop owner must call {@link #tick()} (and tick the file under the Wal).
 *
 * A failed write or sync fails every waiter and every later append.
 */
public class BatchedLog {
    private static final class Waiter {
        final long queuedAtTick;
        final TickCompletableFuture<Long> durable = new TickCompletableFuture<>();
        TickCompletableFuture<Long> write;

        Waiter(long queuedAtTick) {
            this.queuedAtTick = queuedAtTick;
        }
    }

    private final Wal wal;
    private final int maxGroupWaitTicks;
    private final Deque<Waiter> waiting = new ArrayDeque<>();   // written, not yet covered by a sync call
    private List<Waiter> syncing = new ArrayList<>();            // covered by the sync in flight
    private boolean syncInFlight;
    private Throwable failure;
    private long tickCount;

    public BatchedLog(Wal wal, int maxGroupWaitTicks) {
        if (maxGroupWaitTicks < 1) {
            throw new IllegalArgumentException("maxGroupWaitTicks must be at least 1");
        }
        this.wal = wal;
        this.maxGroupWaitTicks = maxGroupWaitTicks;
    }

    /** Completes with the record's offset once it is durable. */
    public TickCompletableFuture<Long> append(byte[] payload) {
        if (failure != null) {
            TickCompletableFuture<Long> rejected = new TickCompletableFuture<>();
            rejected.fail(failure);
            return rejected;
        }
        Waiter waiter = new Waiter(tickCount);
        waiting.add(waiter);
        waiter.write = wal.append(List.of(payload));
        // Registered after queueing: a write that has already failed must find this waiter in the queue.
        waiter.write.whenComplete((offset, error) -> {
            if (error != null) {
                failAll(error);
            }
        });
        return waiter.durable;
    }

    public void tick() {
        tickCount++;
        if (failure == null && !syncInFlight && !waiting.isEmpty()
                && tickCount - waiting.peekFirst().queuedAtTick >= maxGroupWaitTicks) {
            flush();
        }
    }

    private void flush() {
        syncInFlight = true;
        syncing = new ArrayList<>(waiting);
        waiting.clear();
        wal.sync().whenComplete((durableOffset, error) -> {
            syncInFlight = false;
            if (error != null) {
                failAll(error);
                return;
            }
            List<Waiter> covered = syncing;
            syncing = new ArrayList<>();
            for (Waiter waiter : covered) {
                completeDurable(waiter);
            }
        });
    }

    private void completeDurable(Waiter waiter) {
        // File calls complete in order, so the write finished before the sync that covers it.
        if (waiter.write.isCompleted()) {
            waiter.durable.complete(waiter.write.getResult());
        } else {
            waiter.durable.fail(new IllegalStateException("sync completed before the write"));
        }
    }

    private void failAll(Throwable error) {
        if (failure == null) {
            failure = error;
        }
        List<Waiter> all = new ArrayList<>(syncing);
        all.addAll(waiting);
        syncing = new ArrayList<>();
        waiting.clear();
        for (Waiter waiter : all) {
            if (waiter.durable.isPending()) {
                waiter.durable.fail(error);
            }
        }
    }
}
