## TickLoom

TickLoom is a lightweight framework for building deterministic, testable distributed systems in Java. It provides a fabric of ticking processes, a pluggable network, a simple messaging layer, and storage abstractions so you can focus on core algorithms like replication and consensus.

### Why TickLoom?
- Deterministic simulation with a tick() service-layer pattern
- Swap real networking with a simulated network for fast and reliable tests
- Simple, explicit messaging primitives and message bus
- Pluggable storage (RocksDB for production, in-memory for tests)
- “Testkit” module packaged from `src/test` to help consumers write rich integration tests

---

## Installation

Artifacts are published to Maven Central under the `io.github.unmeshjoshi` group.

Gradle (Kotlin DSL):
```kotlin
dependencies {
  implementation("io.github.unmeshjoshi:tickloom:0.1.0-alpha.2")
  testImplementation("io.github.unmeshjoshi:tickloom-testkit:0.1.0-alpha.2")
}
```

Maven:
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

Requirements:
- Java 21+

---

## Quick Start

Below is a minimal Echo example that shows how to build on TickLoom’s primitives:
- `EchoServer` extends `Process`
- `EchoClient` extends `ClusterClient`
- A JUnit test uses the `Cluster` testkit and `assertEventually`

EchoServer and EchoClient:
```java
import com.tickloom.Process;
import com.tickloom.ProcessId;
import com.tickloom.messaging.Message;
import com.tickloom.messaging.MessageBus;
import com.tickloom.messaging.MessageType;
import com.tickloom.network.MessageCodec;
import com.tickloom.util.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class EchoServer extends Process {
  public static final MessageType ECHO_REQUEST = MessageType.of("EchoRequest");
  public static final MessageType ECHO_RESPONSE = MessageType.of("EchoResponse");

  public EchoServer(ProcessId id, MessageBus bus, MessageCodec codec, Clock clock, int timeoutTicks) {
    super(id, bus, codec, timeoutTicks, clock);
  }

  @Override
  protected Map<MessageType, Handler> initialiseHandlers() {
    Map<MessageType, Handler> handlers = new HashMap<>();
    handlers.put(ECHO_REQUEST, this::onEchoRequest);
    return handlers;
  }

  private void onEchoRequest(Message message) {
    String payload = deserializePayload(message.payload(), String.class);
    Message response = createResponseMessage(message, payload, ECHO_RESPONSE);
    try { messageBus.sendMessage(response); } catch (Exception ignored) {}
  }
}

import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.ProcessId;
import com.tickloom.future.ListenableFuture;
import com.tickloom.messaging.Message;
import com.tickloom.messaging.MessageBus;
import com.tickloom.messaging.MessageType;
import com.tickloom.network.MessageCodec;
import com.tickloom.util.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class EchoClient extends ClusterClient {
  public EchoClient(ProcessId clientId, List<ProcessId> endpoints,
                    MessageBus bus, MessageCodec codec, Clock clock, int timeoutTicks) {
    super(clientId, endpoints, bus, codec, clock, timeoutTicks);
  }

  @Override
  protected Map<MessageType, Handler> initialiseHandlers() {
    Map<MessageType, Handler> handlers = new HashMap<>();
    handlers.put(EchoServer.ECHO_RESPONSE, this::onEchoResponse);
    return handlers;
  }

  private void onEchoResponse(Message message) {
    String payload = deserialize(message.payload(), String.class);
    handleResponse(message.correlationId(), payload, message.source());
  }

  public ListenableFuture<String> echo(String message) {
    // Send to first endpoint for simplicity
    return sendRequest(message, getReplicaEndpoints().get(0), EchoServer.ECHO_REQUEST);
  }
}
```

JUnit test with the Cluster testkit:
```java
import com.tickloom.ProcessId;
import com.tickloom.testkit.Cluster;
import static com.tickloom.testkit.ClusterAssertions.assertEventually;
import org.junit.jupiter.api.Test;

public class EchoExampleTest {
  @Test
  void echo_roundtrip() throws Exception {
    Cluster cluster = new Cluster()
        .withNumProcesses(1)
        .useSimulatedNetwork()
        .withInitialClockTime(1)
        .build((id, peers, bus, codec, storage, clock, timeout) ->
            new EchoServer(id, bus, codec, clock, timeout))
        .start();

    EchoClient client = cluster.newClient(
        ProcessId.of("client-1"),
        (clientId, endpoints, bus, codec, clock, timeout) ->
            new EchoClient(clientId, endpoints, bus, codec, clock, timeout)
    );

    var future = client.echo("hello");
    assertEventually(cluster, () -> future.isCompleted() && "hello".equals(future.getResult()));

    cluster.close();
  }
}
```

---

## Testkit

The testkit is published as a separate artifact: `io.github.unmeshjoshi:tickloom-testkit`. It contains reusable helpers from this repository’s `src/test` that make it easy to spin up small clusters, control simulated time, and assert replication behavior.

What’s inside (high-level):
- `com.tickloom.testkit.Cluster` – spin up a network of processes/replicas
- `com.tickloom.testkit.ClusterAssertions` – convenience assertions
- `com.tickloom.testkit.NodeGroup` – grouping helpers for partitions/chaos
- `com.tickloom.util.StubClock` – deterministic time source

Usage example:
```java
import com.tickloom.testkit.Cluster;
import com.tickloom.ProcessId;

Cluster cluster = Cluster.withReplicas(3);
cluster.start();

// Interact with the cluster using your client code
// cluster.tickAll();

cluster.stop();
```

Note: The testkit classes are intended for test scope. Add it with `testImplementation`/`testCompile` only.

---

## Key Concepts

- Process (`com.tickloom.Process`): Base class for deterministic, event-driven logic using a `tick()` loop.
- Messaging (`com.tickloom.messaging.*`): `Message`, `MessageBus`, `MessageType`, callbacks, request/response correlation.
- Network (`com.tickloom.network.*`):
  - `SimulatedNetwork`: deterministic delivery with configurable delays, partitions, and loss
  - `NioNetwork`: non-blocking networking for real deployments
- Storage (`com.tickloom.storage.*`): `Storage`, `RocksDbStorage`, `SimulatedStorage`, `VersionedValue`, etc.
- Replication algorithms (examples): `algorithms/replication/quorum/*` illustrates quorum-based replication.

---

## Building & Testing

```bash
# Full build
./gradlew build

# Run tests
./gradlew test

# Generate javadoc & sources jars
./gradlew javadocJar sourcesJar
```

---

## Publishing & Release (for maintainers)

The build is wired for the Central Portal bundle upload.

Central Portal bundle:
```bash
./gradlew signMavenJavaPublication signMavenTestkitPublication \
         prepareCentralBundle createCentralBundle -x test
# Upload build/distributions/tickloom-<version>-bundle-<version>.zip
```

Signing & credentials setup, tokens, and troubleshooting are documented in:
- `SIGNING_README.md`

---

## Project Structure

- `src/main/java/com/tickloom/`
  - `algorithms/replication/quorum/` – quorum-replication example
  - `config/` – configuration and topology
  - `messaging/` – message types and bus
  - `network/` – network abstractions and implementations
  - `storage/` – storage abstractions and RocksDB integration
  - `Process.java`, `Replica.java`, `ProcessId.java` – core primitives

- `src/test/java/com/tickloom/` – tests and testkit sources
  - Published as `tickloom-testkit` for consumers’ test scope

---

## License

Apache License 2.0. See the `LICENSE` file or the POM’s license section.