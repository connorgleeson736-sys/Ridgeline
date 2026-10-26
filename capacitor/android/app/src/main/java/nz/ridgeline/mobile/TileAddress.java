package nz.ridgeline.mobile;

import java.net.URI;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An allowlisted resource address, independent of API keys and UI grading. */
public final class TileAddress {
  private static final Pattern LINZ =
      Pattern.compile(
          "/v1/tiles/(aerial|elevation|topo-raster)/WebMercatorQuad/(\\d+)/(\\d+)/(\\d+)\\.(webp|png)");
  private static final Pattern VECTOR =
      Pattern.compile("/v1/tiles/topographic-v2/3857/(\\d+)/(\\d+)/(\\d+)\\.pbf");
  private static final Pattern SURVEY =
      Pattern.compile(
          "/v1/tiles/([a-z0-9][a-z0-9-]{0,100})/WebMercatorQuad/(\\d+)/(\\d+)/(\\d+)\\.png");
  private static final Pattern FONT =
      Pattern.compile("/v1/fonts/Open Sans Regular/(\\d+)-(\\d+)\\.pbf");
  private static final Pattern ESRI =
      Pattern.compile(
          "/ArcGIS/rest/services/(World_Imagery|World_Topo_Map)/MapServer/tile/(\\d+)/(\\d+)/(\\d+)");
  private static final Pattern MAPZEN = Pattern.compile("/terrarium/(\\d+)/(\\d+)/(\\d+)\\.png");
  public final String kind;
  public final int z, x, y;
  public final String path;

  private TileAddress(String kind, int z, int x, int y, String path) {
    this.kind = kind;
    this.z = z;
    this.x = x;
    this.y = y;
    this.path = path;
  }

  public static TileAddress tile(String kind, int z, int x, int y) {
    if (z < 0 || z > 22 || x < 0 || y < 0 || x >= (1 << z) || y >= (1 << z))
      throw new IllegalArgumentException("Invalid tile coordinate");
    String path;
    switch (kind) {
      case "aerial":
      case "elevation":
      case "topo-raster":
        path =
            "/v1/tiles/"
                + kind
                + "/WebMercatorQuad/"
                + z
                + "/"
                + x
                + "/"
                + y
                + (kind.equals("elevation") ? ".png" : ".webp");
        break;
      case "vector":
        path = "/v1/tiles/topographic-v2/3857/" + z + "/" + x + "/" + y + ".pbf";
        break;
      case "satellite":
      case "esri-topo":
        path =
            "/ArcGIS/rest/services/"
                + (kind.equals("satellite") ? "World_Imagery" : "World_Topo_Map")
                + "/MapServer/tile/"
                + z
                + "/"
                + y
                + "/"
                + x;
        break;
      case "mapzen":
        path = "/terrarium/" + z + "/" + x + "/" + y + ".png";
        break;
      default:
        if (kind.startsWith("survey-") && kind.substring(7).matches("[a-z0-9][a-z0-9-]{0,100}"))
          path =
              "/v1/tiles/"
                  + kind.substring(7)
                  + "/WebMercatorQuad/"
                  + z
                  + "/"
                  + x
                  + "/"
                  + y
                  + ".png";
        else throw new IllegalArgumentException("Unsupported tile source");
    }
    return new TileAddress(kind, z, x, y, path);
  }

  public static TileAddress font(int start) {
    if (start < 0 || start > 65280 || start % 256 != 0)
      throw new IllegalArgumentException("Invalid glyph range");
    return new TileAddress(
        "font",
        -1,
        start,
        0,
        "/v1/fonts/Open Sans Regular/" + start + "-" + (start + 255) + ".pbf");
  }

  public static TileAddress local(String raw) {
    try {
      URI uri = URI.create(raw);
      if (!"https".equals(uri.getScheme()) || !"localhost".equals(uri.getHost())) return null;
      String p = uri.getPath();
      Matcher m;
      if (p.startsWith("/_ridge/linz/")) {
        p = p.substring("/_ridge/linz".length());
        m = LINZ.matcher(p);
        if (m.matches()) {
          String kind = m.group(1);
          if (!m.group(5).equals(kind.equals("elevation") ? "png" : "webp")) return null;
          return tile(
              kind,
              Integer.parseInt(m.group(2)),
              Integer.parseInt(m.group(3)),
              Integer.parseInt(m.group(4)));
        }
        m = SURVEY.matcher(p);
        if (m.matches())
          return tile(
              "survey-" + m.group(1),
              Integer.parseInt(m.group(2)),
              Integer.parseInt(m.group(3)),
              Integer.parseInt(m.group(4)));
        m = VECTOR.matcher(p);
        if (m.matches())
          return tile(
              "vector",
              Integer.parseInt(m.group(1)),
              Integer.parseInt(m.group(2)),
              Integer.parseInt(m.group(3)));
        m = FONT.matcher(p);
        if (m.matches()) {
          int a = Integer.parseInt(m.group(1));
          return Integer.parseInt(m.group(2)) == a + 255 ? font(a) : null;
        }
      } else if (p.startsWith("/_ridge/esri/")) {
        m = ESRI.matcher(p.substring("/_ridge/esri".length()));
        if (m.matches())
          return tile(
              m.group(1).equals("World_Imagery") ? "satellite" : "esri-topo",
              Integer.parseInt(m.group(2)),
              Integer.parseInt(m.group(4)),
              Integer.parseInt(m.group(3)));
      } else if (p.startsWith("/_ridge/mapzen/")) {
        m = MAPZEN.matcher(p.substring("/_ridge/mapzen".length()));
        if (m.matches())
          return tile(
              "mapzen",
              Integer.parseInt(m.group(1)),
              Integer.parseInt(m.group(2)),
              Integer.parseInt(m.group(3)));
      }
    } catch (IllegalArgumentException ignored) {
    }
    return null;
  }

  public String id() {
    return kind + "_" + z + "_" + x + "_" + y;
  }

  public boolean raster() {
    return !kind.equals("vector") && !kind.equals("font");
  }

  public String mime() {
    return raster()
        ? kind.equals("elevation") || kind.equals("mapzen") ? "image/png" : "image/webp"
        : "application/x-protobuf";
  }

  public String network(String key) {
    if (kind.equals("font")) return "https://basemaps.linz.govt.nz" + path.replace(" ", "%20");
    if (kind.equals("satellite") || kind.equals("esri-topo"))
      return "https://services.arcgisonline.com" + path;
    if (kind.equals("mapzen")) return "https://s3.amazonaws.com/elevation-tiles-prod" + path;
    return "https://basemaps.linz.govt.nz"
        + path
        + "?api="
        + encode(key)
        + (kind.equals("elevation") ? "&pipeline=terrain-rgb" : "");
  }

  private static String encode(String value) {
    try {
      return URLEncoder.encode(value, "UTF-8");
    } catch (java.io.UnsupportedEncodingException e) {
      throw new AssertionError(e);
    }
  }

  private static String decode(String value) {
    try {
      return java.net.URLDecoder.decode(value, "UTF-8");
    } catch (java.io.UnsupportedEncodingException e) {
      throw new AssertionError(e);
    }
  }

  public static String queryKey(String url) {
    String q = URI.create(url).getRawQuery();
    if (q != null)
      for (String part : q.split("&"))
        if (part.startsWith("api=")) return decode(part.substring(4));
    return "";
  }
}
