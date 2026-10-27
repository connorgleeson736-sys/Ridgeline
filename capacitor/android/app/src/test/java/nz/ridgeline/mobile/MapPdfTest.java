package nz.ridgeline.mobile;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;

public class MapPdfTest {
  @Test
  public void streamsHighestDetailWithValidCrossReferences() throws Exception {
    double[] b = {170.12, -43.61, 170.16, -43.57};
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Set<String> seen = new HashSet<>();
    MapPdf.write(
        out,
        "Topo (test)",
        b,
        (z, x, y) -> {
          assertEquals(16, z);
          assertTrue(seen.add(z + "/" + x + "/" + y));
          byte[] rgb = new byte[256 * 256 * 3];
          Arrays.fill(rgb, (byte) 220);
          return rgb;
        });
    String pdf = out.toString(StandardCharsets.ISO_8859_1);
    assertTrue(pdf.startsWith("%PDF-1.6"));
    assertTrue(pdf.contains("/Count 1 /Kids"));
    assertTrue(pdf.contains("/UserUnit "));
    assertTrue(pdf.endsWith("%%EOF\n"));
    long expected = new TilePlan(b, 16, "topo", true).tiles.stream().filter(t -> t.z == 16).count();
    assertEquals(expected, seen.size());
    int start =
        Integer.parseInt(
            pdf.substring(pdf.lastIndexOf("startxref\n") + 10, pdf.lastIndexOf("\n%%EOF")).trim());
    assertTrue(pdf.substring(start).startsWith("xref\n"));
    String[] rows = pdf.substring(start).split("\n");
    int objects = Integer.parseInt(rows[1].split(" ")[1]);
    for (int id = 1; id < objects; id++) {
      int offset = Integer.parseInt(rows[id + 2].substring(0, 10));
      assertTrue(pdf.substring(offset).startsWith(id + " 0 obj\n"));
    }
  }

  @Test
  public void longMapsStayOnOnePageWithNativeImagesAndUserUnits() throws Exception {
    int x = TilePlan.x(170.14, 16), y = TilePlan.y(-43.59, 16);
    double[] b = {
      (x + .2) / 65536 * 360 - 180,
      Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (y + 1.7) / 65536)))),
      (x + 199.8) / 65536 * 360 - 180,
      Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (y + .3) / 65536))))
    };
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    MapPdf.write(out, "Wide test", b, (z, tx, ty) -> new byte[256 * 256 * 3]);
    String pdf = out.toString(StandardCharsets.ISO_8859_1);
    assertTrue(pdf.contains("/Count 1 /Kids"));
    assertTrue(pdf.contains("/UserUnit 2.000000"));
    assertEquals(400, pdf.split("/Subtype /Image", -1).length - 1);
    assertEquals(400, pdf.split("/Width 256 /Height 256", -1).length - 1);
  }

  @Test
  public void missingTilesFailInsteadOfExportingBlankMaps() {
    assertThrows(
        Exception.class,
        () ->
            MapPdf.write(
                new ByteArrayOutputStream(),
                "test",
                new double[] {170.12, -43.61, 170.16, -43.57},
                (z, x, y) -> null));
  }

  @Test
  public void a4AndLandscapeKeepNativeImagesAndVectorFurniture() throws Exception {
    for (String orientation : new String[] {"portrait", "landscape"}) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      MapPdf.Options options =
          new MapPdf.Options(
              "A4", orientation, 18, 1000, true, true, true, 24.5, "User supplied | 2026-10-27");
      MapPdf.write(
          out,
          "A4 region",
          new double[] {170.138, -43.592, 170.142, -43.588},
          (z, x, y) -> {
            assertEquals(18, z);
            return new byte[256 * 256 * 3];
          },
          options);
      String pdf = out.toString(StandardCharsets.ISO_8859_1);
      assertTrue(
          pdf.contains(
              orientation.equals("portrait")
                  ? "/MediaBox [0 0 595.275591 841.889764]"
                  : "/MediaBox [0 0 841.889764 595.275591]"));
      assertTrue(pdf.contains("NZTM2000"));
      assertTrue(pdf.contains("MN 24.5E"));
      assertTrue(pdf.contains("Scale ~1:"));
      assertTrue(pdf.contains("/Interpolate false"));
      assertTrue(pdf.contains("/Count 1 /Kids"));
    }
  }

  @Test
  public void rejectsUnsupportedPaperAndUnsafeGridOptions() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new MapPdf.Options("bad", "portrait", 18, 1000, true, false, true, 0, ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MapPdf.Options("A4", "portrait", 18, 1, true, false, true, 0, ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MapPdf.Options("A4", "portrait", 18, 1000, true, true, true, Double.NaN, ""));
  }

  @Test
  public void denseGridIsRejectedBeforeWriting() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MapPdf.write(
                out,
                "Dense grid",
                new double[] {170, -43.6, 171, -43.599},
                (z, x, y) -> new byte[256 * 256 * 3],
                new MapPdf.Options("A4", "portrait", 18, 100, true, false, true, 0, "")));
    assertEquals(0, out.size());
  }
}
