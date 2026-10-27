package nz.ridgeline.mobile;

import static org.junit.Assert.*;

import org.junit.Test;

public class TileRequestRegistryTest {
  @Test
  public void cancellationBeforeInterceptionCannotStartAStaleRequest() {
    try (var r = new TileRequestRegistry()) {
      String id = "a".repeat(32);
      r.cancel(id);
      r.begin(id, 0);
      assertTrue(r.get(id, 0).cancelled());
    }
  }

  @Test
  public void priorityUpdatesUseTheSameConsumerToken() {
    try (var r = new TileRequestRegistry()) {
      String id = "b".repeat(32);
      r.begin(id, 200);
      TileDemand d = r.get(id, 0);
      assertEquals(200, d.priority());
      r.priority(id, 0);
      assertEquals(0, d.priority());
      r.priority(id, 10000);
      assertEquals(1000, d.priority());
    }
  }

  @Test
  public void closingTheWebViewCancelsOutstandingAndLateRequests() {
    var r = new TileRequestRegistry();
    var d = r.get("c".repeat(32), 0);
    r.close();
    assertTrue(d.cancelled());
    assertTrue(r.get("d".repeat(32), 0).cancelled());
  }

  @Test
  public void malformedIdsCannotCancelUnrelatedRequests() {
    try (var r = new TileRequestRegistry()) {
      var d = r.get("e".repeat(32), 0);
      r.cancel("invalid");
      assertFalse(d.cancelled());
    }
  }
}
