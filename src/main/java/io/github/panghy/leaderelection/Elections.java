package io.github.panghy.leaderelection;

import static io.github.panghy.leaderelection.Keys.configKey;

import com.apple.foundationdb.Database;
import com.apple.foundationdb.subspace.Subspace;
import com.apple.foundationdb.tuple.Tuple;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Factory helpers to build LeaderElection instances. */
public final class Elections {
  private static final Logger log = LoggerFactory.getLogger(Elections.class);

  private Elections() {}

  /** Validate existing config (if any) and write if missing. Throws on mismatch unless overwrite enabled. */
  public static LeaderElection createOrOpen(ElectionConfig cfg) {
    Database db = cfg.getDatabase();
    byte[] key = configKey(cfg.getSubspace());
    db.run(tr -> {
      byte[] existing = tr.get(key).join();
      if (existing == null) {
        Tuple t = Tuple.from(cfg.getHeartbeatTimeout().getSeconds(), cfg.isElectionEnabled());
        tr.set(key, t.pack());
        log.info(
            "Wrote initial election config: timeout={}s enabled={}",
            cfg.getHeartbeatTimeout().getSeconds(),
            cfg.isElectionEnabled());
        return null;
      }
      Tuple t = Tuple.fromBytes(existing);
      long timeoutSecs = (Long) t.get(0);
      boolean enabled = (Boolean) t.get(1);
      boolean matches =
          timeoutSecs == cfg.getHeartbeatTimeout().getSeconds() && enabled == cfg.isElectionEnabled();
      if (!matches) {
        if (cfg.isOverwriteExistingConfig()) {
          log.warn(
              "Overwriting existing election config: existing(timeout={}s,enabled={}) ->"
                  + " new(timeout={}s,enabled={})",
              timeoutSecs,
              enabled,
              cfg.getHeartbeatTimeout().getSeconds(),
              cfg.isElectionEnabled());
          tr.set(
              key,
              Tuple.from(cfg.getHeartbeatTimeout().getSeconds(), cfg.isElectionEnabled())
                  .pack());
        } else {
          throw new LeaderElectionException(String.format(
              "Existing config mismatch: existing(timeout=%ds,enabled=%s) vs"
                  + " requested(timeout=%ds,enabled=%s)",
              timeoutSecs, enabled, cfg.getHeartbeatTimeout().getSeconds(), cfg.isElectionEnabled()));
        }
      }
      return null;
    });
    return new FdbLeaderElection(cfg);
  }

  /** Convenience overloads building a config first. */
  public static LeaderElection createOrOpen(Database db, Subspace subspace) {
    return createOrOpen(ElectionConfig.builder(db, subspace).build());
  }

  public static LeaderElection createOrOpen(Database db, Subspace subspace, Duration heartbeatTimeout) {
    return createOrOpen(ElectionConfig.builder(db, subspace)
        .heartbeatTimeout(heartbeatTimeout)
        .build());
  }

  public static LeaderElection createOrOpen(
      Database db, Subspace subspace, Duration heartbeatTimeout, boolean electionEnabled) {
    return createOrOpen(ElectionConfig.builder(db, subspace)
        .heartbeatTimeout(heartbeatTimeout)
        .electionEnabled(electionEnabled)
        .build());
  }
}
