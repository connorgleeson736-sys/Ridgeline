package nz.ridgeline.mobile;

import static org.junit.Assert.*;

import java.util.concurrent.*;
import org.junit.Test;

public class NativeTasksTest {
  @Test
  public void statusAndPauseAreNotQueuedBehindALongExport() throws Exception {
    CountDownLatch started = new CountDownLatch(1),
        release = new CountDownLatch(1),
        control = new CountDownLatch(1),
        secondBulk = new CountDownLatch(1);
    try (NativeTasks tasks = new NativeTasks()) {
      tasks.heavy(
          () -> {
            started.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
      assertTrue(started.await(3, TimeUnit.SECONDS));
      tasks.heavy(secondBulk::countDown);
      tasks.execute(control::countDown);
      assertTrue(control.await(3, TimeUnit.SECONDS));
      assertEquals(1, secondBulk.getCount());
      release.countDown();
      assertTrue(secondBulk.await(3, TimeUnit.SECONDS));
    } finally {
      release.countDown();
    }
  }
}
