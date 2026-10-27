package nz.ridgeline.mobile;

import static org.junit.Assert.*;

import org.junit.Test;

public class NztmTest {
  @Test
  public void matchesIndependentProjControlPoints() {
    double[] cook = Nztm.forward(170.14, -43.59);
    assertEquals(1369124.0876447866, cook[0], .002);
    assertEquals(5169688.582968757, cook[1], .002);
    double[] wellington = Nztm.forward(174.7762, -41.2865);
    assertEquals(1748735.5530602792, wellington[0], .002);
    assertEquals(5427916.478887198, wellington[1], .002);
  }

  @Test
  public void roundTripsAcrossNewZealand() {
    for (double lon : new double[] {166, 170.14, 173, 174.7762, 179})
      for (double lat : new double[] {-47, -43.59, -41.2865, -34}) {
        double[] p = Nztm.forward(lon, lat), q = Nztm.inverse(p[0], p[1]);
        assertEquals(lon, q[0], 1e-8);
        assertEquals(lat, q[1], 1e-8);
      }
  }
}
