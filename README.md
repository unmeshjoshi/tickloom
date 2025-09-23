<p align="left">
  <img src="logo.png" alt="Tickloom Logo" width="200"/>
</p>

## Tickloom - A Fabric Of Ticking Processes
![Java CI with Gradle](https://github.com/unmeshjoshi/tickloom/actions/workflows/gradle.yml/badge.svg)
### Deterministic Simulation with Jepsen-style Consistency Checking

Tickloom is a lightweight Java framework for building **deterministic, testable distributed systems**.

It gives you:

- A **single-threaded tick loop** for deterministic execution
- Pluggable **network** implementations (real or simulated)
- A **messaging layer** with request/response correlation
- **Process** and **Replica** abstractions for algorithms like replication and consensus
- A **testkit** for cluster setup, failure injection, and deterministic time control

---

## Why Tickloom?

Distributed systems share common needs:

- **Messaging** – send/receive between processes over a network
- **Coordination** – handle client requests that require multiple processes
- **Waiting & timeouts** – keep requests pending until quorum/consensus is reached
- **Testing** – spin up clusters, inject failures, control time, and verify results

Tickloom provides all of these **in a single-threaded deterministic model** — making tests reproducible and easier to debug.

---

## The Tick Model

In Tickloom, **`tick()`** represents a single *lock step* of execution.  
Each tick processes pending work in a **fixed, deterministic order**, ensuring reproducibility and predictable behavior across runs.

### Single Thread of Control
All components run in a **single main thread** — there are no worker threads.  
This eliminates race conditions and makes behavior easier to reason about.

### Tick Order
The core components implement `Tickable` and are invoked **in sequence**:

1. **Network** – delivers pending messages  
   - **Simulated mode**: `SimulatedNetwork` decides delivery time based on configured delays, partitions, and packet loss. Messages are delivered only when their scheduled delivery tick is reached.  
   - **Production mode**: `NioNetwork` processes available `SelectionKey`s from Java NIO’s selector in each tick.
2. **MessageBus** – dispatches delivered messages to the correct target processes.  
3. **Process** – runs user-defined logic, handles incoming messages, and schedules outgoing ones.  
4. **Storage** – applies and commits any pending writes.

### Asynchronous Actions in a Synchronous Tick
While network and storage operations are **asynchronous in nature**, Tickloom models them explicitly within the tick loop:
- Outgoing network messages are queued and delivered later according to the network model.
- Storage writes are acknowledged only when committed in a later tick.

### The Driver Loop
A **driver** calls `tick()` on all components in the defined order: Network → MessageBus → Process → Storage

- In **production**, the driver is the system’s main event loop.  
- In **tests**, the driver is the test itself, using **Cluster testkit** helpers to:
  - Advance simulated time
  - Control message delivery and failures
  - Validate system state

This enables both realistic production behavior and reproducible simulation, which is easier to test.


---

## Time and Timeouts

Tickloom models time in terms of **ticks**, not real-world milliseconds.  
Every call to `tick()` advances a **logical tick counter** by one.  
This makes timing deterministic and reproducible across test runs and simulations.

### Tick-Based Timeouts
Timeouts are configured in terms of the number of ticks before they expire — similar to the approach used in [etcd](https://etcd.io/) and [TigerBeetle](https://github.com/tigerbeetle/tigerbeetle):

- Example: If a process has a timeout of `5` ticks, and the current tick counter is `100`, the timeout will trigger at tick `105`.

### Advantages
- **Deterministic** – Same sequence of events produces the same timeout behavior in every run.
- **Testable** – In tests, you can advance time instantly by calling `tick()` in a loop, without waiting in real time.
- **Simulation-friendly** – Works seamlessly with the simulated network and storage delays.

### Timeout Handling
Within each process:
1. Track the tick count at which the timeout will occur.
2. On each `tick()`, compare the current tick counter to the scheduled trigger tick.
3. When the counter reaches or exceeds the target, execute the timeout action.

This model avoids the unpredictability of real-time timers and makes Tickloom suitable for **highly controlled distributed system testing**.

## Messages and Serialization

In Tickloom, messages are defined as **plain Java records**.  
This makes them:
- Simple to declare and read
- Easy to evolve without special tooling
- Type-safe and self-documenting

Using plain records avoids the need for an external IDL or code generation step (such as Protocol Buffers or Thrift).  
Instead, you can define messages directly in Java:

```java
public record EchoRequest(String message) {}
public record EchoResponse(String message) {}
```

Serialization uses JSON by default, but the framework can be extended to support other formats.

## Using Tickloom as a library

Artifacts are on **Maven Central** under the `io.github.unmeshjoshi` group.
There is an examples github repo which demonstrates how to use tickloom as a library.
see **tickloomexamples**: https://github.com/unmeshjoshi/tickloomexamples

**Gradle (Kotlin DSL)**

```kotlin
dependencies {
  implementation("io.github.unmeshjoshi:tickloom:0.1.0-alpha.7")
  testImplementation("io.github.unmeshjoshi:tickloom-testkit:0.1.0-alpha.7")
}
```

**Maven**
```xml
<dependency>
  <groupId>io.github.unmeshjoshi</groupId>
  <artifactId>tickloom</artifactId>
  <version>0.1.0-alpha.7</version>
</dependency>
<dependency>
  <groupId>io.github.unmeshjoshi</groupId>
  <artifactId>tickloom-testkit</artifactId>
  <version>0.1.0-alpha.7</version>
  <scope>test</scope>
</dependency>
```

**Requirements**: Java 21+

**Compatibility**
```
| TickLoom        | tickloomexamples  |
|-----------------|-------------------|
| 0.1.0-alpha.7   | 0.1.0-alpha.7     |
```

---

## Quick Start

Below is a minimal Echo example that shows how to build on Tickloom’s primitives:
- `EchoServer` extends `Process`
- `EchoClient` extends `ClusterClient`
- A JUnit test uses the `Cluster` testkit and `assertEventually`

### EchoServer and EchoClient
*(Full code retained as in original)*

```java
// Example EchoServer and EchoClient code 
public class EchoServer extends Process {

    private final List<ProcessId> peerIds;

    public EchoServer(ProcessId id,
                      List<ProcessId> peerIds,
                      MessageBus messageBus,
                      MessageCodec messageCodec,
                      Storage storage,
                      Clock clock,
                      int timeoutTicks) {
        super(id, messageBus, messageCodec, timeoutTicks, clock);
        this.peerIds = peerIds;
    }

    @Override
    protected Map<MessageType, Handler> initialiseHandlers() {
        return Map.of(
                ECHO_REQUEST, this::onEchoRequest
        );
    }

    private void onEchoRequest(Message msg) {
        EchoRequest request = deserializePayload(msg.payload(), EchoRequest.class);
        EchoResponse response = new EchoResponse(request.text());
        Message responseMessage = createResponseMessage(msg, response, ECHO_RESPONSE);
        try {
            messageBus.sendMessage(responseMessage);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

public class EchoClient extends ClusterClient {

    public EchoClient(ProcessId clientId,
                      List<ProcessId> replicaEndpoints,
                      MessageBus messageBus,
                      MessageCodec messageCodec,
                      Clock clock,
                      int timeoutTicks) {
        super(clientId, replicaEndpoints, messageBus, messageCodec, clock, timeoutTicks);
    }

    public ListenableFuture<EchoResponse> echo(ProcessId server, String text) {
        EchoRequest req = new EchoRequest(text);
        return sendRequest(req, server, ECHO_REQUEST);
    }

    @Override
    protected java.util.Map<MessageType, Handler> initialiseHandlers() {
        return java.util.Map.of(
                ECHO_RESPONSE, msg -> {
                    EchoResponse resp = deserialize(msg.payload(), EchoResponse.class);
                    handleResponse(msg.correlationId(), resp, msg.source());
                }
        );
    }
}
```

---

### JUnit test with the Cluster testkit
```java
public class EchoClusterTest {

    private Cluster cluster;

    @BeforeEach
    void setup() throws Exception {
        cluster = new Cluster()
                .withNumProcesses(1)
                .useSimulatedNetwork()
                .build(EchoServer::new)
                .start();
    }

    @AfterEach
    void teardown() {
        if (cluster != null) cluster.close();
    }

    @Test
    void echo_roundtrip() throws Exception {
        ProcessId serverId = ProcessId.of("process-1");

        EchoClient client = cluster.newClient(ProcessId.of("client-1"), (clientId, endpoints, bus, codec, clock, timeoutTicks) ->
                new EchoClient(clientId, java.util.List.of(serverId), bus, codec, clock, timeoutTicks));

        var future = client.echo(serverId, "hello");
        assertEventually(cluster, () -> future.isCompleted());

        assertEquals("hello", future.getResult().text());
    }
}

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

## Simulation Runner and Consistency Checking

TickLoom includes a simulation harness to drive repeatable workloads and verify correctness:

- **`SimulationRunner`**: base class that runs a cluster for N ticks, issues client requests deterministically (by seed), and records a history.
- **`QuorumSimulationRunner`**: concrete runner for the quorum key-value example (issues GET/SET).
- **`Jepsen`** integration: converts history to EDN and checks linearizability using the Jepsen checker.

### Example: Run a simulation

```java
long seed = 111_111L;
long ticks = 10_000L;

var runner = new com.tickloom.algorithms.replication.quorum.QuorumSimulationRunner(seed);
runner.runForTicks(ticks); // writes EDN to build/history_*.edn and runs Jepsen checker
```

### Determinism by seed

Two runs with the same seed produce identical histories; different seeds generally differ.

```java
var r1 = new QuorumSimulationRunner(42L);
var r2 = new QuorumSimulationRunner(42L);

var h1 = r1.runAndGetHistory(5_000);
var h2 = r2.runAndGetHistory(5_000);

assert h1.equals(h2); // same seed -> same history
```

See `src/test/java/com/tickloom/SimulationRunnerTest.java` for determinism tests.

### Linearizability check (Jepsen)

`SimulationRunner` uses `Jepsen` to validate a register model. You can also call it directly:

```java
var runner = new QuorumSimulationRunner(123L);
var history = runner.runAndGetHistory(5_000);

var consistencyChecker = new com.tickloom.ConsistencyChecker();
boolean ok = consistencyChecker.checkLinearizableRegister(history.toEdn());
System.out.println("Linearizable = " + ok);
```

# Consistency Checking

TickLoom provides comprehensive consistency verification through both **Jepsen integration** and **custom consistency checkers**. This allows you to verify that your distributed algorithms maintain the correct consistency properties under various failure scenarios.

### Supported Consistency Models

- **Linearizability**: Strongest consistency model - operations appear to execute atomically at some point between their invocation and response
- **Sequential Consistency**: Operations appear to execute in some sequential order consistent with program order

### Quickstart: Consistency Check

```java
// Record operation history during simulation
var history = HistoryRecorder.newHistory();
// ... run your simulation, record ops ...
Path edn = history.writeEdn(Paths.get("build/history.edn"));

// Check linearizability using Jepsen
var resultLin = ConsistencyChecker.check(edn, ConsistencyProperty.LINEARIZABILITY, DataModel.REGISTER);
assert resultLin.valid();

// Check sequential consistency using custom checker  
var resultSeq = ConsistencyChecker.check(edn, ConsistencyProperty.SEQUENTIAL_CONSISTENCY, DataModel.REGISTER);
assert resultSeq.valid();
```

### Advanced Usage

#### Multiple Data Models

```java
// Register model (default)
ConsistencyChecker.check(edn, ConsistencyProperty.LINEARIZABILITY, DataModel.REGISTER);

// Compare-and-swap register
ConsistencyChecker.check(edn, ConsistencyProperty.LINEARIZABILITY, DataModel.CAS_REGISTER);

// Set operations
ConsistencyChecker.check(edn, ConsistencyProperty.LINEARIZABILITY, DataModel.SET);

// Mutex operations
ConsistencyChecker.check(edn, ConsistencyProperty.LINEARIZABILITY, DataModel.MUTEX);
```

#### Independent Operations

```java
// For multi-key operations (Jepsen independent checker)
boolean valid = ConsistencyChecker.checkIndependent(
    edn, 
    ConsistencyProperty.LINEARIZABILITY, 
    DataModel.REGISTER
);
```

### Integration with Tests

```java
@Test
void testConsistencyUnderPartition() throws IOException {
    try (var cluster = new Cluster()
            .withProcessIds(List.of(ATHENS, BYZANTIUM, CYRENE, DELPHI, SPARTA))
            .useSimulatedNetwork()
            .build(QuorumReplica::new)
            .start()) {
        
        // Run operations with network partitions
        cluster.partitionNodes(NodeGroup.of(ATHENS, BYZANTIUM), NodeGroup.of(CYRENE, DELPHI, SPARTA));
        
        // Record history
        History<String, String> history = new History<>();
        // ... perform operations ...
        
        // Verify consistency properties
        String edn = history.toEdn();
        boolean linearizable = ConsistencyChecker.check(edn, ConsistencyProperty.LINEARIZABILITY, DataModel.REGISTER);
        boolean sequential = ConsistencyChecker.check(edn, ConsistencyProperty.SEQUENTIAL_CONSISTENCY, DataModel.REGISTER);
        
        // Assertions based on expected behavior
        assertFalse(linearizable, "Split-brain should violate linearizability");
        assertTrue(sequential, "Should maintain sequential consistency");
    }
}
```

Notes:
- The simulation uses a single-threaded tick model; randomness is fully controlled by the seed for reproducibility.
- EDN histories are stable and suitable for external analysis tools.

For more examples of using Jepsen, see `src/test/java/com/tickloom/JepsenTest.java`.

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

## Who Should Use Tickloom?

Tickloom is for you if you:
- Build distributed algorithms in Java
- Need deterministic, reproducible tests
- Want to simulate failures without non-deterministic chaos using single-threaded event loop architecture
- **Want to learn and understand distributed systems concepts used in state-of-the-art production systems**
- **Need deterministic simulation testing capabilities**
- **Want to implement Jepsen-style consistency checking to verify your algorithms maintain correctness under failures**

---
## Working with LLMs

LLMs are fascinating, but as we discussed in the article
on [conversational abstractions for LLMs](https://martinfowler.com/articles/convo-llm-abstractions.html), having stable
abstractions helps us quickly build code by using them as vocabulary in prompts. With the primitive abstractions
available in the TickLoom framework, I’ve found it relatively easy to quickly build example code for algorithms I want
to try out. Here’s an example prompt:
```
Refer to TickLoom QuorumKVStoreTest code and create tests that demonstrate clock-skew scenarios where a minority partition with higher 
timestamps can overwrite majority values after healing. 
Use Jepsen history recording to prove the system violates 
linearizability but maintains sequential consistency.
```
This prompt uses words like “TickLoom” and “Jepsen” but refers to a concrete implementation, making it easier for LLMs to have a
strong context to work with. LLMs are very new, and everyone is experimenting with them—let’s continue and see what the 
future holds.

## Acknowledgements
- Tickloom’s tick model and deterministic simulation approach are inspired by the excellent [TigerBeetle](https://github.com/tigerbeetle/tigerbeetle) project.
- Built using the Cursor and IntelliJ Idea editors.
- AI assistance: ChatGPT‑5 and Claude Sonnet.
  
---

## License
This project is licensed under the Apache License 2.0 – see the [LICENSE](LICENSE) file for details.
