package nz.ridgeline.mobile;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;

public class RegionTransferTest {
  List<TileAddress> plan(int count) {
    List<TileAddress> tiles = new ArrayList<>();
    for (int x = 0; x < count; x++) tiles.add(TileAddress.tile("aerial", 12, x, 0));
    return tiles;
  }

  @Test
  public void boundedParallelTransfersCommitInBatchesAndSkipSavedTiles() throws Exception {
    var tiles = plan(40);
    AtomicInteger live = new AtomicInteger(),
        peak = new AtomicInteger(),
        calls = new AtomicInteger(),
        commits = new AtomicInteger();
    Set<String> saved = new HashSet<>();
    var result =
        RegionTransfer.run(
            tiles,
            Map.of(tiles.get(0).id(), 3L),
            t -> {
              int n = live.incrementAndGet();
              peak.accumulateAndGet(n, Math::max);
              calls.incrementAndGet();
              try {
                return new byte[] {1, 2, 3};
              } finally {
                live.decrementAndGet();
              }
            },
            batch -> {
              assertTrue(batch.size() <= 16);
              commits.incrementAndGet();
              batch.keySet().forEach(t -> saved.add(t.id()));
              return true;
            },
            (n, b) -> assertEquals(n * 3L, b),
            () -> true,
            new TileDemand(300));
    assertEquals(40, result.done());
    assertEquals(120, result.bytes());
    assertEquals(39, calls.get());
    assertEquals(39, saved.size());
    assertTrue(peak.get() <= 3);
    assertTrue(commits.get() < 10);
  }

  @Test
  public void pausedGenerationCannotCommitFinishedTiles() throws Exception {
    AtomicBoolean active = new AtomicBoolean(true);
    AtomicInteger writes = new AtomicInteger();
    var result =
        RegionTransfer.run(
            plan(30),
            Map.of(),
            t -> {
              active.set(false);
              return new byte[] {1};
            },
            b -> {
              writes.incrementAndGet();
              return false;
            },
            (n, b) -> {},
            active::get,
            new TileDemand(300));
    assertEquals(0, result.done());
    assertEquals(0, writes.get());
  }

  @Test
  public void permanentProviderFailuresDoNotRetryAndStopTheRegion() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    var result =
        RegionTransfer.run(
            plan(20),
            Map.of(),
            t -> {
              calls.incrementAndGet();
              throw new TileNetwork.HttpFailure(403, null);
            },
            b -> true,
            (n, b) -> {},
            () -> true,
            new TileDemand(300));
    assertEquals(6, result.failures());
    assertTrue(calls.get() <= 8);
    assertEquals(0, result.done());
  }

  @Test
  public void retryAfterIsHonouredWithoutAnUnboundedWorkerWait() {
    assertEquals(2000, RegionTransfer.retryDelay(new TileNetwork.HttpFailure(429, "2")));
    assertEquals(-1, RegionTransfer.retryDelay(new TileNetwork.HttpFailure(503, "600")));
    assertEquals(-1, RegionTransfer.retryDelay(new TileNetwork.HttpFailure(404, null)));
    assertEquals(500, RegionTransfer.retryDelay(new IOException()));
  }
}
