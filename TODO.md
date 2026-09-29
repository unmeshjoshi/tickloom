# TickLoom TODO

## Completed - March 22, 2025

- [x] Refactor build.gradle following best practices for maintainability
- [x] Add proper Maven Central POM metadata for main library publication  
- [x] Fix Maven Central validation issues for tickloom component
- [x] Design enums for ConsistencyChecker to replace string parameters
- [x] Update SIGNING_README.md to replace OSSRH references with Maven Central
- [x] Update run-cluster.sh with realistic financial transaction demo
- [x] Add Clojure test integration with Gradle build
- [x] Move history recording to QuorumKVClient or ClusterClient
  - Added HistoryRecorder class and a ClusterTest base class to implement common methods for history recording.
- [x] Update Storage to have byte[] values, so that it can have generic values.
- [x] The API should be updated to reflect datastores like RocksDB. It should have lastKey method, which will be used by WAL like implementations
- [x] Add WriteOptions parameter to set method which allows supporting 'sync' parameter.
- [x] Update ListenableFuture API to add thenApply and thenCompose.
- [x] FileIO interface: `write(data, offset)`, `read(offset, length)`, `sync()`, `truncate()`, `size()`; Tickable
- [x] SimulatedFileIO with volatile vs durable data, configurable delays, uniform failure injection, `crash()`
- [x] LogStore (length-prefixed append-only WAL) on FileIO, with `recover()`
- [x] Basic tests for LocalFileIO, SimulatedFileIO and LogStore (read/write/sync, overwrite, crash loses unsynced writes)

## Deterministic simulation roadmap

Work through these in order. Each item lands with regression tests.

### Phase 1 — Simulator correctness bugs

- [x] **1. FIFO tie-breaking in all tick queues**
  - Ops completing in the same tick run in non-issue order (probe: same-link messages delivered `[0, 11, 10, …]`;
    12 puts then get returned `v0`; `sync` completed before earlier writes).
  - Add a monotonic sequence number to `SimulatedNetwork.QueuedMessage` (currently uses `currentTick`),
    `SimulatedStorage.PendingOperation`, `SimulatedFileIO.PendingOp`, `rocksdb/ops/PendingOperation`.
- [x] **2. Fix per-link delay semantics in SimulatedNetwork**
  - `setDelay` stores a duration that `isClogged` treats as an absolute "until" tick, so delays stop working after N ticks.
  - `clogFor` schedules at ~`2*currentTick + duration`.
  - `withDelayTicks` / `defaultDelayTicks` has no effect.
  - Separate "configured link latency" from "temporary clog until tick".
- [x] **3. Random path clogging is disabled** — `1/100` integer division in `NetworkOptions.create()`.
  - Clogs from `clogFor` never expire (since item 2, `DelayConfig` stores a duration with no expiry).
- [x] **4. Tick the shared network once per `Cluster.tick()`**
  - `Node.tick` and `ClientNode.tick` each tick `sharedNetwork`, so one cluster tick = N network ticks.
  - Order: network → processes → storage. Remove the `delayForClusterTicks` workaround.
- [x] **5. Fix tests that passed only because delays were broken**
  - `SimulatedNetworkTest.shouldSupportPerLinkDelay`, `NetworkPartitionTest.shouldHandleVariableNetworkDelays`,
    `ClusterTest.delay`, `FaultInjectingSimulationRunner.delayRandomDirectedLink`.
- [x] **6. Fault rules: fail loudly on a second drop rule per link**
  - `dropMessagesOfType` / `dropNthMessagesOfType` use `computeIfAbsent`, so a second rule on the same link is silently ignored.
    Throw `IllegalStateException` instead.
  - Deferred until an exercise needs them: multiple rules per link; heal/reset clearing drop rules and per-type delays;
    clog merge dropping the message type.
- (Item 7 moved to Postponed.)
- [x] **8. Process lifecycle** — `start()` marks RUNNING before `onStart()` completes; use STARTING until the future completes.

### Phase 2 — Simulated filesystem, crash/restart, workshop durability (current focus)

**Goal:** realistic simulated storage with crash/restart, used by distrib-patterns-workshop, in a form that can also back
a production engine built on tickloom.

**Principle — simulation and production share one contract.** Every simulated component has a production
implementation behind the same interface and the same semantics; algorithms and storage engines cannot tell which one
they run on. The simulation must be *at least as adversarial* as the real system (anything real hardware/OS can do,
the simulation should eventually be able to do), never more lenient.

| Contract | Production | Simulation |
|---|---|---|
| `Network` | `NioNetwork` (selector polled in `tick()`) | `SimulatedNetwork` |
| `FileSystem` (new) | `LocalFileSystem` (`FileChannel`, `Files.move(ATOMIC_MOVE)`, directory `force`) | `SimulatedFileSystem` |
| `FileIO` | `LocalFileIO` | `SimulatedFileIO` (file inside `SimulatedFileSystem`) |

Rules that follow from the principle:
- **Completion only on `tick()`** in both implementations. Callers never observe an I/O result inside the call that
  issued it. Production performs I/O off the tick thread (I/O thread pool first; io_uring via FFM later, like
  TigerBeetle) and delivers completions in `tick()` — a blocking `fsync` must never stall the node's event loop.
- **POSIX durability semantics, explicitly:** file data is durable only after `sync()`; create/rename/delete are durable
  only after `syncDirectory()`; `rename` is atomic. Production implements exactly these calls; simulation models
  exactly these guarantees. (Platform notes for production: directory fsync works on Linux, not Windows; macOS
  `force()` is not a full flush — needs `F_FULLFSYNC`.)
- **The node owns the filesystem, not the process.** A process opens any number of files by path (multi-raft,
  log + state files); after a crash a new process instance gets the same filesystem and reopens by path
  (FoundationDB: `machine->openFiles`; TigerBeetle: `cluster.storages[i]` outlives `replica_restart()`).
- **Storage engines are ordinary code on the contract** (`LogStore`, `AtomicFile`), identical in production and
  simulation — so their recovery code is what gets tested.
- Key-value `Storage` / `RocksDbStorage` remains a production-only utility (native code cannot run on a simulated
  filesystem). `SimulatedStorage` is not extended.

Design references (compared 2026-09-29): FoundationDB `AsyncFileNonDurable` + `Sim2FileSystem` (filesystem layer,
pending writes until sync, per-page/sector outcomes on kill, `.part` + rename for atomic create, non-durable delete);
TigerBeetle `src/testing/storage.zig` (block-device layer with O_DIRECT/O_DSYNC, sector faults, misdirected writes,
fault atlas keeping faults recoverable).

- [ ] **S1. `FileSystem` contract + `SimulatedFileSystem`**
  - `open(path, create)`, `rename(from, to)`, `delete(path)`, `list(dir)`, `syncDirectory(dir)`; all return
    `TickCompletableFuture`s completed in `tick()`. Files are `FileIO`.
  - Per file: written data visible to reads, durable only after `sync()`.
  - Namespace: create/rename/delete visible immediately, durable only after `syncDirectory()`.
  - `crash()`: every file and the namespace revert to their durable state; pending operations never complete.
    (First version: all unsynced data lost. Partial outcomes come in S7.)
- [ ] **S2. `LocalFileSystem`** — production implementation of the same contract; completions delivered in `tick()`
      (I/O thread pool). Bring `LocalFileIO` onto the same completion rule. Contract tests run against both.
- [ ] **S3. Node-owned filesystem in the process API**
  - `ProcessParams` carries the node's `FileSystem` instead of `Storage`; remove `storage`, `persist()`, `load()` from
    `Process`. `Cluster.Node` owns and ticks the filesystem; `ServerMain` creates a `LocalFileSystem` per data dir.
  - Update QuorumReplica (keeps its values in memory or in an `AtomicFile`), ClusterTest, ReplicaTest.
- [ ] **S4. `Cluster.crashNode(id)` / `restartNode(id)`**
  - Crash: deregister the old Process from the MessageBus, drop its pending callbacks, `SimulatedFileSystem.crash()`.
  - Restart: call the ProcessFactory again with the same filesystem so `onStart()` recovery runs.
  - Keep ticking a crashed node's clock (TigerBeetle does this for `.down` replicas).
- [ ] **S5. Storage engines on the contract**
  - `AtomicFile`: write temp → `sync` → `rename` → `syncDirectory`; each step omittable so the workshop can show
    what breaks.
  - `LogStore` on `FileSystem`: per-entry CRC32C, truncate at first bad entry on recovery; tick-driven recovery
    (today `recover()` self-ticks and treats a delayed read as EOF); advance `writeOffset` only after the write
    succeeds; group commit.
- [ ] **S6. Workshop durability**
  - Paxos crash-recovery test: node crashes before its promise is durable — safe with sync, unsafe without.
  - Persist state in GenerationVoting / PaxosLog (`AtomicFile`) and Raft (`LogStore` + `AtomicFile` for term/vote).
- [ ] **S7. Adversarial storage faults** (FoundationDB/TigerBeetle-style)
  - On crash, per unsynced write and per sector: written, not written, or corrupted; small chance all unsynced data
    survives; truncate/delete/rename may or may not have happened unless synced.
  - Torn writes, `fsync` failure that drops dirty pages ("fsyncgate"), read corruption, misdirected writes, disk full,
    latency (min/mean exponential).
  - Corruption must be detectable (checksums) and placed so the cluster can recover (TigerBeetle fault atlas), so a
    failing seed means a bug.

Dropped: old item 17 (decouple Process from storage by going in-memory) and item 18 (remove Storage) — superseded by
S3 (process gets a node-owned filesystem). `SimulatedStorage` crash semantics (old 15) — superseded by S1.

### Phase 3 — Path to production (e.g. multi-raft engine on tickloom)

**Goal:** tickloom can host a production-grade engine such as multi-raft, with the same code running under
simulation. Phase 2 (S1–S5) is a prerequisite: all durable Raft state sits on `FileSystem`, `AtomicFile` and `LogStore`.
The architecture (completion on `tick()`, node-owned filesystem, cluster-owned network) already fits; the items below
are the gaps found on 2026-09-29.

- [ ] **P1. Fixed-rate production tick**
  - Today `ServerMain.runEventLoop` ticks once per `select` wakeup (up to 10 ms, sooner when I/O arrives), so
    tick-based timeouts (election, heartbeat, request) fire faster under load.
  - Drive `tick()` from a fixed timer (TigerBeetle: 10 ms tick) independent of I/O readiness; process I/O
    completions between ticks. Overlaps with the postponed step/tick separation.
- [ ] **P2. Node-level transport with group routing**
  - `NioNetwork.connections` is keyed by `ProcessId` and the registry maps each process to its own address: one TCP
    connection per group replica. Multi-raft needs one connection per node pair.
  - Address nodes, not processes; add a group/process id to the message envelope; `MessageBus` demultiplexes to the
    hosted processes. `SimulatedNetwork` models the same (links between nodes, faults per node pair).
- [ ] **P3. Transport robustness**
  - Bounded per-connection send queues with backpressure (`NioConnection.java:38` TODO; queue is unbounded today),
    so a slow follower cannot grow leader memory without limit.
  - Reconnect with backoff; messages sent on a broken connection fail visibly instead of disappearing silently.
  - Frame checksums; handle partial frames and oversized payloads.
- [ ] **P4. Hot-path cost**
  - Remove per-message `System.out.println` in `NioConnection`/`NioNetwork` (see postponed item 11).
  - Binary message codec alongside `JsonMessageCodec` for log replication traffic.
- [ ] **P5. Shared I/O across groups**
  - Batch fsyncs across groups (group commit at node level), or a shared WAL for all groups on a node (TiKV/CockroachDB
    style), so 1000 groups do not cost 1000 fsyncs per round.
  - Bounded memory for logs: segmented `LogStore`, entries read from disk instead of an all-in-memory list, log
    truncation after snapshot.
- [ ] **P6. Raft on tickloom**
  - Durable term/vote (`AtomicFile`) before replying; log conflict truncation (`LogStore.truncate(fromIndex)`);
    snapshots installed via `AtomicFile`/rename; membership changes.
- [ ] **P7. Simulation workload for the engine**
  - Randomized crash/restart/partition/disk faults (S4, S7) plus a linearizability check on multi-raft histories
    (items 19–21); specific scenarios for config change under partition and crash during snapshot install.

### Later — tickloom2 with continuations (parallel track)

- [ ] **C1. Straight-line algorithm code on continuations**, as a separate copy of tickloom (tickloom2), built in
      parallel with the callback version.
  - Motivation: durable steps chain as callbacks (`wal.append(e).thenCompose(i -> wal.sync())...`); with
    continuations the same code reads `await(wal.append(e)); await(wal.sync()); reply(...)`.
  - Base on `jdk.internal.vm.Continuation` (Java 25), as prototyped in `~/work/java25continuation` (`Coro`, `Scheduler`,
    `await` resuming through the scheduler queue so the run stays deterministic) and designed in
    `~/work/tickloom_continuation_design.md`.
  - `await` on a `TickCompletableFuture` parks the coroutine; completion in `tick()` schedules the resume, so the
    FileSystem/Network contracts from Phase 2/3 stay unchanged.
  - Compare both versions on the same workshop algorithms and simulation seeds.

### Postponed

- [ ] **7. Self-messages via a loopback queue** (becomes a real bug once handlers are synchronous/in-memory)
  - `MessageBus.sendMessage` delivers self-messages inline, so the handler runs nested inside the sender's handler;
    with synchronous handlers the quorum callback and client reply can run inside `QuorumRequestBuilder.send()`.
  - TigerBeetle: `loopback_queue` flushed after the current handler returns (`replica.zig` `flush_loopback_queue`,
    "We do not call flush_loopback_queue() within on_message() to avoid recursion").
  - Queue self-messages and deliver after the current handler returns (still zero latency, never nested).
- [ ] **9. Independent RNG streams per component** (network, storage/disk, IdGen, workload) derived from the master seed,
      so extra draws in one component don't reshuffle the others. TigerBeetle: `vopr.zig` draws `.seed = prng.int(u64)`
      for cluster/network/storage up front; each builds its own PRNG.
- [ ] **10. Always log the seed**; unseeded `Cluster.create*` uses `new Random().nextInt()` and never prints it.
      `RocksDbStorage` uses unseeded `new Random()`.
- [ ] **11. Replace per-message `System.out.println`** (Replica, MessageBus, SimulatedNetwork, SimulatedStorage)
      with a leveled logger or a trace buffer dumped on failure.
- [ ] **12. `RequestWaitingList` uses deadline ticks** instead of ticking a `Timeout` per request; use `LinkedHashMap`;
      remove leftover quorum entries once the quorum future completes.
- [ ] **step/tick separation** (TigerBeetle commit 58c2f5c90 "vopr: allow fast writes") if sub-tick I/O is needed.
- [ ] **19. Runner asserts instead of printing**: fail on non-linearizable history; bound `waitForPendingRequests`.
- [ ] **20. Durability invariant**: after crash + restart, every acknowledged durable write is still present.
- [ ] **21. Seed sweep**: run N seeds, report failing seed and fault schedule; optionally shrink the schedule.
- [ ] **22. More network faults**: message duplication, latency jitter, randomized partition membership (servers only),
      partition filtering at delivery time (in-flight messages dropped), per-link queues like TigerBeetle.
- [ ] **23. Publish the simulator** (`SimulatedNetwork`, `Cluster`, runners live in `src/test`) as `java-test-fixtures`
      or a `tickloom-testkit` artifact so distrib-patterns-workshop can depend on it.

## distrib-patterns-workshop (see Phase 2, S6)

Each algorithm uses the storage pattern appropriate to its design:

- [ ] **GenerationVotingServer** — generation in an `AtomicFile` (write temp → sync → rename → syncDirectory).
- [ ] **PaxosServer** — PaxosState in an `AtomicFile` (replaces `persist`/`load`).
- [ ] **PaxosLogServer** — per-slot PaxosState; `LogStore` (or one `AtomicFile` per slot) for durability.
- [ ] **RaftServer** — `LogStore` + in-memory log (etcd-style): append, fsync, rebuild on recovery; currentTerm/votedFor
      in an `AtomicFile` (or as WAL entries).
- [ ] **DurableKVStore/WriteAheadLog** — evaluate: rebase Day 1 WAL on tickloom's `LogStore` on `FileSystem`, or keep
      as standalone teaching example.

### Storage pattern summary

All patterns run on the node's `FileSystem`, so they behave identically on `LocalFileSystem` and `SimulatedFileSystem`.

| Algorithm            | Storage pattern                          | Needs LogStore? |
|----------------------|------------------------------------------|-----------------|
| QuorumReplica        | In-memory (or `AtomicFile`)              | No              |
| GenerationVoting     | `AtomicFile`                             | No              |
| PaxosServer          | `AtomicFile`                             | No              |
| PaxosLogServer       | `LogStore` or `AtomicFile` per slot      | Optional        |
| RaftServer           | `LogStore` + `AtomicFile` for term/vote  | Yes             |
| DurableKVStore       | WAL (`LogStore`) + in-memory map         | Yes             |

## Other TODO
- [ ] Move Jepsen specific history_edn handling in linearizability-checker
- [ ] Use Jepsen's generator in the SimulationRunner.
- [ ] Store mavencentral token and gpg key and write a script to setup gradle.properties required for the publish task to work.
- [ ] Separate messagecodec and storagecodec. Right now, the data stored in storage is encoded with the same messagecodec as used for messaging.
