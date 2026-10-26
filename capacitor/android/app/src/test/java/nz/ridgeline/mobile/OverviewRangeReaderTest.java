package nz.ridgeline.mobile;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.Files;
import org.junit.Test;

public class OverviewRangeReaderTest {
  @Test
  public void respectsTheNonzeroApkAssetOffset() throws Exception {
    File file = File.createTempFile("ridgeline-asset", ".bin");
    try {
      Files.write(file.toPath(), new byte[] {90, 91, 92, 10, 11, 12, 13, 14, 15, 99});
      try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
        assertArrayEquals(
            new byte[] {12, 13, 14}, OverviewRangeReader.read(in.getChannel(), 3, 6, 2, 3));
        assertArrayEquals(new byte[] {15}, OverviewRangeReader.read(in.getChannel(), 3, 6, 5, 1));
      }
    } finally {
      file.delete();
    }
  }

  @Test
  public void rejectsRangesOutsideTheAsset() throws Exception {
    File file = File.createTempFile("ridgeline-asset", ".bin");
    try (RandomAccessFile in = new RandomAccessFile(file, "rw")) {
      in.setLength(20);
      assertThrows(IOException.class, () -> OverviewRangeReader.read(in.getChannel(), 3, 6, 5, 2));
      assertThrows(IOException.class, () -> OverviewRangeReader.read(in.getChannel(), 3, 6, -1, 1));
      assertThrows(
          IOException.class, () -> OverviewRangeReader.read(in.getChannel(), 3, 6, 0, 1048577));
    } finally {
      file.delete();
    }
  }

  @Test
  public void detectsTruncatedData() throws Exception {
    File file = File.createTempFile("ridgeline-asset", ".bin");
    try (RandomAccessFile in = new RandomAccessFile(file, "rw")) {
      in.setLength(2);
      assertThrows(EOFException.class, () -> OverviewRangeReader.read(in.getChannel(), 0, 6, 1, 3));
    } finally {
      file.delete();
    }
  }
}
