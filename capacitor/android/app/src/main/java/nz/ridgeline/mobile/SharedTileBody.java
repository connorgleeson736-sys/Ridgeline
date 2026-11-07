package nz.ridgeline.mobile;

import java.io.*;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** Stream identical encoded bytes to readers; EOF is withheld until validation succeeds. */
final class SharedTileBody {
  static final class TooLarge extends IOException {
    TooLarge() {
      super("Map tile exceeds the download size limit.");
    }
  }

  private byte[] buffer;
  private int size;
  private boolean complete;
  private IOException error;
  private final int max;

  SharedTileBody(long length, int max) {
    this.max = max;
    buffer = new byte[(int) Math.min(262144, Math.max(1024, length < 0 ? 16384 : length))];
  }

  synchronized void append(byte[] data, int length) throws IOException {
    if ((long) size + length > max) throw new TooLarge();
    if (size + length > buffer.length)
      buffer = Arrays.copyOf(buffer, Math.min(max, Math.max(size + length, buffer.length * 2)));
    System.arraycopy(data, 0, buffer, size, length);
    size += length;
    notifyAll();
  }

  synchronized byte[] snapshot() {
    return buffer.length == size ? buffer : Arrays.copyOf(buffer, size);
  }

  synchronized void finish(byte[] exact) {
    buffer = exact;
    complete = true;
    notifyAll();
  }

  synchronized void fail(IOException e) {
    if (complete) return;
    error = e;
    buffer = new byte[0];
    size = 0;
    notifyAll();
  }

  synchronized void wake() {
    notifyAll();
  }

  synchronized byte[] bytes() throws IOException {
    while (!complete && error == null) waitForData();
    if (error != null) throw error;
    return buffer;
  }

  synchronized byte[] bytes(BooleanSupplier cancelled) throws IOException {
    while (!complete && error == null && !cancelled.getAsBoolean()) waitForData();
    if (cancelled.getAsBoolean()) throw TileDispatcher.abort();
    if (error != null) throw error;
    return buffer;
  }

  private void waitForData() throws InterruptedIOException {
    try {
      wait();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw TileDispatcher.abort();
    }
  }

  InputStream stream(BooleanSupplier cancelled, Runnable release) {
    return new InputStream() {
      int position;
      volatile boolean closed;

      @Override
      public int read() throws IOException {
        synchronized (SharedTileBody.this) {
          while (true) {
            if (closed || cancelled.getAsBoolean()) throw TileDispatcher.abort();
            if (error != null) throw error;
            if (position < size) return buffer[position++] & 255;
            if (complete) return -1;
            waitForData();
          }
        }
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        java.util.Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) return 0;
        synchronized (SharedTileBody.this) {
          while (true) {
            if (closed || cancelled.getAsBoolean()) throw TileDispatcher.abort();
            if (error != null) throw error;
            if (position < size) {
              int n = Math.min(len, size - position);
              System.arraycopy(buffer, position, b, off, n);
              position += n;
              return n;
            }
            if (complete) return -1;
            waitForData();
          }
        }
      }

      @Override
      public void close() {
        if (!closed) {
          closed = true;
          wake();
          release.run();
        }
      }
    };
  }
}
