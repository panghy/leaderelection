package io.github.panghy.leaderelection;

import static org.assertj.core.api.Assertions.assertThat;

import com.apple.foundationdb.Database;
import com.apple.foundationdb.FDB;
import com.apple.foundationdb.directory.DirectoryLayer;
import com.apple.foundationdb.directory.DirectorySubspace;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ElectionsFactoryTest {
  Database db;
  DirectorySubspace dir;

  @BeforeEach
  void setup() throws Exception {
    db = FDB.selectAPIVersion(730).open();
    dir = db.runAsync(tr -> DirectoryLayer.getDefault()
            .createOrOpen(
                tr,
                List.of(
                    "leaderelection-factory",
                    UUID.randomUUID().toString()),
                "leaderelection".getBytes(StandardCharsets.UTF_8)))
        .get(10, TimeUnit.SECONDS);
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
  void factoryCreatesWorkingInstance() throws Exception {
    LeaderElection le = Elections.createOrOpen(db, dir);

    String id = "factory-proc";
    Instant now = Instant.ofEpochMilli(1234);
    le.registerProcess(id, now).get(5, TimeUnit.SECONDS);
    boolean became = le.tryBecomeLeader(id, now).get(5, TimeUnit.SECONDS);
    assertThat(became).isTrue();

    // Idempotent open with same config should succeed without overwrite
    LeaderElection le2 = Elections.createOrOpen(ElectionConfig.builder(db, dir)
        .heartbeatTimeout(java.time.Duration.ofSeconds(10))
        .electionEnabled(true)
        .build());
    assertThat(le2).isNotNull();
  }
}
