package nz.ridgeline.mobile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.DeflaterOutputStream;

/** Streaming, single-page lossless raster map. Keeps one decoded 256px tile in memory. */
public final class MapPdf {
  public interface Tiles {
    byte[] rgb(int z, int x, int y) throws Exception;
  }

  public static final int ZOOM = 16;

  public static final class Options {
    public final String pageSize, orientation, magneticNote;
    public final int zoom, grid;
    public final boolean north, magnetic, scaleBar;
    public final double declination;

    public Options(
        String pageSize,
        String orientation,
        int zoom,
        int grid,
        boolean north,
        boolean magnetic,
        boolean scaleBar,
        double declination,
        String magneticNote) {
      paper(pageSize);
      if (!orientation.equals("portrait") && !orientation.equals("landscape"))
        throw new IllegalArgumentException("Invalid page orientation");
      if (zoom < 16 || zoom > 18) throw new IllegalArgumentException("Unsupported Topo detail");
      if (grid != 0 && grid != 100 && grid != 500 && grid != 1000 && grid != 5000)
        throw new IllegalArgumentException("Invalid grid spacing");
      if (!Double.isFinite(declination) || Math.abs(declination) > 180)
        throw new IllegalArgumentException("Invalid magnetic declination");
      this.pageSize = pageSize;
      this.orientation = orientation;
      this.zoom = zoom;
      this.grid = grid;
      this.north = north;
      this.magnetic = magnetic;
      this.scaleBar = scaleBar;
      this.declination = declination;
      this.magneticNote = magneticNote;
    }
  }

  private static double[] paper(String name) {
    return switch (name) {
      case "auto" -> null;
      case "A5" -> new double[] {148, 210};
      case "A4" -> new double[] {210, 297};
      case "A3" -> new double[] {297, 420};
      case "Letter" -> new double[] {215.9, 279.4};
      case "Legal" -> new double[] {215.9, 355.6};
      default -> throw new IllegalArgumentException("Choose a supported page size");
    };
  }

  private final OutputStream out;
  private long position;
  private final List<Long> offsets = new ArrayList<>();

  private MapPdf(OutputStream out) {
    this.out = out;
    offsets.add(0L);
  }

  private void bytes(byte[] b) throws IOException {
    out.write(b);
    position += b.length;
  }

  private void text(String s) throws IOException {
    bytes(s.getBytes(StandardCharsets.ISO_8859_1));
  }

  private int reserve() {
    offsets.add(0L);
    return offsets.size() - 1;
  }

  private void object(int id, String body) throws IOException {
    offsets.set(id, position);
    text(id + " 0 obj\n" + body + "\nendobj\n");
  }

  private void stream(int id, String dictionary, byte[] data) throws IOException {
    offsets.set(id, position);
    text(id + " 0 obj\n<< " + dictionary + " /Length " + data.length + " >>\nstream\n");
    bytes(data);
    text("\nendstream\nendobj\n");
  }

  private static String number(double n) {
    return String.format(Locale.ROOT, "%.6f", n);
  }

  private static String safe(String s) {
    return s.replaceAll("[^\\x20-\\x7e]", "?")
        .replace("\\", "\\\\")
        .replace("(", "\\(")
        .replace(")", "\\)");
  }

  public static int[] extent(double[] b) {
    return extent(b, ZOOM);
  }

  public static int[] extent(double[] b, int zoom) {
    // Match download enumeration exactly, including boundaries on tile edges.
    return new int[] {
      TilePlan.x(b[0], zoom),
      TilePlan.y(b[3], zoom),
      TilePlan.x(Math.nextDown(b[2]), zoom),
      TilePlan.y(Math.nextUp(b[1]), zoom)
    };
  }

  public static int pageCount(double[] bounds) {
    return 1;
  }

  public static void write(OutputStream out, String title, double[] bounds, Tiles tiles)
      throws Exception {
    write(
        out,
        title,
        bounds,
        tiles,
        new Options("auto", "portrait", ZOOM, 0, false, false, false, 0, ""));
  }

  public static void write(
      OutputStream out, String title, double[] bounds, Tiles tiles, Options options)
      throws Exception {
    validateOptions(bounds, options);
    int[] e = extent(bounds, options.zoom);
    double n = 1 << options.zoom,
        west = (bounds[0] + 180) / 360 * n,
        east = (bounds[2] + 180) / 360 * n,
        north = mercY(bounds[3]) * n,
        south = mercY(bounds[1]) * n;
    // Keep the original ~192 dpi print density. UserUnit supports long pages
    // without exceeding PDF's 14,400-unit MediaBox limit or resampling pixels.
    double physicalScale = .375,
        physicalWidth = Math.max(600, (east - west) * 256 * physicalScale + 60),
        physicalHeight = Math.max(200, (south - north) * 256 * physicalScale + 94);
    double[] paper = paper(options.pageSize);
    if (paper != null) {
      physicalWidth = paper[0] * 72 / 25.4;
      physicalHeight = paper[1] * 72 / 25.4;
      if (options.orientation.equals("landscape")) {
        double swap = physicalWidth;
        physicalWidth = physicalHeight;
        physicalHeight = swap;
      }
    }
    double unit = Math.max(1, Math.ceil(Math.max(physicalWidth, physicalHeight) / 14000));
    if (paper != null)
      physicalScale =
          Math.min(
              (physicalWidth - 60) / ((east - west) * 256),
              (physicalHeight - 94) / ((south - north) * 256));
    double scale = physicalScale / unit,
        width = physicalWidth / unit,
        height = physicalHeight / unit,
        left = 30 / unit,
        mapLeft = (width - (east - west) * 256 * scale) / 2,
        top = height - 44 / unit - ((height - 94 / unit) - (south - north) * 256 * scale) / 2;
    MapPdf pdf = new MapPdf(new BufferedOutputStream(out, 64 * 1024));
    pdf.text("%PDF-1.6\n%\u00e2\u00e3\u00cf\u00d3\n");
    int catalog = pdf.reserve(),
        pages = pdf.reserve(),
        font = pdf.reserve(),
        page = pdf.reserve(),
        content = pdf.reserve();
    pdf.object(font, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
    StringBuilder resources = new StringBuilder(), commands = new StringBuilder();
    commands
        .append("BT /F1 ")
        .append(number(13 / unit))
        .append(" Tf ")
        .append(number(left))
        .append(' ')
        .append(number(height - 25 / unit))
        .append(" Td (")
        .append(
            safe(
                title.substring(
                    0,
                    Math.min(
                        title.length(),
                        Math.max(10, Math.min(45, (int) ((width * unit - 160) / 7)))))))
        .append(") Tj ET\n");
    label(commands, left, 26 / unit, 7 / unit, "Topo50 / Topo250 | LINZ | CC BY 4.0");
    label(
        commands,
        left,
        14 / unit,
        6.5 / unit,
        options.magnetic
            ? "Magnetic north: " + options.magneticNote
            : String.format(
                Locale.ROOT,
                "Bounds: %.5f, %.5f to %.5f, %.5f",
                bounds[0],
                bounds[1],
                bounds[2],
                bounds[3]));
    commands
        .append("q ")
        .append(number(mapLeft))
        .append(' ')
        .append(number(top - (south - north) * 256 * scale))
        .append(' ')
        .append(number((east - west) * 256 * scale))
        .append(' ')
        .append(number((south - north) * 256 * scale))
        .append(" re W n\n");
    for (int y = e[1]; y <= e[3]; y++)
      for (int x = e[0]; x <= e[2]; x++) {
        byte[] rgb = tiles.rgb(options.zoom, x, y);
        if (rgb == null || rgb.length != 256 * 256 * 3)
          throw new IOException(
              "A highest-detail Topo tile is missing. Complete the region download first.");
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(compressed)) {
          deflate.write(rgb);
        }
        int image = pdf.reserve();
        pdf.stream(
            image,
            "/Type /XObject /Subtype /Image /Width 256 /Height 256 /ColorSpace /DeviceRGB"
                + " /BitsPerComponent 8 /Interpolate false /Filter /FlateDecode",
            compressed.toByteArray());
        resources.append("/I").append(image).append(' ').append(image).append(" 0 R ");
        commands
            .append("q ")
            .append(number(256 * scale))
            .append(" 0 0 ")
            .append(number(256 * scale))
            .append(' ')
            .append(number(mapLeft + (x - west) * 256 * scale))
            .append(' ')
            .append(number(top - (y - north + 1) * 256 * scale))
            .append(" cm /I")
            .append(image)
            .append(" Do Q\n");
      }
    Drawing drawing = new Drawing(bounds, options.zoom, scale, mapLeft, top, west, north, unit);
    if (options.grid > 0) grid(commands, drawing, options.grid);
    commands.append("Q\n");
    extras(commands, drawing, options, width, height);
    pdf.stream(content, "", commands.toString().getBytes(StandardCharsets.ISO_8859_1));
    pdf.object(
        page,
        "<< /Type /Page /Parent "
            + pages
            + " 0 R /MediaBox [0 0 "
            + number(width)
            + " "
            + number(height)
            + "] /UserUnit "
            + number(unit)
            + " /Resources << /Font << /F1 "
            + font
            + " 0 R >> /XObject << "
            + resources
            + ">> >> /Contents "
            + content
            + " 0 R >>");
    pdf.object(pages, "<< /Type /Pages /Count 1 /Kids [" + page + " 0 R] >>");
    pdf.object(
        catalog,
        "<< /Type /Catalog /Pages "
            + pages
            + " 0 R /ViewerPreferences << /PrintScaling /None >> >>");
    long xref = pdf.position;
    pdf.text("xref\n0 " + pdf.offsets.size() + "\n0000000000 65535 f \n");
    for (int i = 1; i < pdf.offsets.size(); i++)
      pdf.text(String.format(Locale.ROOT, "%010d 00000 n \n", pdf.offsets.get(i)));
    pdf.text(
        "trailer\n<< /Size "
            + pdf.offsets.size()
            + " /Root "
            + catalog
            + " 0 R >>\nstartxref\n"
            + xref
            + "\n%%EOF\n");
    pdf.out.flush();
  }

  private static final class Drawing {
    final double[] bounds;
    final double n, scale, left, top, west, north, unit, width, height, bottom;

    Drawing(
        double[] bounds,
        int z,
        double scale,
        double left,
        double top,
        double west,
        double north,
        double unit) {
      this.bounds = bounds;
      this.n = 1 << z;
      this.scale = scale;
      this.left = left;
      this.top = top;
      this.west = west;
      this.north = north;
      this.unit = unit;
      this.width = ((bounds[2] + 180) / 360 * n - west) * 256 * scale;
      this.height = (mercY(bounds[1]) * n - north) * 256 * scale;
      this.bottom = top - height;
    }

    double[] point(double lon, double lat) {
      return new double[] {
        left + ((lon + 180) / 360 * n - west) * 256 * scale,
        top - (mercY(lat) * n - north) * 256 * scale
      };
    }
  }

  private static void label(StringBuilder c, double x, double y, double size, String text) {
    c.append("BT /F1 ")
        .append(number(size))
        .append(" Tf ")
        .append(number(x))
        .append(' ')
        .append(number(y))
        .append(" Td (")
        .append(safe(text))
        .append(") Tj ET\n");
  }

  private static void line(StringBuilder c, double x, double y, double x2, double y2) {
    c.append(number(x))
        .append(' ')
        .append(number(y))
        .append(" m ")
        .append(number(x2))
        .append(' ')
        .append(number(y2))
        .append(" l S\n");
  }

  private static double[] gridExtent(double[] b) {
    double minE = Double.POSITIVE_INFINITY, maxE = -minE, minN = minE, maxN = -minE;
    for (int edge = 0; edge < 4; edge++)
      for (int i = 0; i <= 32; i++) {
        double t = i / 32.0,
            lon = edge < 2 ? b[0] + t * (b[2] - b[0]) : b[edge == 2 ? 0 : 2],
            lat = edge < 2 ? b[edge == 0 ? 1 : 3] : b[1] + t * (b[3] - b[1]);
        double[] p = Nztm.forward(lon, lat);
        minE = Math.min(minE, p[0]);
        maxE = Math.max(maxE, p[0]);
        minN = Math.min(minN, p[1]);
        maxN = Math.max(maxN, p[1]);
      }
    return new double[] {minE, maxE, minN, maxN};
  }

  public static void validateOptions(double[] bounds, Options options) {
    new TilePlan(bounds, ZOOM, "topo", true, options.zoom);
    if (options.grid > 0) {
      double[] e = gridExtent(bounds);
      if ((e[1] - e[0]) / options.grid > 200 || (e[3] - e[2]) / options.grid > 200)
        throw new IllegalArgumentException(
            "The grid is too dense for this region. Choose wider grid spacing.");
    }
  }

  private static void grid(StringBuilder c, Drawing d, int spacing) {
    double[] area = gridExtent(d.bounds);
    double minE = area[0], maxE = area[1], minN = area[2], maxN = area[3];
    c.append("q 0.14 0.38 0.46 RG ").append(number(.35 / d.unit)).append(" w [] 0 d\n");
    for (int axis = 0; axis < 2; axis++)
      for (double value = Math.ceil((axis == 0 ? minE : minN) / spacing) * spacing;
          value <= (axis == 0 ? maxE : maxN);
          value += spacing) {
        double[] previous = null, edgeLabel = null;
        for (int i = 0; i <= 64; i++) {
          double t = i / 64.0,
              e = axis == 0 ? value : minE + t * (maxE - minE),
              n = axis == 0 ? minN + t * (maxN - minN) : value;
          double[] ll = Nztm.inverse(e, n), p = d.point(ll[0], ll[1]);
          if (previous == null)
            c.append(number(p[0])).append(' ').append(number(p[1])).append(" m ");
          else c.append(number(p[0])).append(' ').append(number(p[1])).append(" l ");
          if (previous != null) {
            double target = axis == 0 ? d.bottom : d.left,
                pa = previous[axis == 0 ? 1 : 0],
                pb = p[axis == 0 ? 1 : 0];
            if (pa != pb && (pa - target) * (pb - target) <= 0) {
              double f = (target - pa) / (pb - pa),
                  x = previous[0] + f * (p[0] - previous[0]),
                  y = previous[1] + f * (p[1] - previous[1]);
              if (x >= d.left && x <= d.left + d.width && y >= d.bottom && y <= d.top)
                edgeLabel = new double[] {x, y};
            }
          }
          previous = p;
        }
        c.append("S\n");
        if (edgeLabel != null) {
          double size = 5.5 / d.unit,
              x = axis == 0 ? edgeLabel[0] + 1 / d.unit : d.left + 1 / d.unit,
              y = axis == 0 ? d.bottom + 2 / d.unit : edgeLabel[1] + 2 / d.unit;
          String text = String.format(Locale.ROOT, "%.0f", value);
          c.append("q 1 g ")
              .append(number(x))
              .append(' ')
              .append(number(y - 1 / d.unit))
              .append(' ')
              .append(number(text.length() * size * .57))
              .append(' ')
              .append(number(size + 2 / d.unit))
              .append(" re f 0 g\n");
          label(c, x, y, size, text);
          c.append("Q\n");
        }
      }
    c.append("Q\n");
  }

  private static void arrow(
      StringBuilder c, double x, double y, double angle, double unit, String text) {
    double a = Math.toRadians(angle),
        dx = Math.sin(a),
        dy = Math.cos(a),
        length = 20 / unit,
        tx = x + dx * length,
        ty = y + dy * length;
    c.append("q 0 G 0 g ").append(number(.8 / unit)).append(" w\n");
    line(c, x, y, tx, ty);
    line(c, tx, ty, tx - dx * 5 / unit - dy * 2.5 / unit, ty - dy * 5 / unit + dx * 2.5 / unit);
    line(c, tx, ty, tx - dx * 5 / unit + dy * 2.5 / unit, ty - dy * 5 / unit - dx * 2.5 / unit);
    label(c, x - 8 / unit, y - 8 / unit, 6 / unit, text);
    c.append("Q\n");
  }

  private static void extras(StringBuilder c, Drawing d, Options o, double width, double height) {
    if (o.north) arrow(c, width - 95 / d.unit, height - 31 / d.unit, 0, d.unit, "TN");
    if (o.magnetic)
      arrow(
          c,
          width - 48 / d.unit,
          height - 31 / d.unit,
          o.declination,
          d.unit,
          String.format(
              Locale.ROOT, "MN %.1f%s", Math.abs(o.declination), o.declination >= 0 ? "E" : "W"));
    if (o.grid > 0)
      label(
          c, 30 / d.unit, 35 / d.unit, 7 / d.unit, "NZTM2000 (EPSG:2193) | Grid " + o.grid + " m");
    double latitude = (d.bounds[1] + d.bounds[3]) / 2,
        metres =
            (d.bounds[2] - d.bounds[0])
                * Math.PI
                / 180
                * 6378137
                * Math.cos(Math.toRadians(latitude));
    if (o.scaleBar) {
      double max = metres * Math.min(100 / d.unit, d.width * .3) / d.width,
          pow = Math.pow(10, Math.floor(Math.log10(max))),
          v = max / pow,
          bar = (v >= 5 ? 5 : v >= 2 ? 2 : 1) * pow,
          length = bar / metres * d.width,
          x = width - 150 / d.unit,
          y = 37 / d.unit;
      c.append("q 0 G 0 g ").append(number(.7 / d.unit)).append(" w\n");
      line(c, x, y, x + length, y);
      line(c, x, y - 2 / d.unit, x, y + 2 / d.unit);
      line(c, x + length, y - 2 / d.unit, x + length, y + 2 / d.unit);
      label(
          c,
          x,
          y + 5 / d.unit,
          6 / d.unit,
          String.format(
              Locale.ROOT, bar >= 1000 ? "%.2g km" : "%.0f m", bar >= 1000 ? bar / 1000 : bar));
      label(
          c,
          x,
          y - 9 / d.unit,
          6 / d.unit,
          String.format(
              Locale.ROOT, "Scale ~1:%,.0f at centre", metres / (d.width * d.unit / 72 * .0254)));
      c.append("Q\n");
    }
  }

  private static double mercY(double lat) {
    double a = Math.toRadians(lat);
    return (1 - Math.log(Math.tan(a) + 1 / Math.cos(a)) / Math.PI) / 2;
  }
}
