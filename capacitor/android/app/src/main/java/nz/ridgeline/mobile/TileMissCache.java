package nz.ridgeline.mobile;

import java.util.LinkedHashMap;

/** Bounded negative lookup memo; an epoch prevents stale reads racing a download commit. */
final class TileMissCache {
  private final int limit;
  private long epoch;
  private final LinkedHashMap<String, Boolean> misses = new LinkedHashMap<>();

  TileMissCache(int limit) {
    this.limit = limit;
  }

  synchronized long epoch() {
    return epoch;
  }

  synchronized boolean contains(String key) {
    if (misses.remove(key) == null) return false;
    misses.put(key, true);
    return true;
  }

  synchronized void remember(String key, long observed) {
    if (observed != epoch) return;
    misses.put(key, true);
    while (misses.size() > limit) misses.remove(misses.keySet().iterator().next());
  }

  synchronized void invalidate() {
    epoch++;
    misses.clear();
  }
}
