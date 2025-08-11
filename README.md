<p align="left">
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

In TickLoom, **`tick()`** represents a single *lock step* of execution.  
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
While network and storage operations are **asynchronous in nature**, TickLoom models them explicitly within the tick loop:
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

TickLoom models time in terms of **ticks**, not real-world milliseconds.  
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

This model avoids the unpredictability of real-time timers and makes TickLoom suitable for **highly controlled distributed system testing**.


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

#TickLoom’s tick model and deterministic simulation approach are inspired by the excellent [TigerBeetle](https://github.com/tigerbeetle/tigerbeetle) project.
#ChatGPT-5 was used to brainstorm some ideas and generate some parts of this code.

---

## License
This project is licensed under the Apache License 2.0 – see the [LICENSE](LICENSE) file for details.
