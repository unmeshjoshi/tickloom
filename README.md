# TickLoom

A fabric of ticking processes.

## Overview

TickLoom is a distributed system framework that provides a fabric of ticking processes for building scalable, fault-tolerant applications.

## Features

- Distributed process management
- Network communication layer
- Message passing between processes
- Cluster topology configuration
- Replica management

## Getting Started

### Prerequisites

- Java 21
- Gradle

### Building the Project

```bash
./gradlew build
```

### Running Tests

```bash
./gradlew test
```

## Project Structure

- `src/main/java/com/tickloom/` - Main source code
  - `config/` - Configuration classes
  - `messaging/` - Message handling and types
  - `network/` - Network communication layer
  - `Process.java` - Core process implementation
  - `ProcessId.java` - Process identification
  - `Replica.java` - Replica management

- `src/test/java/com/tickloom/` - Test code
- `src/test/resources/` - Test resources

## License

[Add your license information here] 