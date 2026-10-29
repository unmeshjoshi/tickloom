package com.tickloom;

import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.future.TickCompletableFuture;

import java.util.ArrayDeque;
import java.util.Random;

/**
 * Enforces at most one in-flight request per client.
 * <p>
 * In consistency and linearizability testing (such as Jepsen), each client process
 * represents a single sequential thread of control: an operation must complete (or timeout)
 * before the client can issue its next operation.
 * <p>
 * If the simulation driver picks this client to issue a request while a previous request
 * is still pending, the new request is buffered and drained sequentially once the active
 * request completes.
 */
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
        sendRequest(this::sendClientRequest);
    }

    public void sendRequest(Runnable r) {
        if (inFlight) {
            buffered.addLast(r);
            return;
        }
        inFlight = true;
        r.run();
    }

    private void sendClientRequest() {
        TickCompletableFuture<?> f = simulationRunner.issueRequest(client, rng);
        f.whenComplete((result, ex) -> {
            inFlight = false;
            drainNext();
        });
    }

    private void drainNext() {
        if (inFlight) return;

        Runnable next = buffered.pollFirst();
        if (next != null) next.run();
    }

    public boolean isInFlight() {
        return inFlight;
    }

    public boolean hasPendingRequests() {
        return inFlight || !buffered.isEmpty();
    }
}
