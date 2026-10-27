package nz.ridgeline.mobile;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded shared transfers, dynamic demand priority and subscriber-aware cancellation. */
final class TileDispatcher<T> implements AutoCloseable {
  interface Transfer<T> {
    void run(Publication<T> output) throws Exception;
  }

  interface Publication<T> {
    void publish(T value);

    void onCancel(Runnable cancel);

    boolean cancelled();

    void complete();
  }

  private final int limit, backgroundLimit;
  private final ExecutorService workers;
  private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();
  private int active, background;
  private boolean closed;
  private long started, shared, cancelled, finished;
  private final AtomicLong sequence = new AtomicLong();

  TileDispatcher(int limit, int backgroundLimit) {
    this.limit = limit;
    this.backgroundLimit = Math.min(limit, backgroundLimit);
    workers =
        Executors.newFixedThreadPool(
            limit,
            r -> {
              Thread t = new Thread(r, "ridge-transfer");
              t.setDaemon(true);
              return t;
            });
  }

  private final class Job implements Publication<T> {
    final String key;
    final long order = sequence.incrementAndGet();
    final Transfer<T> transfer;
    final CompletableFuture<T> ready = new CompletableFuture<>();
    final Set<Lease> users = new HashSet<>();
    boolean running, backgroundSlot, stopped, done, completed;
    Runnable stop = () -> {};

    Job(String key, Transfer<T> transfer) {
      this.key = key;
      this.transfer = transfer;
    }

    int priority() {
      int p = 1001;
      for (Lease l : users) if (!l.demand.cancelled()) p = Math.min(p, l.demand.priority());
      return p;
    }

    @Override
    public void publish(T value) {
      ready.complete(value);
    }

    @Override
    public void onCancel(Runnable action) {
      synchronized (TileDispatcher.this) {
        stop = action;
        if (stopped) action.run();
      }
    }

    @Override
    public boolean cancelled() {
      synchronized (TileDispatcher.this) {
        return stopped || closed;
      }
    }

    @Override
    public void complete() {
      synchronized (TileDispatcher.this) {
        completed = true;
      }
    }
  }

  final class Lease implements AutoCloseable {
    private final Job job;
    private final TileDemand demand;
    private final CompletableFuture<T> ready = new CompletableFuture<>();
    private final List<Runnable> closers = new ArrayList<>();
    private Runnable unwatch = () -> {};
    private volatile boolean released;

    Lease(Job job, TileDemand demand) {
      this.job = job;
      this.demand = demand;
      job.ready.whenComplete(
          (v, e) -> {
            if (e != null) ready.completeExceptionally(e);
            else ready.complete(v);
          });
    }

    boolean closed() {
      return released || demand.cancelled();
    }

    T await() throws IOException {
      if (closed()) throw abort();
      try {
        T v = ready.get();
        if (closed()) throw abort();
        return v;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        close();
        throw abort();
      } catch (ExecutionException e) {
        close();
        Throwable cause = e.getCause();
        if (cause instanceof IOException) throw (IOException) cause;
        throw new IOException("Map transfer failed", cause);
      }
    }

    void onClose(Runnable action) {
      synchronized (TileDispatcher.this) {
        if (released) action.run();
        else closers.add(action);
      }
    }

    @Override
    public void close() {
      synchronized (TileDispatcher.this) {
        if (released) return;
        released = true;
        unwatch.run();
        job.users.remove(this);
        ready.completeExceptionally(abort());
        for (Runnable r : closers) r.run();
        closers.clear();
        if (job.users.isEmpty() && !job.done && !job.completed) {
          job.stopped = true;
          cancelled++;
          job.stop.run();
          if (!job.running) {
            jobs.remove(job.key, job);
            job.ready.completeExceptionally(abort());
          }
        }
        pump();
      }
    }
  }

  synchronized Lease acquire(String key, TileDemand demand, Transfer<T> transfer)
      throws IOException {
    if (closed || demand.cancelled()) throw abort();
    Job job = jobs.get(key);
    if (job == null || job.stopped) {
      if (jobs.size() >= 512) throw new IOException("Map request queue is full.");
      job = new Job(key, transfer);
      jobs.put(key, job);
    } else shared++;
    Lease lease = new Lease(job, demand);
    job.users.add(lease);
    lease.unwatch =
        demand.watch(
            () -> {
              synchronized (TileDispatcher.this) {
                if (demand.cancelled()) lease.close();
                else pump();
              }
            });
    if (lease.released) lease.unwatch.run();
    if (!lease.released) pump();
    return lease;
  }

  private void pump() {
    if (closed) return;
    while (active < limit) {
      Job best = null;
      boolean foregroundWaiting =
          jobs.values().stream().anyMatch(j -> !j.running && !j.stopped && j.priority() < 100);
      for (Job j : jobs.values()) {
        if (j.running || j.stopped || j.users.isEmpty()) continue;
        int p = j.priority();
        if (p >= 100 && (foregroundWaiting || background >= backgroundLimit)) continue;
        if (best == null || p < best.priority() || p == best.priority() && j.order < best.order)
          best = j;
      }
      if (best == null) return;
      Job next = best;
      next.running = true;
      next.backgroundSlot = next.priority() >= 100;
      active++;
      if (next.backgroundSlot) background++;
      started++;
      workers.execute(
          () -> {
            try {
              if (next.cancelled()) throw abort();
              next.transfer.run(next);
              if (!next.ready.isDone())
                next.ready.completeExceptionally(new IOException("Empty map transfer"));
            } catch (Throwable error) {
              next.ready.completeExceptionally(error);
            } finally {
              synchronized (TileDispatcher.this) {
                next.done = true;
                active--;
                if (next.backgroundSlot) background--;
                finished++;
                jobs.remove(next.key, next);
                pump();
              }
            }
          });
    }
  }

  synchronized Map<String, Long> stats() {
    return Map.of(
        "active",
        (long) active,
        "background",
        (long) background,
        "queued",
        jobs.values().stream().filter(j -> !j.running).count(),
        "started",
        started,
        "shared",
        shared,
        "cancelled",
        cancelled,
        "finished",
        finished);
  }

  static InterruptedIOException abort() {
    return new InterruptedIOException("Map request cancelled");
  }

  @Override
  public synchronized void close() {
    closed = true;
    for (Job j : jobs.values()) {
      j.stopped = true;
      j.stop.run();
      j.ready.completeExceptionally(abort());
    }
    jobs.clear();
    workers.shutdownNow();
  }
}
