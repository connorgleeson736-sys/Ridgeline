package nz.ridgeline.mobile;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class TileLookupTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void unchangedHitsAndMissesScanDirectoriesOnce() throws Exception {
    File dir = temp.newFolder();
    byte[] bytes = {9, 3, 1};
    Files.write(new File(dir, "a.tile").toPath(), bytes);
    AtomicInteger scans = new AtomicInteger();
    Supplier<Iterable<File>> dirs =
        () -> {
          scans.incrementAndGet();
          return List.of(dir);
        };
    TileLookup lookup = new TileLookup(4096);
    for (int i = 0; i < 100; i++) {
      try (InputStream in = lookup.open("a.tile", dirs)) {
        assertArrayEquals(bytes, in.readAllBytes());
      }
      assertNull(lookup.open("missing.tile", dirs));
    }
    assertEquals(2, scans.get());
  }

  @Test
  public void completedWriteInvalidatesAnEarlierMiss() throws Exception {
    File dir = temp.newFolder();
    TileLookup lookup = new TileLookup(16);
    assertNull(lookup.open("a.tile", () -> List.of(dir)));
    Files.write(new File(dir, "a.tile").toPath(), new byte[] {7});
    lookup.invalidate("a.tile");
    try (InputStream in = lookup.open("a.tile", () -> List.of(dir))) {
      assertEquals(7, in.read());
    }
  }

  @Test
  public void removalFindsAnotherOverlappingRegion() throws Exception {
    File a = temp.newFolder(), b = temp.newFolder();
    Files.write(new File(a, "a.tile").toPath(), new byte[] {3});
    Files.write(new File(b, "a.tile").toPath(), new byte[] {8});
    TileLookup lookup = new TileLookup(16);
    try (InputStream in = lookup.open("a.tile", () -> List.of(a, b))) {
      assertEquals(3, in.read());
    }
    lookup.clear();
    try (InputStream in = lookup.open("a.tile", () -> List.of(b))) {
      assertEquals(8, in.read());
    }
  }

  @Test
  public void missingExternallyDeletedPathIsRescanned() throws Exception {
    File a = temp.newFolder(), b = temp.newFolder(), tile = new File(a, "a.tile");
    Files.write(tile.toPath(), new byte[] {3});
    Files.write(new File(b, "a.tile").toPath(), new byte[] {8});
    TileLookup lookup = new TileLookup(16);
    try (InputStream in = lookup.open("a.tile", () -> List.of(a, b))) {
      assertEquals(3, in.read());
    }
    Files.delete(tile.toPath());
    try (InputStream in = lookup.open("a.tile", () -> List.of(a, b))) {
      assertEquals(8, in.read());
    }
  }

  @Test
  public void leastRecentlyUsedEntriesAreBounded() throws Exception {
    TileLookup lookup = new TileLookup(2);
    AtomicInteger scans = new AtomicInteger();
    Supplier<Iterable<File>> dirs =
        () -> {
          scans.incrementAndGet();
          return List.of();
        };
    lookup.open("a", dirs);
    lookup.open("b", dirs);
    lookup.open("a", dirs);
    lookup.open("c", dirs);
    lookup.open("a", dirs);
    lookup.open("b", dirs);
    assertEquals(4, scans.get());
  }
}
