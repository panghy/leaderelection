# LeaderElection

A FoundationDB-backed leader election library for Java. It mirrors the leader election recipe from the foundationdb-rs project, using versionstamps for ordering and time-based heartbeats for liveness.

## Design
- Ordering via FoundationDB Versionstamps (smallest wins).
- Liveness via timestamps (Duration/Instant), immune to FDB recovery version jumps.
- Serializable transactions ensure at-most-one leader.

## Usage
```java
var fdb = com.apple.foundationdb.FDB.selectAPIVersion(730);
var db = fdb.open();
var dir = com.apple.foundationdb.directory.DirectoryLayer.getDefault()
    .createOrOpen(db, java.util.List.of("myapp","election"))
    .join();
var cfg = io.github.panghy.leaderelection.ElectionConfig.builder(db, dir)
    .heartbeatTimeout(java.time.Duration.ofSeconds(10))
    .electionEnabled(true)
    .build();
// Validate existing or write new config, then return instance
var election = io.github.panghy.leaderelection.Elections.createOrOpen(cfg);
String processId = java.util.UUID.randomUUID().toString();
java.time.Instant now = java.time.Instant.now();

election.registerProcess(processId, now).join();
boolean becameLeader = election.tryBecomeLeader(processId, now).join();

// Auto Heartbeats
// Enable automatic heartbeats (every half timeout) by setting the config flag and
// supplying a process id at factory creation time. No Thread.sleep is used; the
// loop leverages AsyncUtil.whileTrue and a delayed executor on the FDB executor.

var cfgAuto = io.github.panghy.leaderelection.ElectionConfig.builder(db, dir)
    .heartbeatTimeout(java.time.Duration.ofSeconds(10))
    .electionEnabled(true)
    .autoHeartbeatEnabled(true)
    .build();

// This will auto-start a background heartbeat loop for the given process id
var election2 = io.github.panghy.leaderelection.Elections.createOrOpen(cfgAuto, processId);

// Alternatively, start/stop the heartbeat manually
var closer = election.startAutoHeartbeat(processId);
// ... later ...
closer.close();

// Telemetry
// Spans and metrics are emitted via OpenTelemetry using GlobalOpenTelemetry.
// Spans include attributes:
// - election.directory_path (human-readable DirectorySubspace path or hex subspace key)
// - election.heartbeat_timeout.seconds, election.enabled, process.id (where applicable)
```

## Build
- Requires Java 17 and a local/running FoundationDB client.
- `./gradlew clean build` (tests require FDB API version 7.3 and a reachable cluster file).

## License
Apache-2.0
