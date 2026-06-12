[//]: # (Review comments from Claude Fable.)
Correctness and misuse-resistance

Return a per-client scope from client(id) instead of this. Change actionBuilder() to actionBuilder(ProcessId clientId) and have QuorumStepBuilder return a small object capturing the id. This deletes the mutable currentClientId field and eliminates the aliasing bug where a saved scope records the wrong client — the worst defect in the current code because it's silent.
Make EventOrAwaitScopeImpl single-shot. Add a consumed flag; throw IllegalStateException from whileClusterEvent, awaitCompletion, and await if a terminator already fired. This closes the double-terminator hole that records a step twice.
Validate client ids at build time. Pass the declared clientDefs ids from ScenarioBuilderImpl.steps(...) into the StepBuilder, and throw immediately in client(id) for an undeclared id — with a message naming the id and listing the declared ones. Turns a mid-run NPE into an instant, self-explaining failure.
Make QuorumStepBuilder's constructor private. The QuorumStepBuilder::new reference in its own scenario(...) still compiles, and new QuorumStepBuilder().writes(...) — which bypasses the entire grammar — stops being possible.
Fail fast on failed futures in AwaitCompletion. Throw with the failure cause when future.isFailed() instead of spinning until tickUntil's limit. "Write failed at tick N because X" beats "scenario timed out."

API contracts and naming

Rename whileClusterEvent. It introduces an event before the action and never reverts it; the DDIA test's manual Reconnect proves the name lies. withClusterEvent or introducing(...) is honest. If you ever want true scoped semantics, that's a separate during(...) that pairs each event with a revert — worth noting as future work, not blocking.
Guard against dropped client defs and double servers(...). Throw in client(id) if pendingClientId != null, and in servers(...) if serverIds was already set. Also reject empty/duplicate server ids while you're there.
Make Scenario single-shot (or properly re-runnable). Either throw on a second run() or move the recorder creation inside run() so each run gets a fresh history. Pick one and document it; the silent accumulation today is the worst of both.
Generalize HistoryRecorder<String>. Thread the result type through Action/Step/Scenario (or fix it at the Scenarios.scenario(...) site alongside the factories) so non-string-valued protocols don't hit a wall. Cheap now, expensive after a second protocol exists.

Model quality and polish

Replace the composed-event lambda with record CompositeEvent(List<ClusterEvent> events). Keeps the semantic model fully introspectable — a prerequisite if you ever render scenarios as documentation, which the DDIA test practically begs for.
Give ScenarioRunner2Test real assertions. Assert the EDN history (or at minimum invoke/ok event counts) like the DDIA test does. Also add negative tests: one per runtime guard from items 2, 3, 5, and 7, asserting the exception and its message. The guards are part of the DSL's contract, so they deserve coverage.
Sync the docs. Update DESIGN.md's entry-point (QuorumStepBuilder.scenario, not QuorumScenarios) and its claim that StepBuilder is package-private; fix the dangling {@link ClientStep} in Action's javadoc; update the ScenarioRunner2Test javadoc sketch. In a DSL the docs are the user manual, so drift costs more here than usual.

One thing I'd deliberately not do: chase compile-time enforcement for the saved-intermediate cases. Progressive interfaces can't prevent reference capture in Java, and contortions like phantom-typed tokens would wreck the readability that is the DSL's whole point. Runtime guards with excellent messages (items 2, 3, 7) are the idiomatic ceiling, and reaching that ceiling cleanly is what a 10 looks like.