package nz.ridgeline.mobile;

import java.util.concurrent.CopyOnWriteArrayList;

/** One consumer's demand. Cancelling a consumer never cancels another consumer. */
final class TileDemand {
  static final int VISIBLE = 0, PREPARE = 200, DOWNLOAD = 150, ESTIMATE = 100;
  private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
  private volatile boolean cancelled;
  private volatile int priority;

  TileDemand(int priority) {
    this.priority = clamp(priority);
  }

  boolean cancelled() {
    return cancelled;
  }

  int priority() {
    return priority;
  }

  private static int clamp(int p) {
    return Math.max(0, Math.min(1000, p));
  }

  void priority(int p) {
    p = clamp(p);
    if (priority != p) {
      priority = p;
      changed();
    }
  }

  void cancel() {
    if (!cancelled) {
      cancelled = true;
      changed();
    }
  }

  private void changed() {
    for (Runnable r : listeners) r.run();
  }

  Runnable watch(Runnable listener) {
    listeners.add(listener);
    if (cancelled) listener.run();
    return () -> listeners.remove(listener);
  }
}
