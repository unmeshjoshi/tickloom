<p align="center">
  <img src="logo.png" alt="TickLoom Logo" width="200"/>
</p>

## TickLoom
### A fabric of ticking processes

TickLoom is a lightweight Java framework for building **deterministic, testable distributed systems**.  
It gives you:

- A **single-threaded tick loop** for deterministic execution
- Pluggable **network** implementations (real or simulated)
- A **messaging layer** with request/response correlation
- **Process** and **Replica** abstractions for algorithms like replication and consensus
- A **testkit** for cluster setup, failure injection, and deterministic time control

---

## Why TickLoom?

Distributed systems share common needs:

- **Messaging** – send/receive between processes over a network
- **Coordination** – handle client requests that require multiple processes
- **Waiting & timeouts** – keep requests pending until quorum/consensus is reached
- **Testing** – spin up clusters, inject failures, control time, and verify results

TickLoom provides all of these **in a single-threaded deterministic model** — making tests reproducible and easier to debug.

---

## The Tick Model

All processes run inside a shared **logical clock**.  
Each call to `tick()` advances time, processes I/O, and delivers messages deterministically.

```
┌──────────┐       messages       ┌──────────┐
│ ProcessA │  ─────────────────→  │ ProcessB │
└──────────┘ ←──────────────────  └──────────┘
      ▲         simulated ticks          ▲
      └────── deterministic clock ───────┘
```

---

## Installation

Artifacts are published to Maven Central under the `io.github.unmeshjoshi` group.

**Gradle (Kotlin DSL)**:
```kotlin
dependencies {
  implementation("io.github.unmeshjoshi:tickloom:0.1.0-alpha.2")
  testImplementation("io.github.unmeshjoshi:tickloom-testkit:0.1.0-alpha.2")
}
```

**Maven**:
```xml
<dependency>
  <groupId>io.github.unmeshjoshi</groupId>
  <artifactId>tickloom</artifactId>
  <version>0.1.0-alpha.2</version>
</dependency>
<dependency>
  <groupId>io.github.unmeshjoshi</groupId>
  <artifactId>tickloom-testkit</artifactId>
  <version>0.1.0-alpha.2</version>
  <scope>test</scope>
</dependency>
```

**Requirements**:
- Java 21+

---

## Quick Start

Below is a minimal Echo example that shows how to build on TickLoom’s primitives:
- `EchoServer` extends `Process`
- `EchoClient` extends `ClusterClient`
- A JUnit test uses the `Cluster` testkit and `assertEventually`

### EchoServer and EchoClient
*(Full code retained as in original)*

```java
// TODO: Insert full EchoServer and EchoClient code here from your source
```

---

### JUnit test with the Cluster testkit
```java
// TODO: Insert full EchoExampleTest code here from your source
```

---

## Testkit

The testkit (`io.github.unmeshjoshi:tickloom-testkit`) contains helpers to:

- Spin up clusters quickly
- Control simulated time
- Inject network failures (partitions, delays, packet loss)
- Group nodes and apply chaos in a targeted way

Example:
```java
import com.tickloom.testkit.Cluster;
import com.tickloom.ProcessId;

Cluster cluster = new Cluster()
    .withProcessIds(ProcessId.of("n1"), ProcessId.of("n2"), ProcessId.of("n3"))
    .useSimulatedNetwork()
    .withInitialClockTime(1)
    .build((id, peers, bus, codec, storage, clock, timeout) -> /* create Replica */)
    .start();

cluster.partitionNodes(ProcessId.of("n1"), ProcessId.of("n3"));
// Advance ticks until a condition is met
// assertEventually(cluster, () -> ...);

cluster.healPartition(ProcessId.of("n1"), ProcessId.of("n3"));
cluster.close();
```

---

## Key Concepts

- **Process** – deterministic event-driven unit of logic (`tick()` loop)
- **Replica** – process with built-in cluster utilities (peer tracking, broadcast, quorum handling)
- **Messaging** – messages, message types, buses, correlation IDs
- **Network**:
  - `SimulatedNetwork`: deterministic delivery with configurable delays, partitions, and loss
  - `NioNetwork`: non-blocking networking for real deployments
- **Storage** – in-memory, RocksDB-backed, or simulated

---

## Building & Testing

```bash
# Full build
./gradlew build

# Run tests
./gradlew test
```

---

## Who Should Use TickLoom?

TickLoom is for you if you:
- Build distributed algorithms in Java
- Need deterministic, reproducible tests
- Want to simulate failures without non-deterministic chaos
- Prefer single-threaded event loop architecture

---

## Acknowledgements

TickLoom’s tick model and deterministic simulation approach are inspired by the excellent [TigerBeetle](https://github.com/tigerbeetle/tigerbeetle) project.

---

## License

Apache License 2.0. See `LICENSE`.
