package com.tickloom.testkit.dsl.semanticmodel;

import com.tickloom.history.JepsenHistory;

/**
 * Outputs of a single {@link Scenario#run()}:
 *
 * <ul>
 *   <li>{@link #history()} — the Jepsen-style timeline of invocations and
 *       responses recorded during the scenario.
 *   <li>{@link #snapshot()} — a read-only view of replica / client in-memory
 *       state at the moment the last step completed. The underlying cluster
 *       is closed by the time this result is returned; the snapshot only
 *       supports state reads, not ticks or message sends.
 * </ul>
 */
public record ScenarioResult(JepsenHistory history, ClusterSnapshot snapshot) {
}
