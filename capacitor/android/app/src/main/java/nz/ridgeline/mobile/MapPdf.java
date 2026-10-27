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
    // Match download enumeration exactly, including boundaries on tile edges.
    return new int[] {
      TilePlan.x(b[0], ZOOM),
      TilePlan.y(b[3], ZOOM),
      TilePlan.x(Math.nextDown(b[2]), ZOOM),
      TilePlan.y(Math.nextUp(b[1]), ZOOM)
    };
  }

  public static int pageCount(double[] bounds) {
    return 1;
  }

  public static void write(OutputStream out, String title, double[] bounds, Tiles tiles)
      throws Exception {
    new TilePlan(bounds, ZOOM, "topo", true);
    int[] e = extent(bounds);
    double n = 1 << ZOOM,
        west = (bounds[0] + 180) / 360 * n,
        east = (bounds[2] + 180) / 360 * n,
        north = mercY(bounds[3]) * n,
        south = mercY(bounds[1]) * n;
    // Keep the original ~192 dpi print density. UserUnit supports long pages
    // without exceeding PDF's 14,400-unit MediaBox limit or resampling pixels.
    double physicalScale = .375,
        physicalWidth = Math.max(600, (east - west) * 256 * physicalScale + 60),
        physicalHeight = Math.max(200, (south - north) * 256 * physicalScale + 94);
    double unit = Math.max(1, Math.ceil(Math.max(physicalWidth, physicalHeight) / 14000)),
        scale = physicalScale / unit;
    double width = physicalWidth / unit,
        height = physicalHeight / unit,
        left = 30 / unit,
        mapLeft = (width - (east - west) * 256 * scale) / 2,
        top = height - 44 / unit;
    MapPdf pdf = new MapPdf(out);
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
        .append(safe(title))
        .append(") Tj ET\n");
    commands
        .append("BT /F1 ")
        .append(number(8 / unit))
        .append(" Tf ")
        .append(number(left))
        .append(' ')
        .append(number(22 / unit))
        .append(" Td (LINZ NZ Topo | CC BY 4.0 | Native zoom 16 | North is up) Tj ET\n");
    commands
        .append("BT /F1 ")
        .append(number(8 / unit))
        .append(" Tf ")
        .append(number(left))
        .append(' ')
        .append(number(10 / unit))
        .append(" Td (")
        .append(
            safe(
                String.format(
                    Locale.ROOT,
                    "Bounds: %.5f, %.5f to %.5f, %.5f",
                    bounds[0],
                    bounds[1],
                    bounds[2],
                    bounds[3])))
        .append(") Tj ET\n");
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
        byte[] rgb = tiles.rgb(ZOOM, x, y);
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
                + " /BitsPerComponent 8 /Filter /FlateDecode",
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
    commands.append("Q\n");
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
    out.flush();
  }

  private static double mercY(double lat) {
    double a = Math.toRadians(lat);
    return (1 - Math.log(Math.tan(a) + 1 / Math.cos(a)) / Math.PI) / 2;
  }
}
