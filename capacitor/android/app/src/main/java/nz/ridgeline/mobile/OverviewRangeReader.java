package nz.ridgeline.mobile;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Reads a bounded image directly from an uncompressed APK asset. */
final class OverviewRangeReader {
  static byte[] read(
      FileChannel channel, long assetStart, long assetLength, long offset, int length)
      throws IOException {
    if (assetStart < 0
        || assetLength <= 0
        || offset < 0
        || offset > assetLength
        || length < 1
        || length > 1024 * 1024
        || length > assetLength - offset) throw new IOException("Invalid overview asset range.");
    byte[] bytes = new byte[length];
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    try {
      long start = Math.addExact(assetStart, offset);
      while (buffer.hasRemaining()) {
        int n = channel.read(buffer, Math.addExact(start, buffer.position()));
        if (n < 0) throw new EOFException("Bundled overview image is truncated.");
        if (n == 0) throw new IOException("Overview asset could not be read.");
      }
    } catch (ArithmeticException e) {
      throw new IOException("Invalid overview asset offset.", e);
    }
    return bytes;
  }
}
