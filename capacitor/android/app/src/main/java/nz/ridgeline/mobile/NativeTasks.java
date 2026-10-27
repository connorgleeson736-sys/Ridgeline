package nz.ridgeline.mobile;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Long exports/imports/estimates cannot queue ahead of Pause, settings or status. */
final class NativeTasks implements AutoCloseable {
  private final ExecutorService controls = Executors.newSingleThreadExecutor();
  private final ExecutorService bulk = Executors.newSingleThreadExecutor();

  void execute(Runnable task) {
    controls.execute(task);
  }

  void heavy(Runnable task) {
    // Keep large jobs serial to retain the existing bounded peak memory use.
    bulk.execute(task);
  }

  @Override
  public void close() {
    controls.shutdownNow();
    bulk.shutdownNow();
  }
}
