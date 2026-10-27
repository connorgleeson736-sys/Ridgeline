package nz.ridgeline.mobile;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class SingleFlightTest {
  @Test
  public void concurrentRequestsShareTheSameBytes() throws Exception {
    SingleFlight<byte[]> jobs = new SingleFlight<>();
    ExecutorService pool = Executors.newFixedThreadPool(12);
    CountDownLatch ready = new CountDownLatch(12),
        start = new CountDownLatch(1),
        release = new CountDownLatch(1);
    AtomicInteger loads = new AtomicInteger();
    byte[] bytes = {1, 7, 9};
    List<Future<byte[]>> results = new ArrayList<>();
    try {
      for (int i = 0; i < 12; i++)
        results.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return jobs.run(
                      "tile",
                      () -> {
                        loads.incrementAndGet();
                        try {
                          if (!release.await(3, TimeUnit.SECONDS))
                            throw new IOException("Timed out");
                        } catch (InterruptedException e) {
                          throw new InterruptedIOException();
                        }
                        return bytes;
                      });
                }));
      assertTrue(ready.await(3, TimeUnit.SECONDS));
      start.countDown();
      // None can finish until the single producer is released.
      for (Future<byte[]> f : results)
        assertThrows(TimeoutException.class, () -> f.get(20, TimeUnit.MILLISECONDS));
      assertEquals(1, loads.get());
      release.countDown();
      for (Future<byte[]> f : results) assertSame(bytes, f.get(3, TimeUnit.SECONDS));
      assertEquals(1, loads.get());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  public void completedAndFailedWorkNeverBecomeAnUnboundedMemoryCache() throws Exception {
    SingleFlight<Integer> jobs = new SingleFlight<>();
    AtomicInteger calls = new AtomicInteger();
    assertEquals(1, (int) jobs.run("tile", calls::incrementAndGet));
    assertEquals(2, (int) jobs.run("tile", calls::incrementAndGet));
    assertThrows(
        IOException.class,
        () ->
            jobs.run(
                "tile",
                () -> {
                  throw new IOException("offline");
                }));
    assertEquals(3, (int) jobs.run("tile", calls::incrementAndGet));
    assertEquals(4, (int) jobs.run("another-key", calls::incrementAndGet));
  }

  @Test
  public void interruptingAWaiterDoesNotCancelAnotherConsumersRequest() throws Exception {
    SingleFlight<Integer> jobs = new SingleFlight<>();
    ExecutorService pool = Executors.newSingleThreadExecutor();
    CountDownLatch started = new CountDownLatch(1),
        release = new CountDownLatch(1),
        waiting = new CountDownLatch(1);
    try {
      Future<Integer> leader =
          pool.submit(
              () ->
                  jobs.run(
                      "tile",
                      () -> {
                        started.countDown();
                        try {
                          release.await();
                        } catch (InterruptedException e) {
                          throw new InterruptedIOException();
                        }
                        return 9;
                      }));
      assertTrue(started.await(3, TimeUnit.SECONDS));
      Thread follower =
          new Thread(
              () -> {
                Thread.currentThread().interrupt();
                try {
                  jobs.run(
                      "tile",
                      () -> {
                        throw new AssertionError("duplicate producer");
                      });
                  throw new AssertionError("Expected interruption");
                } catch (InterruptedIOException expected) {
                  if (Thread.currentThread().isInterrupted()) waiting.countDown();
                } catch (IOException e) {
                  throw new AssertionError(e);
                }
              });
      follower.start();
      assertTrue(waiting.await(3, TimeUnit.SECONDS));
      follower.join(1000);
      release.countDown();
      assertEquals(9, (int) leader.get(3, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }
}
