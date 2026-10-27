package nz.ridgeline.mobile;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/** Share only outstanding work. Completed values remain subject to the HTTP cache's expiry. */
final class SingleFlight<T> {
  interface Loader<T> {
    T load() throws IOException;
  }

  private final ConcurrentHashMap<String, FutureTask<T>> pending = new ConcurrentHashMap<>();

  T run(String key, Loader<T> loader) throws IOException {
    FutureTask<T> own = new FutureTask<>(loader::load);
    FutureTask<T> job = pending.putIfAbsent(key, own);
    boolean leader = job == null;
    if (leader) job = own;
    try {
      if (leader) job.run();
      return job.get();
    } catch (InterruptedException e) {
      // One cancelled waiter must not cancel the map/download's shared request.
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("Map tile request interrupted.");
    } catch (ExecutionException e) {
      if (e.getCause() instanceof IOException) throw (IOException) e.getCause();
      if (e.getCause() instanceof RuntimeException) throw (RuntimeException) e.getCause();
      if (e.getCause() instanceof Error) throw (Error) e.getCause();
      throw new IOException("Map tile request failed.", e.getCause());
    } finally {
      if (leader) pending.remove(key, own);
    }
  }
}
