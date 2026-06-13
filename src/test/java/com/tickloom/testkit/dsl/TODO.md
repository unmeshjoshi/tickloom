[//]: # (Review comments from Claude Fable.)
Ordered by impact. Items 1–5 close correctness gaps; 6–9 fix vocabulary and
contracts; 10–12 are reference-quality polish.

### Correctness and misuse-resistance

#### 1. Return a per-client scope from `client(id)` instead of `this`

The single most important change. The *static type* of `client(id)` is already
correct — it returns `QuorumActionScope`, exposing only `writes`/`reads`. What's
wrong is the *binding*: it returns `this`, the one shared mutable builder, so
which client the verbs act for is whatever `currentClientId` happens to be when
the verb runs. The type says "client actions"; the object doesn't say "*this*
client's actions."

Make the returned scope a real per-client object so type and identity line up:

```java
// StepBuilder — id flows into the scope instead of into a field
public abstract class StepBuilder<C extends ClusterClient, T extends ActionScope>
        implements StepScope<T> {

    @Override
    public final T client(ProcessId id) {
        return actionScopeFor(id);
    }

    protected abstract T actionScopeFor(ProcessId clientId);
    // currentClientId field and currentClientId() — deleted
}
```

```java
// QuorumStepBuilder — no longer implements QuorumActionScope itself
final class QuorumStepBuilder
        extends StepBuilder<QuorumReplicaClient, QuorumActionScope> {

    @Override
    protected QuorumActionScope actionScopeFor(ProcessId clientId) {
        return new QuorumClientActions(clientId);
    }

    private final class QuorumClientActions implements QuorumActionScope {
        private final ProcessId clientId;

        QuorumClientActions(ProcessId clientId) { this.clientId = clientId; }

        @Override
        public EventOrAwaitScope<QuorumActionScope> writes(String key, String value) {
            // same action-building code, using this.clientId
            return beginStep(action);   // inner class reaches the enclosing builder
        }
        // reads(...) likewise
    }
}
```

Three wins fall out for free:

- The aliasing bug becomes structurally impossible — `var a = s.client(alice)`
  holds alice's id forever, regardless of a later `s.client(bob)`.
- The builder loses its last piece of step-scoped mutable state.
- `QuorumStepBuilder` stops implementing `QuorumActionScope`, completing the
  separation `DESIGN.md` flagged ("the fluentinterface scratch conflates these —
  works, but cleaner to split"). The builder collects steps; the scope speaks verbs.

#### 2. Make `EventOrAwaitScopeImpl` single-shot

A saved intermediate can fire the terminator twice and record the step twice:

```java
var e = s.client(alice).writes("k", "v");
e.awaitCompletion();
e.awaitCompletion();   // adds a second Step silently
```

Add a `consumed` flag; throw `IllegalStateException` from `whileClusterEvent`,
`awaitCompletion`, and `await` once a terminator has fired.

#### 3. Validate client ids at build time

`s.client(ProcessId.of("typo"))` compiles, builds the scenario, and only dies
inside the action lambda when `clients.get(clientId)` returns null mid-`run()`.
Pass the declared `clientDefs` ids from `ScenarioBuilderImpl.steps(...)` into the
`StepBuilder` and throw immediately in `client(id)` for an undeclared id, with a
message naming the id and listing the declared ones.

#### 4. Make `QuorumStepBuilder`'s constructor private

`new QuorumStepBuilder().writes("k","v")` bypasses the entire grammar
(`currentClientId` null → NPE at run). The `QuorumStepBuilder::new` reference in
its own `scenario(...)` is in the same class, so a private constructor still
compiles. (After change #1 this matters less, but it's still the right default.)

#### 5. Fail fast on failed futures in `AwaitCompletion`

`isCompleted() && !isFailed()` means a failed future never fulfills the
condition, so `tickUntil` spins to its limit and throws a generic timeout. Throw
with the failure cause instead:

```java
if (future.isFailed()) throw new AssertionError("step action failed: ...");
return future.isCompleted();
```

"Write failed at tick N because X" beats "scenario timed out."

### API contracts and naming

#### 6. Rename `whileClusterEvent`

"While" implies the event holds for the action's duration then reverts. The
actual semantics are "introduce before the action, leave it in place forever" —
the DDIA test's manual `Reconnect(BYZANTIUM)` to undo a prior partition is the
proof. `withClusterEvent` or `introducing(...)` is honest. True scoped semantics
(`during(...)` pairing each event with a revert) is separate future work, not
blocking. In a DSL the surface vocabulary *is* the product.

Related: rename `ActionScope` to `ClientActionScope`. Every verb in it is
something a client does, and once the object genuinely represents "the actions
available to *this* client" (change #1), the name tells the truth at both the
type and instance level.

#### 7. Guard dropped client defs and double `servers(...)`

```java
c.client(alice);                    // pendingClientId = alice
c.client(bob).connectedTo(athens);  // alice silently discarded
```

Throw in `client(id)` if `pendingClientId != null`; throw in `servers(...)` if
`serverIds` was already set. Reject empty/duplicate server ids while you're there.

#### 8. Make `Scenario` single-shot (or properly re-runnable)

`run()` is callable twice and the recorder accumulates both runs' history. Either
throw on a second `run()`, or move recorder creation inside `run()` so each run
gets a fresh history. Pick one and document it — silent accumulation is the worst
of both.

#### 9. Generalize `HistoryRecorder<String>`

The `String` result type is a quorum-KV assumption leaking into the
protocol-agnostic `Action`/`Step` layer. Thread the result type through (or fix
it at the `Scenarios.scenario(...)` site alongside the factories) so non-string
protocols don't hit a wall. Cheap now, expensive after a second protocol exists.

### Model quality and polish

#### 10. Replace the composed-event lambda with a record

`compose(events)` returns an anonymous lambda, so `Step.clusterEvent()` on Bob's
step can't be inspected as "Reconnect + Partition." A
`record CompositeEvent(List<ClusterEvent> events) implements ClusterEvent` keeps
the model introspectable — a prerequisite for ever rendering scenarios as
documentation, which the DDIA test practically begs for.

#### 11. Give `ScenarioRunner2Test` real assertions

It currently runs and `System.out.println`s the history — it would pass on an
empty history. Assert the EDN (or at minimum invoke/ok event counts) like the
DDIA test does. Add negative tests: one per runtime guard from #2, #3, #5, #7,
asserting the exception and its message. The guards are part of the DSL's
contract and deserve coverage.

#### 12. Sync the docs

- `DESIGN.md` says the entry point is `QuorumScenarios.scenario(...)`; the code
  put it on `QuorumStepBuilder.scenario(...)`. Update the doc (or extract a
  `QuorumScenarios` facade — either is fine, but they must agree).
- `DESIGN.md` claims `StepBuilder` is package-private; impossible given the
  cross-package subclass. Correct it.
- `Action`'s javadoc has a dangling `{@link ClientStep}` — a type that doesn't exist.
- The `ScenarioRunner2Test` javadoc sketch references the old entry point.

In a DSL the docs are the user manual, so drift costs more here than usual.

---

## Deliberately *not* recommended

Don't chase compile-time enforcement for the saved-intermediate cases (#1b, #2,
#7). Progressive interfaces can't prevent reference capture in Java, and
contortions like phantom-typed tokens would wreck the readability that is the
DSL's whole point. Runtime guards with excellent messages are the idiomatic
ceiling; reaching it cleanly is what a 10 looks like.

---

## Summary

| # | Item | Severity | Effort |
|---|------|----------|--------|
| 1 | Per-client scope from `client(id)` | High | Medium |
| 2 | Single-shot `EventOrAwaitScopeImpl` | High | Low |
| 3 | Build-time client-id validation | High | Low |
| 4 | Private `QuorumStepBuilder` constructor | Medium | Low |
| 5 | Fail fast on failed futures | Medium | Low |
| 6 | Rename `whileClusterEvent` / `ActionScope` | Medium | Low |
| 7 | Guard dropped client defs / double `servers` | Medium | Low |
| 8 | Single-shot (or re-runnable) `Scenario` | Medium | Low |
| 9 | Generalize `HistoryRecorder<String>` | Low | Medium |
| 10 | `CompositeEvent` record | Low | Low |
| 11 | Real assertions + negative tests | Medium | Medium |
| 12 | Doc sync | Low | Low |

The foundation doesn't need to move. Change #1 is the one to prioritize — it
removes shared mutable state rather than papering over it with runtime checks.
