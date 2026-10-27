package nz.ridgeline.mobile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.DeflaterOutputStream;

/** Streaming, lossless raster atlas. Keeps one decoded 256px tile in memory. */
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
    return String.format(Locale.ROOT, "%.3f", n);
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

  public static int pageCount(double[] b) {
    int[] e = extent(b);
    return ((e[2] - e[0] + 8) / 8) * ((e[3] - e[1] + 5) / 5);
  }

  public static void write(OutputStream out, String title, double[] bounds, Tiles tiles)
      throws Exception {
    if (bounds == null || bounds.length != 4 || bounds[0] >= bounds[2] || bounds[1] >= bounds[3])
      throw new IOException("Invalid PDF bounds.");
    new TilePlan(bounds, ZOOM, "topo", true);
    int[] e = extent(bounds);
    int count = pageCount(bounds);
    if (count > 320)
      throw new IOException("Choose a smaller region for PDF export (320 page limit).");
    MapPdf pdf = new MapPdf(out);
    pdf.text("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n");
    int catalog = pdf.reserve(), pages = pdf.reserve(), font = pdf.reserve();
    List<Integer> kids = new ArrayList<>();
    pdf.object(font, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
    int pageNo = 0;
    double scale = 782.0 / (8 * 256), top = 548;
    double n = 1 << ZOOM;
    double west = (bounds[0] + 180) / 360 * n, east = (bounds[2] + 180) / 360 * n;
    double north = mercY(bounds[3]) * n, south = mercY(bounds[1]) * n;
    for (int py = e[1]; py <= e[3]; py += 5)
      for (int px = e[0]; px <= e[2]; px += 8) {
        int page = pdf.reserve(), content = pdf.reserve();
        kids.add(page);
        StringBuilder resources = new StringBuilder(), commands = new StringBuilder();
        commands.append("BT /F1 13 Tf 30 572 Td (").append(safe(title)).append(") Tj ET\n");
        commands
            .append("BT /F1 8 Tf 30 20 Td (LINZ NZ Topo | CC BY 4.0 | Native zoom 16 | Page ")
            .append(++pageNo)
            .append(" / ")
            .append(count)
            .append(") Tj ET\n");
        commands
            .append("BT /F1 8 Tf 30 8 Td (")
            .append(
                safe(
                    String.format(
                        Locale.ROOT,
                        "Bounds: %.5f, %.5f to %.5f, %.5f | North is up",
                        bounds[0],
                        bounds[1],
                        bounds[2],
                        bounds[3])))
            .append(") Tj ET\n");
        double left = Math.max(0, (west - px) * 256 * scale),
            right = Math.min(782, (east - px) * 256 * scale),
            upper = Math.max(0, (north - py) * 256 * scale),
            lower = Math.min(5 * 256 * scale, (south - py) * 256 * scale);
        commands
            .append("q ")
            .append(number(30 + left))
            .append(' ')
            .append(number(top - lower))
            .append(' ')
            .append(number(right - left))
            .append(' ')
            .append(number(lower - upper))
            .append(" re W n\n");
        for (int y = py; y <= Math.min(py + 4, e[3]); y++)
          for (int x = px; x <= Math.min(px + 7, e[2]); x++) {
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
                .append(number(30 + (x - px) * 256 * scale))
                .append(' ')
                .append(number(top - (y - py + 1) * 256 * scale))
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
                + " 0 R /MediaBox [0 0 842 595] /Resources << /Font << /F1 "
                + font
                + " 0 R >> /XObject << "
                + resources
                + ">> >> /Contents "
                + content
                + " 0 R >>");
      }
    StringBuilder children = new StringBuilder();
    for (int child : kids) children.append(child).append(" 0 R ");
    pdf.object(pages, "<< /Type /Pages /Count " + kids.size() + " /Kids [" + children + "] >>");
    pdf.object(catalog, "<< /Type /Catalog /Pages " + pages + " 0 R >>");
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
