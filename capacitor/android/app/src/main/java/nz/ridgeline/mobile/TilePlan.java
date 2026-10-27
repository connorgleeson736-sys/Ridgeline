package nz.ridgeline.mobile;

import java.util.ArrayList;
import java.util.List;

/** Bounded, deterministic plan including ancestor zooms and DEM seam neighbours. */
public final class TilePlan {
  public static final int LIMIT = 12000;
  public static final int TOPO_ZOOM = 18;
  public final List<TileAddress> tiles = new ArrayList<>();

  public TilePlan(double[] b, int zoom, String imagery, boolean topo) {
    this(b, zoom, imagery, topo, TOPO_ZOOM);
  }

  public TilePlan(double[] b, int zoom, String imagery, boolean topo, int topoZoom) {
    if (topoZoom < 16 || topoZoom > 18) throw new IllegalArgumentException("Invalid Topo detail");
    if (b == null || b.length != 4)
      throw new IllegalArgumentException("Specify west, south, east and north");
    for (double v : b)
      if (!Double.isFinite(v)) throw new IllegalArgumentException("Coordinates must be finite");
    if (b[0] < 165 || b[2] > 180 || b[1] < -49 || b[3] > -33 || b[0] >= b[2] || b[1] >= b[3])
      throw new IllegalArgumentException(
          "Choose a rectangle within New Zealand (165–180° E, 49–33° S)");
    if (zoom < 13 || zoom > 17)
      throw new IllegalArgumentException("Download detail must be between zoom 13 and 17");
    if (imagery.equals("topo")) {
      add(b, "topo-raster", topoZoom, 0);
      return;
    }
    if (!imagery.equals("aerial") && !imagery.equals("satellite"))
      throw new IllegalArgumentException("Choose LINZ aerial or Satellite");
    add(b, imagery, zoom, 0);
    if (imagery.equals("aerial")) add(b, "satellite", Math.min(zoom, 17), 0);
    add(b, "elevation", Math.min(zoom, 16), 1);
    if (topo) {
      add(b, "topo-raster", topoZoom, 0);
      add(b, "vector", 14, 0);
      for (int start = 0; start < 768; start += 256) tiles.add(TileAddress.font(start));
      tiles.add(TileAddress.font(8192));
    }
    if (tiles.size() > LIMIT) throw tooLarge();
  }

  private static IllegalArgumentException tooLarge() {
    return new IllegalArgumentException(
        "This region exceeds 12,000 files. Choose a smaller area or less detail.");
  }

  private void add(double[] b, String kind, int max, int halo) {
    for (int z = 0; z <= max; z++) {
      int n = 1 << z,
          x0 = Math.max(0, x(b[0], z) - halo),
          x1 = Math.min(n - 1, x(Math.nextDown(b[2]), z) + halo),
          y0 = Math.max(0, y(b[3], z) - halo),
          y1 = Math.min(n - 1, y(Math.nextUp(b[1]), z) + halo);
      long count = (long) (x1 - x0 + 1) * (y1 - y0 + 1);
      if (count + tiles.size() > LIMIT) throw tooLarge();
      for (int x = x0; x <= x1; x++)
        for (int y = y0; y <= y1; y++) tiles.add(TileAddress.tile(kind, z, x, y));
    }
  }

  static int x(double lon, int z) {
    return Math.min((1 << z) - 1, Math.max(0, (int) Math.floor((lon + 180) / 360 * (1 << z))));
  }

  static int y(double lat, int z) {
    double a = Math.toRadians(lat);
    return Math.min(
        (1 << z) - 1,
        Math.max(
            0,
            (int)
                Math.floor(
                    (1 - Math.log(Math.tan(a) + 1 / Math.cos(a)) / Math.PI) / 2 * (1 << z))));
  }
}
