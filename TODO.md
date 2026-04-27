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

## Storage Refactor

### Track A — Add FileIO, build LogStore, eventually remove old Storage

FileIO is the new low-level file I/O interface (like io_uring). Introduced alongside
existing Storage so nothing breaks. LogStore (WAL) is built on FileIO. Old Storage
(KV-level) is removed once all consumers have migrated.

- [ ] **Create FileIO interface** (new, alongside existing Storage)
  - `write(data, offset)`, `read(offset, length)`, `sync()`, `size()`
  - Extends Tickable — operations submitted immediately, complete during tick()
  - Supports both append (etcd-style WAL) and positioned overwrite (TigerBeetle/Paxos slots)

- [ ] **Create SimulatedFileIO**
  - Volatile vs durable data tracking (unsynced writes lost on crash)
  - Configurable delays and failure injection
  - Torn write simulation for crash recovery testing

- [ ] **Create LogStore on top of FileIO**
  - etcd-style append-only WAL: `append(entry)`, sequential read for recovery
  - Entry framing: length prefix + checksum
  - BookKeeper-style GroupCommit: batch writes between ticks, one sync() for the batch
  - Recovery: scan file sequentially, verify checksums, rebuild in-memory state

- [ ] **Tests for FileIO and LogStore**
  - Basic read/write/sync on FileIO
  - Overwrite at same offset
  - Crash simulation (unsynced writes lost)
  - LogStore append and recovery scan
  - Batching behavior

- [ ] **Remove old Storage interface** (after all consumers migrated)
  - Remove Storage, SimulatedStorage, RocksDbStorage
  - Or keep RocksDbStorage as a standalone utility not tied to the framework

### Track B — Decouple Process from storage

Process provides messaging and tick infrastructure. Storage is each algorithm's choice.

- [ ] **Remove storage from Process and ProcessParams**
  - Remove `Storage storage` field from ProcessParams record
  - Remove `storage` field and `persist()`/`load()` methods from Process

- [ ] **Update QuorumReplica to use its own in-memory KV store**
  - Replace `storage.get()`/`storage.put()` with local `TreeMap<byte[], byte[]>`
  - Add `getStoredValue()` for test access
  - No disk I/O needed — durability comes from replication

- [ ] **Update Cluster, ServerMain, and test infrastructure**
  - Remove storage from Cluster.Node and tick loop
  - Remove `getStorageForProcess()`/`getStorageValue()` from Cluster
  - Update ClusterTest assertions to access storage through process
  - Update ServerMain event loop
  - Update ReplicaTest

### Track C — Update distrib-patterns-workshop (needs Track A + B)

Each algorithm uses the storage pattern appropriate to its design:

- [ ] **GenerationVotingServer** — own KV store (RocksDB) with sync for generation. No WAL needed.
- [ ] **PaxosServer** — own KV store (RocksDB) with sync for PaxosState. No WAL needed.
- [ ] **PaxosLogServer** — in-memory per-slot PaxosState. Optionally add LogStore (WAL) for durability.
- [ ] **RaftServer** — LogStore (WAL) + in-memory log (etcd-style). Append to WAL, fsync, rebuild on recovery. currentTerm/votedFor as WAL entries.
- [ ] **DurableKVStore/WriteAheadLog** — evaluate: rebase Day 1 WAL on tickloom's LogStore/Storage, or keep as standalone teaching example.

### Storage pattern summary

| Algorithm            | Storage pattern                      | Needs LogStore? |
|----------------------|--------------------------------------|-----------------|
| QuorumReplica        | In-memory TreeMap                    | No              |
| GenerationVoting     | Direct KV (RocksDB) with sync        | No              |
| PaxosServer          | Direct KV (RocksDB) with sync        | No              |
| PaxosLogServer       | In-memory + optional WAL             | Optional        |
| RaftServer           | WAL + in-memory log (etcd-style)     | Yes             |
| DurableKVStore       | WAL + in-memory map                  | Yes             |

## Other TODO
- [ ] Move Jepsen specific history_edn handling in linearizability-checker
- [ ] Use Jepsen's generator in the SimulationRunner.
- [ ] Store mavencentral token and gpg key and write a script to setup gradle.properties required for the publish task to work.
- [ ] Separate messagecodec and storagecodec. Right now, the data stored in storage is encoded with the same messagecodec as used for messaging.
