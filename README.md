# LeaderElection

A distributed leader election library backed by FoundationDB, providing reliable leader selection with automatic heartbeat management.

## Features

- **Leader election via FoundationDB Versionstamps** for strict ordering
- **Time-based heartbeats** for liveness detection
- **At-most-one leader guarantee** using serializable transactions
- **Automatic heartbeat management** with configurable intervals
- **Process registration and tracking** with unique process IDs
- **Configurable heartbeat timeout** for failure detection
- **Election enable/disable** without code changes
- **Immunity to FoundationDB recovery version jumps** using Duration/Instant timestamps
- **OpenTelemetry instrumentation** for distributed tracing and metrics
- **Built on FoundationDB** for reliability and scalability

## Installation

Add the following dependency to your project:

### Maven
```xml
<dependency>
    <groupId>io.github.panghy</groupId>
    <artifactId>leaderelection</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Gradle
```gradle
implementation 'io.github.panghy:leaderelection:0.1.0'
```

## Quick Start

```java
import com.apple.foundationdb.Database;
import com.apple.foundationdb.FDB;
import com.apple.foundationdb.directory.DirectoryLayer;
import io.github.panghy.leaderelection.ElectionConfig;
import io.github.panghy.leaderelection.Elections;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

// Initialize FoundationDB
FDB fdb = FDB.selectAPIVersion(730);
Database db = fdb.open();
DirectoryLayer directory = DirectoryLayer.getDefault();

// Create configuration asynchronously
directory.createOrOpen(db, List.of("myapp", "election"))
    .thenCompose(dirSubspace -> {
        ElectionConfig config = ElectionConfig.builder(db, dirSubspace)
            .heartbeatTimeout(Duration.ofSeconds(10))
            .electionEnabled(true)
            .build();

        // Create or open the election system
        return Elections.createOrOpen(config);
    })
    .thenCompose(election -> {
        String processId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        // Register this process
        return election.registerProcess(processId, now)
            .thenCompose(v -> {
                // Try to become leader
                return election.tryBecomeLeader(processId, now);
            })
            .thenAccept(becameLeader -> {
                if (becameLeader) {
                    System.out.println("This process is now the leader!");
                } else {
                    System.out.println("Failed to become leader");
                }
            });
    });

// Async worker with heartbeat
private CompletableFuture<Void> runLeaderAsync(
        LeaderElection election,
        String processId,
        Database db) {

    return AsyncUtil.whileTrue(() -> {
        Instant now = Instant.now();

        return election.isCurrentLeader(processId, now)
            .thenCompose(isLeader -> {
                if (isLeader) {
                    // Perform leader duties
                    return performLeaderWork()
                        .thenCompose(v -> {
                            // Send heartbeat
                            return election.heartbeat(processId, now);
                        })
                        .thenApply(v -> true);  // Continue loop
                } else {
                    // Not leader, try to become leader
                    return election.tryBecomeLeader(processId, now)
                        .thenApply(becameLeader -> {
                            if (becameLeader) {
                                System.out.println("Became leader!");
                            }
                            return true;  // Continue loop
                        });
                }
            })
            .thenCompose(continueLoop -> {
                // Wait before next iteration
                return AsyncUtil.delayedFuture(Duration.ofSeconds(5), db.getExecutor())
                    .thenApply(v -> continueLoop);
            });
    }, db.getExecutor());
}

// Example async leader work
private CompletableFuture<Void> performLeaderWork() {
    return CompletableFuture.supplyAsync(() -> {
        // Simulate leader work
        System.out.println("Performing leader duties...");
        return null;
    });
}
```

### Advanced Usage

```java
// Automatic heartbeat management
ElectionConfig configWithAutoHeartbeat = ElectionConfig.builder(db, dirSubspace)
    .heartbeatTimeout(Duration.ofSeconds(10))
    .electionEnabled(true)
    .autoHeartbeatEnabled(true)  // Enable automatic heartbeats
    .build();

// This will auto-start a background heartbeat loop for the given process
String processId = UUID.randomUUID().toString();
LeaderElection electionAuto = Elections.createOrOpen(configWithAutoHeartbeat, processId)
    .join();

// Or manually start/stop heartbeats
LeaderElection election = Elections.createOrOpen(config).join();
AutoCloseable heartbeatLoop = election.startAutoHeartbeat(processId).join();
// ... do work ...
heartbeatLoop.close();  // Stop heartbeats

// Check current leader
election.getLeader(Instant.now())
    .thenAccept(leaderInfo -> {
        if (leaderInfo != null) {
            System.out.println("Current leader: " + leaderInfo.processId());
            System.out.println("Leader since: " + leaderInfo.timestamp());
        } else {
            System.out.println("No current leader");
        }
    });

// List all registered processes
election.listProcesses(Instant.now())
    .thenAccept(processes -> {
        System.out.println("Active processes: " + processes.size());
        for (ProcessDescriptor process : processes) {
            System.out.println("  - " + process.processId() +
                             " (last heartbeat: " + process.timestamp() + ")");
        }
    });

// Resign leadership
election.resign(processId, Instant.now())
    .thenAccept(v -> {
        System.out.println("Resigned from leadership");
    });

// Use within transactions
db.runAsync(tr -> {
    return election.tryBecomeLeader(tr, processId, Instant.now())
        .thenCompose(becameLeader -> {
            if (becameLeader) {
                // Perform other transactional operations as leader
                return someLeaderOperation(tr);
            }
            return CompletableFuture.completedFuture(null);
        });
})
.thenAccept(result -> {
    System.out.println("Transaction completed");
});

// Disable election temporarily (useful for maintenance)
ElectionConfig disabledConfig = ElectionConfig.builder(db, dirSubspace)
    .heartbeatTimeout(Duration.ofSeconds(10))
    .electionEnabled(false)  // Disable election
    .build();

// Update configuration
Elections.updateConfig(disabledConfig)
    .thenAccept(v -> {
        System.out.println("Election disabled for maintenance");
    });
```

## Configuration Options

The `ElectionConfig` supports the following configuration options:

- **`heartbeatTimeout`**: Maximum time between heartbeats before a leader is considered dead (default: 10 seconds)
- **`electionEnabled`**: Enable or disable leader election (default: true)
- **`autoHeartbeatEnabled`**: Enable automatic heartbeat management (default: false)
- **`instantSource`**: Custom time source for testing (default: system time)

## Observability with OpenTelemetry

LeaderElection includes built-in OpenTelemetry instrumentation for distributed tracing and metrics collection. All major operations are automatically instrumented with spans and metrics.

### Tracing

The following operations create spans:
- `registerProcess` - Tracks process registration
- `tryBecomeLeader` - Tracks leader election attempts
- `heartbeat` - Tracks heartbeat operations
- `resign` - Tracks leadership resignation
- `getLeader` - Tracks leader queries
- `listProcesses` - Tracks process listing

Each span includes attributes:
- `election.directory_path` - Human-readable DirectorySubspace path or hex subspace key
- `election.heartbeat_timeout.seconds` - Configured heartbeat timeout
- `election.enabled` - Whether election is enabled
- `process.id` - Process identifier (where applicable)

### Metrics

The following metrics are automatically collected:
- `leaderelection.processes.registered` - Counter of processes registered
- `leaderelection.leader.elections` - Counter of successful leader elections
- `leaderelection.leader.resignations` - Counter of leader resignations
- `leaderelection.heartbeats.sent` - Counter of heartbeats sent
- `leaderelection.heartbeats.missed` - Counter of missed heartbeats
- `leaderelection.leader.duration` - Histogram of leadership duration (ms)

### Setup

To enable observability, configure OpenTelemetry in your application:

```java
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;

// Configure OpenTelemetry SDK (example with OTLP exporter)
OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
    .setTracerProvider(SdkTracerProvider.builder()
        // Add your span processor/exporter here
        .build())
    .setMeterProvider(SdkMeterProvider.builder()
        // Add your metric reader/exporter here
        .build())
    .buildAndRegisterGlobal();

// LeaderElection will automatically use the global OpenTelemetry instance
```

## API Reference

### Elections Factory Methods

Static factory methods for creating election instances:

- **`createOrOpen(config)`**: Create or open an election system → `CompletableFuture<LeaderElection>`
- **`createOrOpen(config, processId)`**: Create with auto-heartbeat for process → `CompletableFuture<LeaderElection>`
- **`updateConfig(config)`**: Update election configuration → `CompletableFuture<Void>`

### LeaderElection Methods

All methods return `CompletableFuture` for async operations:

- **`registerProcess(processId, now)`**: Register a new process → `CompletableFuture<Void>`
- **`tryBecomeLeader(processId, now)`**: Attempt to become leader → `CompletableFuture<Boolean>`
- **`heartbeat(processId, now)`**: Send heartbeat for process → `CompletableFuture<Void>`
- **`resign(processId, now)`**: Resign from leadership → `CompletableFuture<Void>`
- **`getLeader(now)`**: Get current leader info → `CompletableFuture<LeaderInfo>`
- **`isCurrentLeader(processId, now)`**: Check if process is leader → `CompletableFuture<Boolean>`
- **`listProcesses(now)`**: List all active processes → `CompletableFuture<List<ProcessDescriptor>>`
- **`startAutoHeartbeat(processId)`**: Start automatic heartbeats → `CompletableFuture<AutoCloseable>`

### LeaderInfo Methods

Information about the current leader (all synchronous):

- **`processId()`**: Get the leader's process ID
- **`timestamp()`**: Get when this process became leader
- **`versionstamp()`**: Get the FoundationDB versionstamp

### ProcessDescriptor Methods

Information about a registered process (all synchronous):

- **`processId()`**: Get the process ID
- **`timestamp()`**: Get the last heartbeat timestamp
- **`versionstamp()`**: Get the FoundationDB versionstamp

## Requirements

- Java 17 or higher
- FoundationDB 7.3 or higher

## Building from Source

```bash
./gradlew clean build
```

## Testing

The project includes comprehensive tests covering all functionality:

```bash
# Run all tests
./gradlew test

# Run only LeaderElection tests
./gradlew test --tests LeaderElectionTest

# Run tests with coverage report
./gradlew test jacocoTestReport
```

### Test Coverage

The test suite includes comprehensive coverage of:

- **Basic Operations**: Process registration, leader election, heartbeats, and resignation
- **Leader Selection**: Versionstamp-based ordering and leader determination
- **Heartbeat Management**: Manual and automatic heartbeat loops
- **Failure Detection**: Timeout-based leader detection and failover
- **Concurrent Elections**: Multiple processes competing for leadership
- **Configuration Changes**: Enabling/disabling elections, updating timeouts
- **Edge Cases**: Network partitions, process crashes, and recovery scenarios
- **Transactional Consistency**: Atomic leadership transitions

All tests use simulated time (no `Thread.sleep()`) for reliable and fast execution.

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.