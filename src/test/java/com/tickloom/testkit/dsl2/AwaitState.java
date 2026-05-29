package com.tickloom.testkit.dsl2;

import com.tickloom.future.TickCompletableFuture;
import com.tickloom.testkit.Cluster;

import java.util.function.Predicate;

/**
 * Awaits until a user-supplied predicate over the cluster state is satisfied.
 *
 * Example — wait until "athens" has stored {@code key1} with value {@code value1}:
 * <pre>{@code
 * new Step(action, new AwaitState(cluster -> {
 *     VersionedValue v = cluster.getDecodedStoredValue(
 *             ProcessId.of("athens"), "key1".getBytes(), VersionedValue.class);
 *     return v != null && Arrays.equals(v.value(), "value1".getBytes());
 * }));
 * }</pre>
 */
public record AwaitState(Predicate<Cluster> predicate) implements AwaitCondition {
    @Override
    public boolean isFulfilled(TickCompletableFuture future, Cluster cluster) {
        return predicate.test(cluster);
    }
}
