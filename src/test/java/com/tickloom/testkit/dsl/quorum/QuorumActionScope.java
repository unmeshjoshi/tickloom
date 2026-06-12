package com.tickloom.testkit.dsl.quorum;

import com.tickloom.testkit.dsl.ActionScope;
import com.tickloom.testkit.dsl.EventOrAwaitScope;

/**
 * Narrow grammar projection of {@link QuorumStepBuilder}: the only methods
 * reachable after {@code client(id)} inside a step lambda. Excludes
 * {@code client(...)} so {@code .client(a).client(b).writes(...)} is a
 * compile error.
 */
public interface QuorumActionScope extends ActionScope {
    EventOrAwaitScope<QuorumActionScope> writes(String key, String value);
    EventOrAwaitScope<QuorumActionScope> reads(String key);
}
