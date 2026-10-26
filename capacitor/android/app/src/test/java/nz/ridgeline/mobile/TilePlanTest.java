package nz.ridgeline.mobile;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class TilePlanTest {
  final double[] cook = {170.12, -43.61, 170.16, -43.57};

  @Test
  public void includesTerrainNeighboursAndEveryAncestor() {
    TilePlan p = new TilePlan(cook, 15, "aerial", true);
    Set<String> ids = new HashSet<>();
    for (TileAddress t : p.tiles) assertTrue("No duplicate resource", ids.add(t.id()));
    int x = TilePlan.x(170.14, 15), y = TilePlan.y(-43.59, 15);
    assertTrue(ids.contains("aerial_15_" + x + "_" + y));
    assertTrue(ids.contains("elevation_15_" + (x - 1) + "_" + y));
    assertTrue(ids.contains("satellite_15_" + x + "_" + y));
    assertTrue(ids.contains("aerial_0_0_0"));
    assertTrue(ids.contains("font_-1_256_0"));
    assertTrue(ids.contains("vector_14_" + (x / 2) + "_" + (y / 2)));
  }

  @Test
  public void maximumDetailDoesNotOverpromiseDemResolution() {
    TilePlan p = new TilePlan(cook, 17, "aerial", false);
    assertTrue(p.tiles.stream().anyMatch(t -> t.kind.equals("aerial") && t.z == 17));
    assertFalse(p.tiles.stream().anyMatch(t -> t.kind.equals("elevation") && t.z > 16));
    assertFalse(p.tiles.stream().anyMatch(t -> t.kind.equals("vector")));
  }

  @Test
  public void satellitePlanDoesNotDownloadLinzAerial() {
    TilePlan p = new TilePlan(cook, 13, "satellite", false);
    assertTrue(p.tiles.stream().anyMatch(t -> t.kind.equals("elevation")));
    assertFalse(p.tiles.stream().anyMatch(t -> t.kind.equals("aerial")));
  }

  @Test
  public void rejectsUnboundedOrInvalidRegions() {
    for (double[] b :
        new double[][] {
          {165, -49, 180, -33},
          {170, -43, 169, -42},
          {Double.NaN, -44, 171, -43},
          {164, -44, 171, -43},
          {170, -44, 181, -43},
          {170, -44, 171, 0}
        }) assertThrows(IllegalArgumentException.class, () -> new TilePlan(b, 17, "aerial", true));
  }

  @Test
  public void rejectsInvalidSettings() {
    assertThrows(IllegalArgumentException.class, () -> new TilePlan(cook, 20, "aerial", true));
    assertThrows(IllegalArgumentException.class, () -> new TilePlan(cook, 15, "other", true));
  }
}
