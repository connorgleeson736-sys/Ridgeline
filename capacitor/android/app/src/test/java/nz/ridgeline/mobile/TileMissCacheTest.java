package nz.ridgeline.mobile;

import static org.junit.Assert.*;

import org.junit.Test;

public class TileMissCacheTest {
  @Test
  public void repeatedMissesAreMemoisedWithinBudget() {
    var c = new TileMissCache(2);
    c.remember("a", c.epoch());
    c.remember("b", c.epoch());
    assertTrue(c.contains("a"));
    c.remember("c", c.epoch());
    assertFalse(c.contains("b"));
    assertTrue(c.contains("a"));
    assertTrue(c.contains("c"));
  }

  @Test
  public void downloadCommitInvalidatesMissAndLateReaderCannotReinsertIt() {
    var c = new TileMissCache(2);
    long old = c.epoch();
    c.remember("a", old);
    c.invalidate();
    c.remember("a", old);
    assertFalse(c.contains("a"));
    c.remember("b", c.epoch());
    assertTrue(c.contains("b"));
  }
}
