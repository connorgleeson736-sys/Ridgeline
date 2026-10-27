package nz.ridgeline.mobile;

import android.webkit.JavascriptInterface;
import java.util.*;

/** WebView-scoped control only: no URLs, filesystem access or arbitrary native operations. */
public final class TileRequestRegistry implements AutoCloseable {
  private record Entry(TileDemand demand, long at) {}

  private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
  private boolean closed;
  private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[a-f0-9]{32}");

  private static boolean valid(String id) {
    return id != null && ID.matcher(id).matches();
  }

  synchronized TileDemand get(String id, int priority) {
    prune();
    if (!valid(id)) return new TileDemand(priority);
    Entry e = entries.get(id);
    if (e == null) {
      e = new Entry(new TileDemand(priority), System.nanoTime());
      entries.put(id, e);
    }
    if (closed) e.demand.cancel();
    return e.demand;
  }

  private void prune() {
    long before = System.nanoTime() - 120_000_000_000L;
    for (var it = entries.entrySet().iterator(); it.hasNext(); ) {
      var e = it.next().getValue();
      if (e.at >= before && entries.size() <= 4096) break;
      if (e.at < before || entries.size() > 4096) {
        e.demand.cancel();
        it.remove();
      }
    }
  }

  @JavascriptInterface
  public void begin(String id, int priority) {
    if (valid(id)) get(id, priority).priority(priority);
  }

  @JavascriptInterface
  public void priority(String id, int priority) {
    if (valid(id)) get(id, priority).priority(priority);
  }

  @JavascriptInterface
  public void cancel(String id) {
    if (valid(id)) get(id, 0).cancel();
  }

  @Override
  public synchronized void close() {
    closed = true;
    for (Entry e : entries.values()) e.demand.cancel();
    entries.clear();
  }
}
