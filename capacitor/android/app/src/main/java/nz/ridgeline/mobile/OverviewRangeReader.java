package nz.ridgeline.mobile;

import java.io.Closeable;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Reads a bounded image directly from an uncompressed APK asset. */
final class OverviewRangeReader {
  static InputStream open(
      FileInputStream in, long start, long assetLength, long offset, int length, Closeable owner)
      throws IOException {
    if (start < 0
        || assetLength <= 0
        || offset < 0
        || length < 1
        || length > 1024 * 1024
        || offset > assetLength
        || length > assetLength - offset) throw new IOException("Invalid overview asset range.");
    final long position;
    try {
      position = Math.addExact(start, offset);
      Math.addExact(position, length - 1);
    } catch (ArithmeticException e) {
      throw new IOException("Invalid overview asset offset", e);
    }
    return new InputStream() {
      int read = 0;
      boolean closed;

      @Override
      public int read() throws IOException {
        byte[] b = new byte[1];
        return read(b, 0, 1) < 0 ? -1 : b[0] & 255;
      }

      @Override
      public int read(byte[] data, int off, int count) throws IOException {
        java.util.Objects.checkFromIndexSize(off, count, data.length);
        if (closed) throw new IOException("Overview stream closed");
        if (count == 0) return 0;
        if (read == length) return -1;
        int n =
            in.getChannel()
                .read(ByteBuffer.wrap(data, off, Math.min(count, length - read)), position + read);
        if (n < 0) throw new EOFException("Bundled overview image is truncated.");
        if (n == 0) throw new IOException("Overview asset could not be read.");
        read += n;
        return n;
      }

      @Override
      public void close() throws IOException {
        if (!closed) {
          closed = true;
          try {
            in.close();
          } finally {
            owner.close();
          }
        }
      }
    };
  }

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
