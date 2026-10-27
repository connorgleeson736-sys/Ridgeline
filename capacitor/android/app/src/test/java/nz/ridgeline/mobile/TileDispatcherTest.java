package nz.ridgeline.mobile;

import static org.junit.Assert.*;

import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;

public class TileDispatcherTest {
  @Test
  public void cancellingOneSubscriberKeepsOtherConsumersTransferAlive() throws Exception {
    try (TileDispatcher<String> d = new TileDispatcher<>(2, 1)) {
      CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
      AtomicInteger stops = new AtomicInteger();
      TileDemand map = new TileDemand(0), download = new TileDemand(300);
      var a =
          d.acquire(
              "same",
              map,
              p -> {
                p.onCancel(
                    () -> {
                      stops.incrementAndGet();
                      release.countDown();
                    });
                p.publish("headers");
                started.countDown();
                release.await();
              });
      assertTrue(started.await(3, TimeUnit.SECONDS));
      var b =
          d.acquire(
              "same",
              download,
              p -> {
                throw new AssertionError("duplicate transfer");
              });
      map.cancel();
      assertThrows(InterruptedIOException.class, a::await);
      assertEquals(0, stops.get());
      assertEquals("headers", b.await());
      download.cancel();
      assertEquals(1, stops.get());
      assertEquals(1, (long) d.stats().get("started"));
    }
  }

  @Test
  public void backgroundWorkLeavesCapacityForVisibleTiles() throws Exception {
    try (TileDispatcher<String> d = new TileDispatcher<>(2, 1)) {
      CountDownLatch hold = new CountDownLatch(1), started = new CountDownLatch(1);
      TileDemand bg = new TileDemand(300), waiting = new TileDemand(200);
      var first =
          d.acquire(
              "background",
              bg,
              p -> {
                p.onCancel(hold::countDown);
                p.publish("background");
                started.countDown();
                hold.await();
              });
      assertTrue(started.await(3, TimeUnit.SECONDS));
      var queued =
          d.acquire(
              "prepare",
              waiting,
              p -> {
                p.publish("prepare");
                p.complete();
              });
      var visible =
          d.acquire(
              "visible",
              new TileDemand(0),
              p -> {
                p.publish("visible");
                p.complete();
              });
      assertEquals("visible", visible.await());
      assertEquals(1, (long) d.stats().get("queued"));
      waiting.priority(0);
      assertEquals("prepare", queued.await());
      first.close();
      queued.close();
      visible.close();
    }
  }

  @Test
  public void cancellingQueuedWorkNeverRunsIt() throws Exception {
    try (TileDispatcher<String> d = new TileDispatcher<>(1, 1)) {
      CountDownLatch hold = new CountDownLatch(1);
      var first =
          d.acquire(
              "first",
              new TileDemand(0),
              p -> {
                p.onCancel(hold::countDown);
                p.publish("first");
                hold.await();
              });
      first.await();
      TileDemand demand = new TileDemand(0);
      var queued =
          d.acquire(
              "queued",
              demand,
              p -> {
                throw new AssertionError("Obsolete request executed");
              });
      demand.cancel();
      assertThrows(InterruptedIOException.class, queued::await);
      assertEquals(0, (long) d.stats().get("queued"));
      first.close();
    }
  }

  @Test
  public void completedTransferIsNotCancelledWhenItsReaderCloses() throws Exception {
    try (TileDispatcher<String> d = new TileDispatcher<>(1, 1)) {
      CountDownLatch complete = new CountDownLatch(1);
      AtomicInteger stops = new AtomicInteger();
      var lease =
          d.acquire(
              "tile",
              new TileDemand(0),
              p -> {
                p.onCancel(stops::incrementAndGet);
                p.complete();
                p.publish("done");
                complete.countDown();
              });
      assertEquals("done", lease.await());
      assertTrue(complete.await(3, TimeUnit.SECONDS));
      lease.close();
      assertEquals(0, stops.get());
    }
  }

  @Test
  public void perConsumerCancellationWakesABlockedBodyReader() throws Exception {
    try (TileDispatcher<SharedTileBody> d = new TileDispatcher<>(1, 1)) {
      SharedTileBody body = new SharedTileBody(-1, 100);
      CountDownLatch release = new CountDownLatch(1);
      TileDemand one = new TileDemand(0), two = new TileDemand(0);
      var a =
          d.acquire(
              "tile",
              one,
              p -> {
                p.onCancel(
                    () -> {
                      body.fail(TileDispatcher.abort());
                      release.countDown();
                    });
                p.publish(body);
                release.await();
              });
      var b =
          d.acquire(
              "tile",
              two,
              p -> {
                throw new AssertionError();
              });
      a.await();
      b.await();
      a.onClose(body::wake);
      ExecutorService reader = Executors.newSingleThreadExecutor();
      try {
        Future<byte[]> wait = reader.submit(() -> body.bytes(a::closed));
        one.cancel();
        assertThrows(ExecutionException.class, () -> wait.get(1, TimeUnit.SECONDS));
        assertFalse(b.closed());
        two.cancel();
      } finally {
        reader.shutdownNow();
      }
    }
  }
}
