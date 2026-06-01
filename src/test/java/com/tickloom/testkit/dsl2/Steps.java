package com.tickloom.testkit.dsl2;

import com.tickloom.algorithms.replication.ClusterClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Protocol-agnostic base for the steps block. A protocol package subclasses this and
 * overrides {@link #newHandle(String)} to return its typed client handle:
 * <pre>{@code
 * public final class QuorumSteps extends Steps<QuorumReplicaClient, QuorumClientStep> {
 *     @Override protected QuorumClientStep newHandle(String name) {
 *         return new QuorumClientStep(name, this);
 *     }
 * }
 * }</pre>
 */
public abstract class Steps<C extends ClusterClient, H extends ClientStep<C>> {
    private final List<Step<C, ?>> recorded = new ArrayList<>();

    protected abstract H newHandle(String clientName);

    public final H client(String name) {
        return newHandle(name);
    }

    final <V> StepBuilder<C, V> record(Step<C, V> step) {
        recorded.add(step);
        return new StepBuilder<>(step);
    }

    /**
     * Drives the cluster forward until {@code predicate} (over a {@link NodeView} of {@code node})
     * is satisfied. Useful as a final convergence step after the last verb when in-flight requests
     * need to settle before history-based assertions.
     */
    public final ConvergenceBuilder<C> untilConverged(String node,
                                                      java.util.function.Predicate<NodeView> predicate) {
        Action<C, Void> noAction = (clients, cluster, recorder) ->
                com.tickloom.future.TickCompletableFuture.completed(null);
        Step<C, Void> step = new Step<>(noAction);
        step.withAwait(new AwaitState(cluster ->
                predicate.test(new ClusterNodeView(cluster, com.tickloom.ProcessId.of(node)))));
        recorded.add(step);
        return new ConvergenceBuilder<>(step);
    }

    public final List<Step<C, ?>> recorded() { return recorded; }
}
