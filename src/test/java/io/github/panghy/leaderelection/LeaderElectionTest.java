package io.github.panghy.leaderelection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.apple.foundationdb.Database;
import com.apple.foundationdb.FDB;
import com.apple.foundationdb.directory.DirectoryLayer;
import com.apple.foundationdb.directory.DirectorySubspace;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LeaderElectionTest {
  Database db;
  DirectorySubspace dir;
  LeaderElection election;
  ElectionConfig cfg;

  @BeforeEach
  void setup() throws Exception {
    db = FDB.selectAPIVersion(730).open();
    dir = db.runAsync(tr -> DirectoryLayer.getDefault()
            .createOrOpen(
                tr,
                List.of("leaderelection-test", UUID.randomUUID().toString()),
                "leaderelection".getBytes(StandardCharsets.UTF_8)))
        .get(10, TimeUnit.SECONDS);
    cfg = ElectionConfig.builder(db, dir).build();
    election = Elections.createOrOpen(cfg);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (db != null && dir != null) {
      db.runAsync(tr -> {
            dir.remove(tr);
            return CompletableFuture.completedFuture(null);
          })
          .get(5, TimeUnit.SECONDS);
      db.close();
    }
  }

  @Test
  void configRoundTrip() throws Exception {
    var stored = db.readAsync(tr -> tr.get(Keys.configKey(dir))).get(5, TimeUnit.SECONDS);
    var t = com.apple.foundationdb.tuple.Tuple.fromBytes(stored);
    assertThat((Long) t.get(0)).isEqualTo(ElectionConfig.DEFAULT_HEARTBEAT_TIMEOUT.getSeconds());
    assertThat((Boolean) t.get(1)).isTrue();

    ElectionConfig newCfg = ElectionConfig.builder(db, dir)
        .heartbeatTimeout(Duration.ofSeconds(3))
        .electionEnabled(false)
        .build();
    assertThatThrownBy(() -> Elections.createOrOpen(newCfg))
        .hasCauseInstanceOf(LeaderElectionException.class)
        .hasMessageContaining("mismatch");
    ElectionConfig overwriteCfg = ElectionConfig.builder(db, dir)
        .heartbeatTimeout(Duration.ofSeconds(3))
        .electionEnabled(false)
        .overwriteExistingConfig(true)
        .build();
    election = Elections.createOrOpen(overwriteCfg);
    var stored2 = db.readAsync(tr -> tr.get(Keys.configKey(dir))).get(5, TimeUnit.SECONDS);
    var t2 = com.apple.foundationdb.tuple.Tuple.fromBytes(stored2);
    assertThat((Long) t2.get(0)).isEqualTo(3L);
    assertThat((Boolean) t2.get(1)).isFalse();
  }

  @Test
  void basicElectionFlow() throws Exception {
    String p1 = "p1";
    String p2 = "p2";
    Instant t0 = Instant.ofEpochMilli(1_000);

    election.registerProcess(p1, t0).get(5, TimeUnit.SECONDS);
    election.registerProcess(p2, t0.plusMillis(1)).get(5, TimeUnit.SECONDS);

    boolean p1Leader = election.tryBecomeLeader(p1, t0).get(5, TimeUnit.SECONDS);
    assertThat(p1Leader).isTrue();

    LeaderInfo info = election.getCurrentLeader(t0).get(5, TimeUnit.SECONDS);
    assertThat(info).isNotNull();
    Boolean p1Is = election.isLeader(p1, t0).get(5, TimeUnit.SECONDS);
    Boolean p2Is = election.isLeader(p2, t0).get(5, TimeUnit.SECONDS);
    assertThat(p1Is).isTrue();
    assertThat(p2Is).isFalse();
  }

  @Test
  void leaderExpiresByTimeout() throws Exception {
    String id = "p1";
    Instant t0 = Instant.ofEpochMilli(1_000);
    election.registerProcess(id, t0).get(5, TimeUnit.SECONDS);
    election.tryBecomeLeader(id, t0).get(5, TimeUnit.SECONDS);

    // Advance beyond default heartbeat timeout (10s)
    Instant later = t0.plusSeconds(15);
    LeaderInfo info = election.getCurrentLeader(later).get(5, TimeUnit.SECONDS);
    assertThat(info).isNull();
  }

  @Test
  void disabledElectionPreventsOps() throws Exception {
    ElectionConfig disabled = ElectionConfig.builder(db, dir)
        .electionEnabled(false)
        .overwriteExistingConfig(true)
        .build();
    election = Elections.createOrOpen(disabled);

    assertThatThrownBy(() -> db.run(tr -> {
          election.registerProcess(tr, "x", Instant.now()).join();
          return null;
        }))
        .hasCauseInstanceOf(LeaderElectionException.class)
        .hasMessageContaining("disabled");

    assertThatThrownBy(() -> db.run(tr -> {
          election.heartbeat(tr, "x", Instant.now()).join();
          return null;
        }))
        .hasCauseInstanceOf(LeaderElectionException.class)
        .hasMessageContaining("disabled");
  }

  @Test
  void evictsDeadProcesses() throws Exception {
    String p1 = "p1";
    String p2 = "p2";
    Instant t0 = Instant.ofEpochMilli(10_000);
    election.registerProcess(p1, t0).get(5, TimeUnit.SECONDS);
    election.registerProcess(p2, t0).get(5, TimeUnit.SECONDS);

    // Only p1 heartbeats; p2 becomes stale
    Instant t1 = t0.plusSeconds(12);
    election.heartbeat(p1, t1).get(5, TimeUnit.SECONDS);

    // p1 tries to become leader and should evict p2 as dead
    election.tryBecomeLeader(p1, t1).get(5, TimeUnit.SECONDS);

    // Check that p2 key is gone
    Boolean p2Exists = db.readAsync(tr -> tr.get(Keys.processKey(dir, p2)).thenApply(v -> v != null))
        .get(5, TimeUnit.SECONDS);
    assertThat(p2Exists).isFalse();
  }

  @Test
  void readConfigNotInitializedThrows() throws Exception {
    DirectorySubspace fresh = db.runAsync(tr -> DirectoryLayer.getDefault()
            .createOrOpen(
                tr,
                List.of("leaderelection-test", UUID.randomUUID().toString()),
                "leaderelection".getBytes(StandardCharsets.UTF_8)))
        .get(10, TimeUnit.SECONDS);
    LeaderElection le =
        Elections.createOrOpen(ElectionConfig.builder(db, fresh).build());
    db.runAsync(tr -> {
          fresh.remove(tr);
          return CompletableFuture.completedFuture(null);
        })
        .get(5, TimeUnit.SECONDS);
  }

  @Test
  void isLeaderFallsBackWhenNoLeaderState() throws Exception {
    String a = "a";
    String b = "b";
    Instant t0 = Instant.ofEpochMilli(1_000);
    election.registerProcess(a, t0).get(5, TimeUnit.SECONDS);
    election.registerProcess(b, t0.plusMillis(1)).get(5, TimeUnit.SECONDS);
    // No leader_state yet
    Boolean aIs = election.isLeader(a, t0).get(5, TimeUnit.SECONDS);
    assertThat(aIs).isTrue();
  }

  @Test
  void tryBecomeLeaderDisabledThrows() throws Exception {
    election = Elections.createOrOpen(ElectionConfig.builder(db, dir)
        .electionEnabled(false)
        .overwriteExistingConfig(true)
        .build());
    assertThatThrownBy(() -> election.tryBecomeLeader("x", Instant.now()).join())
        .hasCauseInstanceOf(LeaderElectionException.class)
        .hasMessageContaining("disabled");
  }
}
