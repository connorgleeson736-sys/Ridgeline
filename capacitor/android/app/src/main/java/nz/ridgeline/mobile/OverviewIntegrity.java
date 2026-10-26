package nz.ridgeline.mobile;

import java.io.*;
import java.security.MessageDigest;

/** Bounded streaming verification: no whole-archive allocation or unchecked extraction. */
final class OverviewIntegrity {
  static void copy(InputStream in, OutputStream out, long expected, String sha) throws Exception {
    if (expected <= 0 || expected > 64L * 1024 * 1024 || !sha.matches("[a-f0-9]{64}"))
      throw new IOException("Invalid overview pack metadata.");
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] buffer = new byte[32768];
    long bytes = 0;
    int n;
    while ((n = in.read(buffer)) != -1) {
      bytes += n;
      if (bytes > expected)
        throw new IOException("Overview pack is larger than its verified size.");
      digest.update(buffer, 0, n);
      out.write(buffer, 0, n);
      if (Thread.currentThread().isInterrupted())
        throw new InterruptedIOException(
            "Overview import interrupted. Finished packs are retained.");
    }
    StringBuilder hex = new StringBuilder();
    for (byte b : digest.digest())
      hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    if (bytes != expected || !sha.contentEquals(hex))
      throw new IOException("Overview pack checksum failed. Download the original ZIP again.");
  }
}
