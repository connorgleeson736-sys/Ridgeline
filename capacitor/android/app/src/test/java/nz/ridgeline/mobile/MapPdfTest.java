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
    assertTrue(pdf.startsWith("%PDF-1.4"));
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
}
