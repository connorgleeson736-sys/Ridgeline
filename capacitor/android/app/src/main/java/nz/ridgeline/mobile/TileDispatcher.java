package nz.ridgeline.mobile;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Shared transfers, dynamic background priority and subscriber-aware cancellation. */
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
  private final Set<Job> waiting = new LinkedHashSet<>();
  private int active, background;
  private volatile boolean closed;
  private long started, shared, cancelled, finished;
  private final AtomicLong sequence = new AtomicLong();

  /** Production: the renderer owns foreground concurrency; only background work queues. */
  TileDispatcher(int backgroundLimit) {
    this(Integer.MAX_VALUE, backgroundLimit);
  }

  /** Explicit total limits are retained for scheduler tests. */
  TileDispatcher(int limit, int backgroundLimit) {
    this.limit = limit;
    this.backgroundLimit = Math.min(limit, backgroundLimit);
    ThreadFactory factory =
        r -> {
          Thread t = new Thread(r, "ridge-transfer");
          t.setDaemon(true);
          return t;
        };
    workers =
        limit == Integer.MAX_VALUE
            ? Executors.newCachedThreadPool(factory)
            : Executors.newFixedThreadPool(limit, factory);
  }

  private final class Job implements Publication<T> {
    final String key;
    final long order = sequence.incrementAndGet();
    final Transfer<T> transfer;
    final CompletableFuture<T> ready = new CompletableFuture<>();
    final Set<Lease> users = new HashSet<>();
    boolean running, backgroundSlot, done, completed;
    volatile boolean stopped;
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
      boolean run;
      synchronized (TileDispatcher.this) {
        stop = action;
        run = stopped;
      }
      if (run) action.run();
    }

    @Override
    public boolean cancelled() {
      return stopped || closed;
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
        if (!released) { closers.add(action); return; }
      }
      action.run();
    }

    @Override
    public void close() {
      Runnable unsubscribe, stop = null;
      List<Runnable> release;
      boolean queued = false;
      synchronized (TileDispatcher.this) {
        if (released) return;
        released = true;
        unsubscribe = unwatch;
        release = new ArrayList<>(closers);
        closers.clear();
        job.users.remove(this);
        if (job.users.isEmpty() && !job.done && !job.completed && !job.stopped) {
          job.stopped = true;
          cancelled++;
          stop = job.stop;
          if (!job.running) {
            jobs.remove(job.key, job);
            waiting.remove(job);
            queued = true;
          }
        }
        pump();
      }
      // Never call body readers, HTTP cancellation or future continuations
      // while owning the dispatcher monitor.
      unsubscribe.run();
      ready.completeExceptionally(abort());
      for (Runnable action : release) action.run();
      if (stop != null) stop.run();
      if (queued) job.ready.completeExceptionally(abort());
    }
  }

  Lease acquire(String key, TileDemand demand, Transfer<T> transfer) throws IOException {
    Lease lease;
    synchronized (this) {
      if (closed || demand.cancelled()) throw abort();
      Job job = jobs.get(key);
      if (job == null || job.stopped || job.completed) {
        if (jobs.size() >= 512) throw new IOException("Map request queue is full.");
        job = new Job(key, transfer);
        jobs.put(key, job);
        waiting.add(job);
      } else shared++;
      lease = new Lease(job, demand);
      job.users.add(lease);
    }
    Runnable unsubscribe = demand.watch(() -> {
      if (demand.cancelled()) lease.close();
      else synchronized (TileDispatcher.this) { pump(); }
    });
    boolean released;
    synchronized (this) {
      lease.unwatch = unsubscribe;
      released = lease.released;
      if (!released) pump();
    }
    if (released) unsubscribe.run();
    return lease;
  }

  private void pump() {
    if (closed) return;
    while (active < limit) {
      Job best = null;
      int bestPriority = Integer.MAX_VALUE;
      for (Job j : waiting) {
        if (j.stopped || j.users.isEmpty()) continue;
        int p = j.priority();
        if (p >= 100 && background >= backgroundLimit) continue;
        if (p < bestPriority || p == bestPriority && j.order < best.order) {
          best = j;
          bestPriority = p;
        }
      }
      if (best == null) return;
      Job next = best;
      waiting.remove(next);
      next.running = true;
      next.backgroundSlot = bestPriority >= 100;
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
        (long) waiting.size(),
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
  public void close() {
    List<Job> stopped;
    synchronized (this) {
      if (closed) return;
      closed = true;
      stopped = new ArrayList<>(jobs.values());
      for (Job j : stopped) j.stopped = true;
      jobs.clear();
      waiting.clear();
    }
    for (Job j : stopped) {
      j.stop.run();
      j.ready.completeExceptionally(abort());
    }
    workers.shutdownNow();
  }
}
