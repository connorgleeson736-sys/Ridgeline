package nz.ridgeline.mobile;

import static org.junit.Assert.*;

import android.graphics.*;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.*;
import org.json.*;
import org.junit.*;

public class OfflineArchiveTest {
  TileArchive archive;
  final List<String> created = new ArrayList<>();

  @Before
  public void setup() {
    archive = TileArchive.get(InstrumentationRegistry.getInstrumentation().getTargetContext());
  }

  @After
  public void cleanup() throws Exception {
    for (String id : created) archive.remove(id);
  }

  String create() throws Exception {
    double[] b = {170.13, -43.60, 170.15, -43.58};
    TilePlan p = new TilePlan(b, 13, "aerial", false);
    String id =
        archive.create(
            new JSONObject()
                .put("name", "Automated test")
                .put("key", "test-only")
                .put("bounds", new JSONArray(b))
                .put("zoom", 13)
                .put("imagery", "aerial")
                .put("topo", false),
            p);
    created.add(id);
    return id;
  }

  static byte[] image(int color) {
    Bitmap bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
    bmp.eraseColor(color);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
    bmp.recycle();
    return out.toByteArray();
  }

  @Test
  public void restartedProcessCanContinueTheSameGeneration() throws Exception {
    String id = create(), generation = archive.queue(id);
    assertTrue(archive.begin(id, generation));
    assertTrue(archive.begin(id, generation));
    archive.state(id, "paused", "");
    assertFalse(archive.begin(id, generation));
  }

  @Test
  public void savedTilesSurvivePauseAndListsNeverExposeKeys() throws Exception {
    String id = create();
    String gen = archive.queue(id);
    assertTrue(archive.begin(id, gen));
    TileAddress t = TileAddress.tile("aerial", 13, 7967, 5200);
    byte[] bytes = image(0xff579f67);
    assertTrue(archive.write(id, t, bytes, gen));
    archive.state(id, "paused", "");
    assertArrayEquals(bytes, archive.read(t));
    JSONArray list = archive.list();
    for (int i = 0; i < list.length(); i++) assertFalse(list.getJSONObject(i).has("key"));
  }

  @Test
  public void staleDownloadCannotOverwriteResumedGeneration() throws Exception {
    String id = create(), first = archive.queue(id);
    assertTrue(archive.begin(id, first));
    String second = archive.queue(id);
    assertFalse(archive.begin(id, first));
    assertTrue(archive.begin(id, second));
    TileAddress t = TileAddress.tile("aerial", 13, 7967, 5200);
    assertFalse(archive.write(id, t, image(0xffffffff), first));
    archive.finish(id, first, "incomplete", "Old request");
    assertTrue(archive.active(id, second));
  }

  @Test
  public void removalRejectsAnInFlightWrite() throws Exception {
    String id = create(), gen = archive.queue(id);
    assertTrue(archive.begin(id, gen));
    archive.remove(id);
    assertFalse(
        archive.write(id, TileAddress.tile("aerial", 13, 7967, 5200), image(0xffffffff), gen));
    assertFalse(archive.exists(id));
  }

  @Test
  public void ancestorElevationIsDecodedWithoutChannelInterpolationArtifacts() throws Exception {
    // Deliberately cross a blue-channel rollover, where independently
    // interpolating RGB channels would produce wildly incorrect heights.
    Bitmap bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
    int[] pixels = new int[65536];
    for (int y = 0; y < 256; y++)
      for (int x = 0; x < 256; x++) pixels[y * 256 + x] = 0xff000000 | (0x01fff0 + x);
    bmp.setPixels(pixels, 0, 256, 0, 0, 256, 256);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
    bmp.recycle();
    byte[] data =
        TileResampler.crop(out.toByteArray(), TileAddress.tile("elevation", 14, 15934, 10400), 1);
    Bitmap child = BitmapFactory.decodeByteArray(data, 0, data.length);
    assertEquals(0x020030, child.getPixel(128, 100) & 0xffffff);
    child.recycle();
  }

  @Test
  public void offlineSettingPersistsAndBoundedReaderRejectsLargeInput() throws Exception {
    boolean old = archive.offlineOnly();
    try {
      archive.offlineOnly(true);
      assertTrue(archive.offlineOnly());
      assertThrows(
          IOException.class, () -> TileNetwork.read(new ByteArrayInputStream(new byte[100]), 99));
    } finally {
      archive.offlineOnly(old);
    }
  }
}
