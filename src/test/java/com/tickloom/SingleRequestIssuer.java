package com.tickloom;

import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.future.ListenableFuture;

import java.util.ArrayDeque;
import java.util.Random;

public class SingleRequestIssuer<C extends ClusterClient> {
    private final SimulationRunner simulationRunner;
    private final C client;
    private final Random rng;

    private final ArrayDeque<Runnable> buffered = new ArrayDeque<>();
    private boolean inFlight = false;

    public SingleRequestIssuer(SimulationRunner runner, C client, Random rng) {
        this.simulationRunner = runner;
        this.client = client;
        this.rng = rng;
    }

    public void issueRequest() {
        sendRequest(() -> sendClientRequest());
    }

    public void sendRequest(Runnable r) {
        if (inFlight) {
            buffered.addLast(r);
            return;
        }
        inFlight = true;
        r.run(); // assumes single-threaded / no throw
    }

    private void sendClientRequest() {
        ListenableFuture<?> f = simulationRunner.issueRequest(client, rng);
        f.andThen((result, ex) -> {
            inFlight = false;
            drainNext();
        });
    }

    private void drainNext() {
        if (inFlight) return;     // someone else already started one

        Runnable next = buffered.pollFirst();
        if (next != null) next.run();
    }

    public boolean isInFlight() { return inFlight; }

    /** True if there’s an op in flight OR anything queued. */
    public boolean hasPendingRequests() { return inFlight || !buffered.isEmpty(); }
}
