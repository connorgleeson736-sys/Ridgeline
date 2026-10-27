package nz.ridgeline.mobile;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class OverviewStreamTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void readsExactImageWithoutLeakingAssetPrefixOrFollowingImage() throws Exception {
    File file = temp.newFile();
    Files.write(file.toPath(), new byte[] {9, 9, 1, 2, 3, 4, 8, 8});
    AtomicInteger closes = new AtomicInteger();
    FileInputStream in = new FileInputStream(file);
    try (InputStream stream = OverviewRangeReader.open(in, 2, 4, 1, 2, closes::incrementAndGet)) {
      assertArrayEquals(new byte[] {2, 3}, stream.readAllBytes());
      assertEquals(-1, stream.read());
    }
    assertFalse(in.getChannel().isOpen());
    assertEquals(1, closes.get());
  }

  @Test
  public void truncatedSourceFailsInsteadOfReturningASuccessfulImage() throws Exception {
    File f = temp.newFile();
    Files.write(f.toPath(), new byte[] {1});
    try (FileInputStream in = new FileInputStream(f);
        InputStream stream = OverviewRangeReader.open(in, 0, 4, 0, 4, () -> {})) {
      assertThrows(EOFException.class, stream::readAllBytes);
    }
  }

  @Test
  public void rejectsOverflowAndOutOfAssetRanges() throws Exception {
    try (FileInputStream in = new FileInputStream(temp.newFile())) {
      assertThrows(
          IOException.class,
          () -> OverviewRangeReader.open(in, Long.MAX_VALUE, 20, 1, 4, () -> {}));
      assertThrows(IOException.class, () -> OverviewRangeReader.open(in, 0, 4, 3, 2, () -> {}));
    }
  }
}
