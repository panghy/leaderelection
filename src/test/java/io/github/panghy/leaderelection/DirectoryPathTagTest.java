package io.github.panghy.leaderelection;

import static org.assertj.core.api.Assertions.assertThat;

import com.apple.foundationdb.Database;
import com.apple.foundationdb.FDB;
import com.apple.foundationdb.subspace.Subspace;
import com.apple.foundationdb.tuple.Tuple;
import org.junit.jupiter.api.Test;

class DirectoryPathTagTest {

  @Test
  void nonDirectorySubspaceUsesHexPrefix() {
    Database db = FDB.selectAPIVersion(730).open();
    try {
      Subspace raw = new Subspace(Tuple.from("raw"));
      ElectionConfig cfg = ElectionConfig.builder(db, raw).build();
      FdbLeaderElection le = new FdbLeaderElection(cfg);
      String tag = le.directoryPathTag();
      assertThat(tag).isNotBlank();
      // Should be hex-ish
      assertThat(tag).matches("[0-9a-f]+");
    } finally {
      db.close();
    }
  }
}
