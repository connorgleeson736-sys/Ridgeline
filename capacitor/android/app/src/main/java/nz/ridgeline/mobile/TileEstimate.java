package nz.ridgeline.mobile;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Stratifies by source/zoom; bounded actual-byte sampling with explicit fallback uncertainty. */
final class TileEstimate {
  interface Sizes {
    long size(TileAddress tile, boolean allowNetwork) throws Exception;
  }

  record Result(long bytes, long low, long high, int samples, int failures) {}

  private record Sample(List<TileAddress> group, double bytes, int count, int failures) {}

  static Result estimate(TilePlan plan, Sizes sizes, boolean online) {
    Map<String, List<TileAddress>> groups = new LinkedHashMap<>();
    for (TileAddress tile : plan.tiles)
      groups.computeIfAbsent(tile.kind + ":" + tile.z, k -> new ArrayList<>()).add(tile);
    List<List<TileAddress>> sorted = new ArrayList<>(groups.values());
    sorted.sort((a, b) -> Integer.compare(b.size(), a.size()));
    Map<String, double[]> means = new HashMap<>();
    Map<List<TileAddress>, Double> measured = new IdentityHashMap<>();
    int samples = 0, failures = 0;
    ExecutorService pool = Executors.newFixedThreadPool(4);
    List<Future<Sample>> jobs = new ArrayList<>();
    AtomicInteger budget = new AtomicInteger(24);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    try {
      for (List<TileAddress> group : sorted)
        jobs.add(
            pool.submit(
                () -> {
                  double sum = 0;
                  int count = 0, errors = 0;
                  for (int i = 0; i < Math.min(3, group.size()); i++) {
                    TileAddress tile =
                        group.get(
                            (int)
                                ((long) i
                                    * (group.size() - 1)
                                    / Math.max(1, Math.min(3, group.size()) - 1)));
                    try {
                      long bytes = sizes.size(tile, false);
                      if (bytes < 0 && online && budget.getAndDecrement() > 0)
                        bytes = sizes.size(tile, true);
                      if (bytes >= 0) {
                        sum += bytes;
                        count++;
                      }
                    } catch (Exception e) {
                      errors++;
                    }
                  }
                  return new Sample(group, sum, count, errors);
                }));
      for (Future<Sample> job : jobs) {
        try {
          long remaining = Math.max(1, deadline - System.nanoTime());
          Sample s = job.get(remaining, TimeUnit.NANOSECONDS);
          failures += s.failures;
          if (s.count > 0) {
            samples += s.count;
            measured.put(s.group, s.bytes / s.count);
            double[] value = means.computeIfAbsent(s.group.get(0).kind, k -> new double[2]);
            value[0] += s.bytes;
            value[1] += s.count;
          }
        } catch (Exception e) {
          failures++;
          job.cancel(true);
        }
      }
    } finally {
      pool.shutdownNow();
    }
    double total = 0, uncertainty = 0;
    for (List<TileAddress> group : sorted) {
      String kind = group.get(0).kind;
      Double direct = measured.get(group);
      double[] other = means.get(kind);
      double mean = direct != null ? direct : other != null ? other[0] / other[1] : prior(kind);
      double contribution = mean * group.size();
      total += contribution;
      uncertainty += contribution * (direct != null ? .25 : other != null ? .4 : .7);
    }
    return new Result(
        Math.round(total),
        Math.max(0, Math.round(total - uncertainty)),
        Math.round(total + uncertainty),
        samples,
        failures);
  }

  private static int prior(String kind) {
    return switch (kind) {
      case "topo-raster" -> 40000;
      case "elevation" -> 30000;
      case "vector" -> 18000;
      case "font" -> 35000;
      default -> 50000;
    };
  }
}
