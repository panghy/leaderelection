package io.github.panghy.leaderelection;

import static io.github.panghy.leaderelection.Keys.configKey;
import static io.github.panghy.leaderelection.Keys.leaderStateKey;
import static io.github.panghy.leaderelection.Keys.processKey;
import static io.github.panghy.leaderelection.Keys.processesRange;
import static java.util.concurrent.CompletableFuture.completedFuture;

import com.apple.foundationdb.KeyValue;
import com.apple.foundationdb.MutationType;
import com.apple.foundationdb.Range;
import com.apple.foundationdb.ReadTransaction;
import com.apple.foundationdb.Transaction;
import com.apple.foundationdb.async.AsyncIterable;
import com.apple.foundationdb.tuple.Tuple;
import com.apple.foundationdb.tuple.Versionstamp;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FoundationDB-backed implementation of {@link LeaderElection}.
 * <p>Uses Versionstamps for ordering and timestamp-based liveness checks.</p>
 */
public final class FdbLeaderElection implements LeaderElection {
  private static final Logger log = LoggerFactory.getLogger(FdbLeaderElection.class);
  private final ElectionConfig config;

  public FdbLeaderElection(ElectionConfig config) {
    this.config = Objects.requireNonNull(config);
  }

  @Override
  public ElectionConfig config() {
    return config;
  }

  private CompletableFuture<ElectionConfig> loadConfig(ReadTransaction tr) {
    return tr.get(configKey(config.getSubspace())).thenApply(val -> {
      if (val == null) throw LeaderElectionException.notInitialized();
      Tuple t = Tuple.fromBytes(val);
      long timeoutSecs = (Long) t.get(0);
      boolean enabled = (Boolean) t.get(1);
      return ElectionConfig.builder(config.getDatabase(), config.getSubspace())
          .heartbeatTimeout(Duration.ofSeconds(timeoutSecs))
          .electionEnabled(enabled)
          .build();
    });
  }

  // constructor defined above

  // Configuration is validated/persisted in Elections factory.

  /**
   * {@inheritDoc}
   */
  @Override
  public CompletableFuture<Void> registerProcess(Transaction tr, String uuid, Instant now) {
    return loadConfig(tr).thenAccept(cfg -> {
      if (!cfg.isElectionEnabled()) throw LeaderElectionException.electionDisabled();
      byte[] key = processKey(config.getSubspace(), uuid);
      long nanos = now.toEpochMilli() * 1_000_000L;
      byte[] payload = Tuple.from(Versionstamp.incomplete(0), nanos).packWithVersionstamp();
      tr.mutate(MutationType.SET_VERSIONSTAMPED_VALUE, key, payload);
      log.debug("registerProcess uuid={} nanos={}", uuid, nanos);
    });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public CompletableFuture<Void> heartbeat(Transaction tr, String uuid, Instant now) {
    return loadConfig(tr).thenAccept(cfg -> {
      if (!cfg.isElectionEnabled()) throw LeaderElectionException.electionDisabled();
      byte[] key = processKey(config.getSubspace(), uuid);
      long nanos = now.toEpochMilli() * 1_000_000L;
      byte[] payload = Tuple.from(Versionstamp.incomplete(0), nanos).packWithVersionstamp();
      tr.mutate(MutationType.SET_VERSIONSTAMPED_VALUE, key, payload);
      log.debug("heartbeat uuid={} nanos={}", uuid, nanos);
    });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public CompletableFuture<List<Map.Entry<String, ProcessDescriptor>>> findAliveProcesses(
      ReadTransaction tr, Instant now) {
    return loadConfig(tr).thenCompose(cfg -> {
      Range r = processesRange(config.getSubspace());
      AsyncIterable<KeyValue> it = tr.getRange(r);
      return it.asList().thenApply(list -> {
        List<Map.Entry<String, ProcessDescriptor>> alive = new ArrayList<>();
        for (KeyValue kv : list) {
          var keyT = config.getSubspace().unpack(kv.getKey());
          String uuid = (String) keyT.get(1);
          var valT = Tuple.fromBytes(kv.getValue());
          Versionstamp vs = (Versionstamp) valT.get(0);
          long tsNanos = (Long) valT.get(1);
          Instant ts = Instant.ofEpochSecond(0L, tsNanos);
          ProcessDescriptor desc = ProcessDescriptor.fromVersionstampAndTimestamp(vs.getBytes(), ts);
          if (desc.isAlive(now, cfg.getHeartbeatTimeout())) {
            alive.add(new AbstractMap.SimpleImmutableEntry<>(uuid, desc));
          }
        }
        alive.sort(Map.Entry.comparingByValue());
        log.debug("findAliveProcesses count={}", alive.size());
        return alive;
      });
    });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public CompletableFuture<Boolean> tryBecomeLeader(Transaction tr, String uuid, Instant now) {
    return loadConfig(tr).thenCompose(cfg -> {
      if (!cfg.isElectionEnabled()) throw LeaderElectionException.electionDisabled();
      return findAliveProcesses(tr, now).thenCompose(alive -> {
        if (!alive.isEmpty() && alive.get(0).getKey().equals(uuid)) {
          ProcessDescriptor leader = alive.get(0).getValue();
          return updateLeaderState(tr, leader)
              .thenCompose(v -> evictDeadProcesses(tr, alive))
              .thenApply(v -> {
                log.info(
                    "{} became leader (version={}, userVersion={})",
                    uuid,
                    leader.version,
                    leader.userVersion);
                return true;
              });
        }
        return completedFuture(false);
      });
    });
  }

  private CompletableFuture<Void> updateLeaderState(Transaction tr, ProcessDescriptor leader) {
    byte[] key = leaderStateKey(config.getSubspace());
    long nanos = leader.timestamp.toEpochMilli() * 1_000_000L;
    Tuple t = Tuple.from(leader.toVersionstampBytes(), nanos);
    tr.set(key, t.pack());
    return completedFuture(null);
  }

  private CompletableFuture<Void> evictDeadProcesses(
      Transaction tr, List<Map.Entry<String, ProcessDescriptor>> alive) {
    Set<String> aliveIds = new HashSet<>();
    for (var e : alive) aliveIds.add(e.getKey());
    Range r = processesRange(config.getSubspace());
    return tr.getRange(r).asList().thenAccept(all -> {
      for (var kv : all) {
        var keyT = config.getSubspace().unpack(kv.getKey());
        String id = (String) keyT.get(1);
        if (!aliveIds.contains(id)) tr.clear(kv.getKey());
      }
    });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public CompletableFuture<LeaderInfo> getCurrentLeader(ReadTransaction tr, Instant now) {
    byte[] key = leaderStateKey(config.getSubspace());
    return loadConfig(tr).thenCompose(cfg -> tr.get(key).thenApply(val -> {
      if (val == null) return null;
      Tuple t = Tuple.fromBytes(val);
      byte[] vsBytes = (byte[]) t.get(0);
      long nanos = (Long) t.get(1);
      ProcessDescriptor leader =
          ProcessDescriptor.fromVersionstampAndTimestamp(vsBytes, Instant.ofEpochSecond(0L, nanos));
      if (leader.isAlive(now, cfg.getHeartbeatTimeout())) return new LeaderInfo(leader);
      return null;
    }));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public CompletableFuture<Boolean> isLeader(ReadTransaction tr, String uuid, Instant now) {
    return loadConfig(tr).thenCompose(cfg -> {
      if (!cfg.isElectionEnabled()) throw LeaderElectionException.electionDisabled();
      return getCurrentLeader(tr, now).thenCompose(info -> {
        if (info != null) {
          byte[] pKey = processKey(config.getSubspace(), uuid);
          return tr.get(pKey).thenApply(val -> {
            if (val == null) return false;
            Tuple t = Tuple.fromBytes(val);
            Versionstamp vs = (Versionstamp) t.get(0);
            long nanos = (Long) t.get(1);
            ProcessDescriptor self = ProcessDescriptor.fromVersionstampAndTimestamp(
                vs.getBytes(), Instant.ofEpochSecond(0L, nanos));
            return self.equals(info.leader());
          });
        }
        return findAliveProcesses(tr, now)
            .thenApply(alive ->
                !alive.isEmpty() && alive.get(0).getKey().equals(uuid));
      });
    });
  }
}
