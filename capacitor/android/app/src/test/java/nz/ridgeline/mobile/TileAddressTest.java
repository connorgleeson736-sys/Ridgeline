package nz.ridgeline.mobile;

import static org.junit.Assert.*;

import org.junit.Test;

public class TileAddressTest {
  @Test
  public void tileIdentityIgnoresClientKeyAndGrading() {
    TileAddress
        a =
            TileAddress.local(
                "https://localhost/_ridge/linz/v1/tiles/aerial/WebMercatorQuad/3/7/5.webp?api=first&ridge-grade=snow-v3"),
        b =
            TileAddress.local(
                "https://localhost/_ridge/linz/v1/tiles/aerial/WebMercatorQuad/3/7/5.webp?api=second");
    assertEquals(a.id(), b.id());
    assertFalse(a.network("demo").contains("ridge-grade"));
  }

  @Test
  public void esriUsesYThenX() {
    TileAddress a =
        TileAddress.local(
            "https://localhost/_ridge/esri/ArcGIS/rest/services/World_Imagery/MapServer/tile/3/5/7");
    assertEquals(7, a.x);
    assertEquals(5, a.y);
    assertTrue(a.network("").endsWith("/3/5/7"));
  }

  @Test
  public void preservesTerrainEncodingAndEscapesKey() {
    TileAddress t = TileAddress.tile("elevation", 3, 7, 5);
    assertTrue(t.network("a&b").contains("api=a%26b"));
    assertTrue(t.network("demo").endsWith("&pipeline=terrain-rgb"));
  }

  @Test
  public void acceptsOnlyExpectedOriginsPathsAndCoordinates() {
    for (String url :
        new String[] {
          "https://evil.example/_ridge/linz/v1/tiles/aerial/WebMercatorQuad/3/7/5.webp",
          "http://localhost/_ridge/linz/v1/tiles/aerial/WebMercatorQuad/3/7/5.webp",
          "https://localhost/_ridge/linz/../../secret",
          "https://localhost/_ridge/linz/v1/tiles/aerial/WebMercatorQuad/3/8/5.webp",
          "https://localhost/_ridge/linz/v1/tiles/elevation/WebMercatorQuad/3/7/5.webp",
          "https://localhost/_ridge/mapzen/not-allowed"
        }) assertNull(url, TileAddress.local(url));
  }

  @Test
  public void vectorAndMacronGlyphResourcesAreAllowlisted() {
    assertNotNull(
        TileAddress.local(
            "https://localhost/_ridge/linz/v1/tiles/topographic-v2/3857/3/7/5.pbf?api=demo"));
    assertNotNull(
        TileAddress.local(
            "https://localhost/_ridge/linz/v1/fonts/Open%20Sans%20Regular/256-511.pbf"));
    assertNull(TileAddress.local("https://localhost/_ridge/linz/v1/fonts/Other/256-511.pbf"));
  }
}
