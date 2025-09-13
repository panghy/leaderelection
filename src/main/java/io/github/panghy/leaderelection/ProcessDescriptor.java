package io.github.panghy.leaderelection;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Process descriptor using a hybrid: versionstamp (ordering) + timestamp (liveness).
 * Ordering intentionally ignores the timestamp so ordering is stable across recoveries.
 */
final class ProcessDescriptor implements Comparable<ProcessDescriptor> {
  final long version; // first 8 bytes of versionstamp
  final int userVersion; // last 2 bytes as unsigned short
  final Instant timestamp; // for timeout checks

  ProcessDescriptor(long version, int userVersion, Instant timestamp) {
    this.version = version;
    this.userVersion = userVersion & 0xFFFF;
    this.timestamp = Objects.requireNonNull(timestamp);
  }

  static ProcessDescriptor fromVersionstampAndTimestamp(byte[] vs12, Instant ts) {
    if (vs12.length != 12) throw new IllegalArgumentException("versionstamp must be 12 bytes");
    long v = 0;
    for (int i = 0; i < 8; i++) v = (v << 8) | (vs12[i] & 0xFF);
    int userV = ((vs12[10] & 0xFF) << 8) | (vs12[11] & 0xFF);
    return new ProcessDescriptor(v, userV, ts);
  }

  byte[] toVersionstampBytes() {
    byte[] out = new byte[12];
    for (int i = 0; i < 8; i++) {
      out[7 - i] = (byte) ((version >>> (i * 8)) & 0xFF);
    }
    out[10] = (byte) ((userVersion >>> 8) & 0xFF);
    out[11] = (byte) (userVersion & 0xFF);
    return out;
  }

  boolean isAlive(Instant now, Duration timeout) {
    return !now.isBefore(timestamp) && Duration.between(timestamp, now).compareTo(timeout) <= 0;
  }

  @Override
  public int compareTo(ProcessDescriptor o) {
    int c = Long.compare(this.version, o.version);
    if (c != 0) return c;
    return Integer.compare(this.userVersion, o.userVersion);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ProcessDescriptor that)) return false;
    return version == that.version && userVersion == that.userVersion;
  }

  @Override
  public int hashCode() {
    return Objects.hash(version, userVersion);
  }
}
