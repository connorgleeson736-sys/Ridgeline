package nz.ridgeline.mobile;

import static org.junit.Assert.*;

import java.io.*;
import java.util.concurrent.*;
import org.junit.Test;

public class SharedTileBodyTest {
  @Test
  public void bytesStreamEarlyButEofWaitsForValidation() throws Exception {
    SharedTileBody b = new SharedTileBody(3, 10);
    InputStream in = b.stream(() -> false, () -> {});
    b.append(new byte[] {1, 2, 3}, 3);
    assertEquals(1, in.read());
    assertEquals(2, in.read());
    assertEquals(3, in.read());
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      Future<Integer> eof = worker.submit(() -> in.read());
      assertThrows(TimeoutException.class, () -> eof.get(50, TimeUnit.MILLISECONDS));
      b.finish(b.snapshot());
      assertEquals(-1, (int) eof.get(1, TimeUnit.SECONDS));
    } finally {
      worker.shutdownNow();
    }
  }

  @Test
  public void invalidBodyNeverEndsAsASuccessfulImage() throws Exception {
    SharedTileBody b = new SharedTileBody(3, 10);
    b.append(new byte[] {1, 2, 3}, 3);
    InputStream in = b.stream(() -> false, () -> {});
    assertEquals(1, in.read());
    b.fail(new IOException("Invalid image"));
    assertThrows(IOException.class, in::read);
    assertThrows(IOException.class, b::bytes);
  }

  @Test
  public void boundsAreEnforcedBeforeGrowingStorage() throws Exception {
    SharedTileBody b = new SharedTileBody(-1, 3);
    assertThrows(IOException.class, () -> b.append(new byte[4], 4));
  }
}
