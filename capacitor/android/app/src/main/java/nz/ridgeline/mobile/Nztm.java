package nz.ridgeline.mobile;

/** EPSG:2193, NZGD2000 / New Zealand Transverse Mercator (GRS80). */
public final class Nztm {
  private static final double A = 6378137,
      F = 1 / 298.257222101,
      E2 = F * (2 - F),
      EP = E2 / (1 - E2),
      K = .9996;

  private static double meridian(double p) {
    return A
        * ((1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2 * E2 * E2 / 256) * p
            - (3 * E2 / 8 + 3 * E2 * E2 / 32 + 45 * E2 * E2 * E2 / 1024) * Math.sin(2 * p)
            + (15 * E2 * E2 / 256 + 45 * E2 * E2 * E2 / 1024) * Math.sin(4 * p)
            - 35 * E2 * E2 * E2 / 3072 * Math.sin(6 * p));
  }

  public static double[] forward(double lon, double lat) {
    double p = Math.toRadians(lat),
        s = Math.sin(p),
        c = Math.cos(p),
        t = Math.tan(p),
        v = A / Math.sqrt(1 - E2 * s * s),
        T = t * t,
        C = EP * c * c,
        d = Math.toRadians(lon - 173) * c;
    double east =
        1600000
            + K
                * v
                * (d
                    + (1 - T + C) * Math.pow(d, 3) / 6
                    + (5 - 18 * T + T * T + 72 * C - 58 * EP) * Math.pow(d, 5) / 120);
    double north =
        10000000
            + K
                * (meridian(p)
                    + v
                        * t
                        * (d * d / 2
                            + (5 - T + 9 * C + 4 * C * C) * Math.pow(d, 4) / 24
                            + (61 - 58 * T + T * T + 600 * C - 330 * EP) * Math.pow(d, 6) / 720));
    return new double[] {east, north};
  }

  public static double[] inverse(double east, double north) {
    double m = (north - 10000000) / K,
        mu = m / (A * (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2 * E2 * E2 / 256)),
        e1 = (1 - Math.sqrt(1 - E2)) / (1 + Math.sqrt(1 - E2));
    double p =
        mu
            + (3 * e1 / 2 - 27 * Math.pow(e1, 3) / 32) * Math.sin(2 * mu)
            + (21 * e1 * e1 / 16 - 55 * Math.pow(e1, 4) / 32) * Math.sin(4 * mu)
            + 151 * Math.pow(e1, 3) / 96 * Math.sin(6 * mu)
            + 1097 * Math.pow(e1, 4) / 512 * Math.sin(8 * mu);
    double s = Math.sin(p),
        c = Math.cos(p),
        t = Math.tan(p),
        C = EP * c * c,
        T = t * t,
        N = A / Math.sqrt(1 - E2 * s * s),
        R = A * (1 - E2) / Math.pow(1 - E2 * s * s, 1.5),
        d = (east - 1600000) / (N * K);
    double lat =
        p
            - N
                * t
                / R
                * (d * d / 2
                    - (5 + 3 * T + 10 * C - 4 * C * C - 9 * EP) * Math.pow(d, 4) / 24
                    + (61 + 90 * T + 298 * C + 45 * T * T - 252 * EP - 3 * C * C)
                        * Math.pow(d, 6)
                        / 720);
    double lon =
        Math.toRadians(173)
            + (d
                    - (1 + 2 * T + C) * Math.pow(d, 3) / 6
                    + (5 - 2 * C + 28 * T - 3 * C * C + 8 * EP + 24 * T * T) * Math.pow(d, 5) / 120)
                / c;
    double x = Math.toDegrees(lon), y = Math.toDegrees(lat), step = 1e-6;
    // Refine the inverse against the forward series near the NZTM zone edges.
    for (int i = 0; i < 2; i++) {
      double[] p0 = forward(x, y), px = forward(x + step, y), py = forward(x, y + step);
      double ax = (px[0] - p0[0]) / step,
          ay = (px[1] - p0[1]) / step,
          bx = (py[0] - p0[0]) / step,
          by = (py[1] - p0[1]) / step,
          det = ax * by - ay * bx,
          de = east - p0[0],
          dn = north - p0[1];
      x += (de * by - dn * bx) / det;
      y += (ax * dn - ay * de) / det;
    }
    return new double[] {x, y};
  }
}
