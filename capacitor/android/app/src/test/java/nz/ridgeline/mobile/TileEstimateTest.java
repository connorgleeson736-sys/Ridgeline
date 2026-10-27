package nz.ridgeline.mobile;

import static org.junit.Assert.*;

import org.junit.Test;

public class TileEstimateTest {
  @Test
  public void measuresSourceSpecificBytesWithoutNetwork() {
    TilePlan p = new TilePlan(new double[] {170.12, -43.61, 170.16, -43.57}, 15, "aerial", true);
    TileEstimate.Result result =
        TileEstimate.estimate(
            p,
            (t, network) -> {
              assertFalse(network);
              return t.kind.equals("topo-raster") ? 20000 : 10000;
            },
            false);
    long expected =
        p.tiles.stream().mapToLong(t -> t.kind.equals("topo-raster") ? 20000 : 10000).sum();
    assertEquals(expected, result.bytes());
    assertTrue(result.samples() > 0);
    assertTrue(result.low() < expected);
    assertTrue(result.high() > expected);
  }

  @Test
  public void unavailableSamplesRemainExplicitPlanningEstimates() {
    TilePlan p = new TilePlan(new double[] {170.12, -43.61, 170.16, -43.57}, 16, "topo", true);
    TileEstimate.Result result = TileEstimate.estimate(p, (t, network) -> -1, false);
    assertEquals(0, result.samples());
    assertEquals(p.tiles.size() * 40000L, result.bytes());
  }
}
