package io.github.panghy.leaderelection;

/**
 * Error type for leader election operations.
 * <p>Mirrors the Rust recipe's error surface with a single runtime exception carrying
 * contextual messages. Helper factory methods provide common cases.</p>
 */
public final class LeaderElectionException extends RuntimeException {
  public LeaderElectionException(String message) {
    super(message);
  }

  public LeaderElectionException(String message, Throwable cause) {
    super(message, cause);
  }

  /** Election is administratively disabled. */
  public static LeaderElectionException electionDisabled() {
    return new LeaderElectionException("Election is currently disabled");
  }
  /** Global configuration not initialized. */
  public static LeaderElectionException notInitialized() {
    return new LeaderElectionException("Global configuration not initialized");
  }
}
