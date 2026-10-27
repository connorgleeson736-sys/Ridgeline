package nz.ridgeline.mobile;

import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Three bounded producers; the shared transport reserves foreground capacity. */
final class RegionTransfer {
  interface Fetch {
    byte[] read(TileAddress tile) throws IOException;
  }

  interface Save {
    boolean write(Map<TileAddress, byte[]> batch) throws Exception;
  }

  interface Progress {
    void update(int done, long bytes) throws Exception;
  }

  record Result(int done, long bytes, int failures, String error) {}

  private record Tile(TileAddress address, byte[] bytes, String error) {}

  static long retryDelay(IOException error) {
    if (error instanceof TileNetwork.HttpFailure f) {
      if (f.status != 429 && f.status < 500) return -1;
      if (f.retryAfter != null)
        try {
          long ms;
          try {
            ms = Math.multiplyExact(Long.parseLong(f.retryAfter.trim()), 1000);
          } catch (NumberFormatException e) {
            ms =
                ZonedDateTime.parse(f.retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant()
                        .toEpochMilli()
                    - System.currentTimeMillis();
          }
          return ms > 30000 ? -1 : Math.max(500, ms);
        } catch (RuntimeException ignored) {
        }
    }
    return 500;
  }

  static Result run(
      List<TileAddress> plan,
      Map<String, Long> saved,
      Fetch fetch,
      Save save,
      Progress progress,
      BooleanSupplier active,
      TileDemand demand)
      throws Exception {
    int done = 0, failures = 0;
    long bytes = 0;
    List<TileAddress> missing = new ArrayList<>();
    for (TileAddress t : plan) {
      Long n = saved.get(t.id());
      if (n != null) {
        done++;
        bytes += n;
      } else missing.add(t);
    }
    progress.update(done, bytes);
    ExecutorService producers =
        Executors.newFixedThreadPool(
            3,
            r -> {
              Thread t = new Thread(r, "ridge-region-tile");
              t.setDaemon(true);
              return t;
            });
    CompletionService<Tile> results = new ExecutorCompletionService<>(producers);
    Set<Future<Tile>> futures = new HashSet<>();
    Iterator<TileAddress> remaining = missing.iterator();
    int outstanding = 0;
    String error = "";
    Map<TileAddress, byte[]> batch = new LinkedHashMap<>();
    long batchBytes = 0;
    try {
      while (active.getAsBoolean() && (remaining.hasNext() || outstanding > 0)) {
        while (outstanding < 3 && remaining.hasNext()) {
          TileAddress t = remaining.next();
          outstanding++;
          futures.add(
              results.submit(
                  () -> {
                    for (int attempt = 0;
                        attempt < 2 && active.getAsBoolean() && !demand.cancelled();
                        attempt++)
                      try {
                        return new Tile(t, fetch.read(t), "");
                      } catch (IOException e) {
                        long delay = retryDelay(e);
                        if (attempt == 1 || delay < 0) return new Tile(t, null, e.getMessage());
                        long until = System.nanoTime() + delay * 1000000;
                        while (System.nanoTime() < until
                            && active.getAsBoolean()
                            && !demand.cancelled())
                          TimeUnit.MILLISECONDS.sleep(
                              Math.min(100, Math.max(1, (until - System.nanoTime()) / 1000000)));
                      }
                    return new Tile(t, null, "Download paused");
                  }));
        }
        Future<Tile> next = results.poll(200, TimeUnit.MILLISECONDS);
        if (next == null) continue;
        Tile tile = next.get();
        futures.remove(next);
        outstanding--;
        if (tile.bytes == null) {
          failures++;
          error = tile.error == null ? "Map tile unavailable" : tile.error;
        } else {
          batch.put(tile.address, tile.bytes);
          batchBytes += tile.bytes.length;
        }
        if (batch.size() >= 16
            || batchBytes >= 4 * 1024 * 1024
            || outstanding == 0 && !remaining.hasNext()
            || failures >= 6) {
          if (!batch.isEmpty()) {
            if (!save.write(batch)) break;
            done += batch.size();
            bytes += batchBytes;
            batch.clear();
            batchBytes = 0;
            progress.update(done, bytes);
          }
        }
        if (failures >= 6) break;
      }
      return new Result(done, bytes, failures, error);
    } finally {
      demand.cancel();
      for (Future<Tile> f : futures) f.cancel(true);
      producers.shutdownNow();
    }
  }
}
