package io.github.panghy.leaderelection;

import com.apple.foundationdb.Database;
import com.apple.foundationdb.subspace.Subspace;
import java.time.Duration;
import java.util.Objects;

/**
 * Global configuration for the election.
 * <p>Includes FoundationDB access ({@link #getDatabase()}) and the election subspace
 * ({@link #getSubspace()}) so high-level APIs can open transactions automatically.
 * Heartbeat timeout controls liveness detection via wall-clock timestamps, which are
 * immune to FDB recovery version jumps.</p>
 */
public final class ElectionConfig {
  /** Default heartbeat timeout (10 seconds). */
  public static final Duration DEFAULT_HEARTBEAT_TIMEOUT = Duration.ofSeconds(10);

  private final Database database;
  private final Subspace subspace;
  private final Duration heartbeatTimeout;
  private final boolean electionEnabled;
  private final boolean overwriteExistingConfig;

  private ElectionConfig(
      Database database,
      Subspace subspace,
      Duration heartbeatTimeout,
      boolean electionEnabled,
      boolean overwriteExistingConfig) {
    this.database = Objects.requireNonNull(database, "database");
    this.subspace = Objects.requireNonNull(subspace, "subspace");
    if (heartbeatTimeout == null || heartbeatTimeout.isNegative() || heartbeatTimeout.isZero()) {
      throw new IllegalArgumentException("heartbeatTimeout must be positive");
    }
    this.heartbeatTimeout = heartbeatTimeout;
    this.electionEnabled = electionEnabled;
    this.overwriteExistingConfig = overwriteExistingConfig;
  }

  /** Builder entrypoint. */
  public static Builder builder(Database database, Subspace subspace) {
    return new Builder(database, subspace);
  }

  public Database getDatabase() {
    return database;
  }

  public Subspace getSubspace() {
    return subspace;
  }

  public Duration getHeartbeatTimeout() {
    return heartbeatTimeout;
  }

  public boolean isElectionEnabled() {
    return electionEnabled;
  }

  public boolean isOverwriteExistingConfig() {
    return overwriteExistingConfig;
  }

  public static final class Builder {
    private final Database database;
    private final Subspace subspace;
    private Duration heartbeatTimeout = DEFAULT_HEARTBEAT_TIMEOUT;
    private boolean electionEnabled = true;
    private boolean overwriteExistingConfig = false;

    private Builder(Database database, Subspace subspace) {
      this.database = database;
      this.subspace = subspace;
    }

    public Builder heartbeatTimeout(Duration heartbeatTimeout) {
      this.heartbeatTimeout = Objects.requireNonNull(heartbeatTimeout);
      return this;
    }

    public Builder electionEnabled(boolean electionEnabled) {
      this.electionEnabled = electionEnabled;
      return this;
    }

    /** If true, the factory overwrites an existing mismatched config (WARN). */
    public Builder overwriteExistingConfig(boolean overwriteExistingConfig) {
      this.overwriteExistingConfig = overwriteExistingConfig;
      return this;
    }

    public ElectionConfig build() {
      return new ElectionConfig(database, subspace, heartbeatTimeout, electionEnabled, overwriteExistingConfig);
    }
  }
}
