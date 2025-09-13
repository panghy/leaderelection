package io.github.panghy.leaderelection;

import com.apple.foundationdb.ReadTransaction;
import com.apple.foundationdb.Transaction;
import com.apple.foundationdb.subspace.Subspace;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Leader Election API for FoundationDB-backed coordination.
 *
 * <p>Algorithm overview (adapted from the Rust recipe):
 * <ul>
 *   <li><b>Process registration</b>: each participant registers a unique id and receives a
 *       FoundationDB {@code Versionstamp} (assigned at commit).</li>
 *   <li><b>Heartbeat</b>: processes periodically write a new value preserving the versionstamp
 *       placement but updating a wall-clock timestamp for liveness.</li>
 *   <li><b>Leader selection</b>: among processes whose heartbeats are within the configured
 *       timeout, the one with the smallest versionstamp is the leader.</li>
 *   <li><b>Timeout management</b>: liveness is determined via timestamps (Instant/Duration),
 *       which remain correct across FDB recovery version jumps.</li>
 * </ul>
 *
 * <p>Safety and liveness:
 * <ul>
 *   <li><b>Integrity</b>: At most one leader at any time, ensured by serializable transactions
 *       and atomic operations.</li>
 *   <li><b>Termination</b>: A correct process eventually becomes leader if heartbeats continue.</li>
 *   <li><b>Observation</b>: Readers obtain the current leader via read-only operations.</li>
 * </ul>
 *
 * <p>All keys live under a user-provided {@link Subspace}; multiple elections can coexist.
 */
public interface LeaderElection {

  /**
   * Configuration backing this election instance.
   */
  ElectionConfig config();

  /**
   * Helper to run within a read-write transaction using the configured database.
   */
  default <R> CompletableFuture<R> runAsync(java.util.function.Function<Transaction, CompletableFuture<R>> fn) {
    return config().getDatabase().runAsync(fn);
  }

  /**
   * Helper to run within a read-only transaction using the configured database.
   */
  default <R> CompletableFuture<R> readAsync(java.util.function.Function<ReadTransaction, CompletableFuture<R>> fn) {
    return config().getDatabase().readAsync(fn);
  }

  // Configuration validation/persistence is handled by Elections.createOrOpen(...)

  /**
   * Register a process by id. Stores a versionstamp (ordering) and timestamp (liveness).
   *
   * @throws LeaderElectionException if election is disabled
   */
  CompletableFuture<Void> registerProcess(Transaction tr, String processId, Instant now);

  /**
   * Overload that supplies the transaction from {@link ElectionConfig#getDatabase()}.
   */
  default CompletableFuture<Void> registerProcess(String processId, Instant now) {
    return runAsync(tr -> registerProcess(tr, processId, now));
  }

  /**
   * Heartbeat for an existing process. Updates the liveness timestamp.
   *
   * @throws LeaderElectionException if election is disabled
   */
  CompletableFuture<Void> heartbeat(Transaction tr, String processId, Instant now);

  /**
   * Overload that supplies the transaction from {@link ElectionConfig#getDatabase()}.
   */
  default CompletableFuture<Void> heartbeat(String processId, Instant now) {
    return runAsync(tr -> heartbeat(tr, processId, now));
  }

  /**
   * Return alive processes (id, descriptor) sorted by priority (smallest versionstamp first).
   */
  CompletableFuture<List<Map.Entry<String, ProcessDescriptor>>> findAliveProcesses(ReadTransaction tr, Instant now);

  /**
   * Overload that supplies the transaction from {@link ElectionConfig#getDatabase()}.
   */
  default CompletableFuture<List<Map.Entry<String, ProcessDescriptor>>> findAliveProcesses(Instant now) {
    return readAsync(tr -> findAliveProcesses(tr, now));
  }

  /**
   * Attempt to become the leader if this id has the highest priority among alive processes.
   * <p>On success, updates leader state and evicts dead processes.</p>
   *
   * @return {@code true} if leadership was acquired in this transaction
   * @throws LeaderElectionException if election is disabled
   */
  CompletableFuture<Boolean> tryBecomeLeader(Transaction tr, String processId, Instant now);

  /**
   * Overload that supplies the transaction from {@link ElectionConfig#getDatabase()}.
   */
  default CompletableFuture<Boolean> tryBecomeLeader(String processId, Instant now) {
    return runAsync(tr -> tryBecomeLeader(tr, processId, now));
  }

  /**
   * Read-only: return the current leader if its heartbeat is still valid at {@code now}.
   */
  CompletableFuture<LeaderInfo> getCurrentLeader(ReadTransaction tr, Instant now);

  /**
   * Overload that supplies the transaction from {@link ElectionConfig#getDatabase()}.
   */
  default CompletableFuture<LeaderInfo> getCurrentLeader(Instant now) {
    return readAsync(tr -> getCurrentLeader(tr, now));
  }

  /**
   * Read-only: check if {@code processId} is the current leader at {@code now}. Falls back to
   * evaluating alive processes if no leader state exists.
   *
   * @throws LeaderElectionException if election is disabled
   */
  CompletableFuture<Boolean> isLeader(ReadTransaction tr, String processId, Instant now);

  /**
   * Overload that supplies the transaction from {@link ElectionConfig#getDatabase()}.
   */
  default CompletableFuture<Boolean> isLeader(String processId, Instant now) {
    return readAsync(tr -> isLeader(tr, processId, now));
  }

  /**
   * Start an automatic heartbeat loop for the given process id. The loop sends a heartbeat every
   * half of the configured timeout (e.g., default 5s) using a delayed executor (no Thread.sleep).
   * Returns an {@link AutoCloseable} that cancels the loop when {@code close()} is called.
   */
  AutoCloseable startAutoHeartbeat(String processId);
}
