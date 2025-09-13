package io.github.panghy.leaderelection;

import com.apple.foundationdb.subspace.Subspace;
import com.apple.foundationdb.tuple.Tuple;

/**
 * Key/subspace utilities. Layout (within the election subspace):
 * <ul>
 *   <li>config</li>
 *   <li>processes/{processUuid}</li>
 *   <li>leader_state</li>
 * </ul>
 */
final class Keys {
  static final String CONFIG_PREFIX = "config";
  static final String PROCESSES_PREFIX = "processes";
  static final String LEADER_STATE_PREFIX = "leader_state";

  static byte[] configKey(Subspace sub) {
    return sub.pack(Tuple.from(CONFIG_PREFIX));
  }

  static byte[] processKey(Subspace sub, String uuid) {
    return sub.pack(Tuple.from(PROCESSES_PREFIX, uuid));
  }

  static com.apple.foundationdb.Range processesRange(Subspace sub) {
    Subspace range = sub.subspace(Tuple.from(PROCESSES_PREFIX));
    return range.range();
  }

  static byte[] leaderStateKey(Subspace sub) {
    return sub.pack(Tuple.from(LEADER_STATE_PREFIX));
  }
}
