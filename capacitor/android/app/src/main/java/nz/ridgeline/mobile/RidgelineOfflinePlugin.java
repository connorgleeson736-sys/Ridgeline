package nz.ridgeline.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.Bundle;
import androidx.activity.result.ActivityResult;
import androidx.core.content.ContextCompat;
import androidx.work.WorkManager;
import com.getcapacitor.*;
import com.getcapacitor.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.*;

@CapacitorPlugin(
    name = "RidgelineOffline",
    permissions = {
      @Permission(
          alias = "location",
          strings = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
          })
    })
public final class RidgelineOfflinePlugin extends Plugin {
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private LocationManager locations;
  private String estimateSignature = "";
  private JSObject estimateCached;
  private long estimateTime;
  private boolean watching = false;
  private final LocationListener listener =
      new LocationListener() {
        @Override
        public void onLocationChanged(Location l) {
          if (!watching) return;
          JSObject d = new JSObject();
          d.put("longitude", l.getLongitude());
          d.put("latitude", l.getLatitude());
          d.put("accuracy", l.getAccuracy());
          notifyListeners("location", d);
        }

        @Override
        public void onProviderDisabled(String p) {
          JSObject d = new JSObject();
          d.put("error", "Location provider is disabled. Enable Location in Android settings.");
          notifyListeners("location", d);
        }

        @Override
        public void onProviderEnabled(String p) {}

        @Override
        public void onStatusChanged(String p, int s, Bundle b) {}
      };

  private interface Operation {
    JSObject run() throws Exception;
  }

  private void async(PluginCall call, Operation op) {
    io.execute(
        () -> {
          try {
            JSObject result = op.run();
            if (result != null) call.resolve(result);
          } catch (Exception e) {
            call.reject(
                e.getMessage() == null ? "Operation failed. Please try again." : e.getMessage());
          }
        });
  }

  private TileArchive archive() {
    return TileArchive.get(getContext());
  }

  private double[] bounds(PluginCall call) throws Exception {
    JSArray b = call.getArray("bounds");
    if (b == null || b.length() != 4)
      throw new IllegalArgumentException("Specify all four region coordinates.");
    return new double[] {b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3)};
  }

  private TilePlan plan(PluginCall c) throws Exception {
    return new TilePlan(
        bounds(c),
        c.getInt("zoom", 15),
        c.getString("imagery", "aerial"),
        c.getBoolean("topo", true));
  }

  private String id(PluginCall c) {
    String id = c.getString("id", "");
    if (!id.matches("[a-f0-9-]{36}"))
      throw new IllegalArgumentException("Invalid region identifier");
    return id;
  }

  @PluginMethod
  public void estimateRegion(PluginCall c) {
    async(
        c,
        () -> {
          TilePlan p = plan(c);
          String signature = c.getData().toString() + archive().offlineOnly();
          if (estimateCached != null
              && signature.equals(estimateSignature)
              && android.os.SystemClock.elapsedRealtime() - estimateTime < 60000)
            return estimateCached;
          JSObject r = new JSObject();
          r.put("total", p.tiles.size());
          TileEstimate.Result estimate =
              TileEstimate.estimate(
                  p,
                  (tile, network) -> {
                    byte[] bytes =
                        network
                            ? TileNetwork.sample(tile, c.getString("key", ""))
                            : archive().read(tile);
                    return bytes == null ? -1 : bytes.length;
                  },
                  !archive().offlineOnly() && !c.getString("key", "").isBlank());
          r.put("estimatedMiB", Math.round(estimate.bytes() / 104857.6) / 10.0);
          r.put("lowMiB", Math.round(estimate.low() / 104857.6) / 10.0);
          r.put("highMiB", Math.round(estimate.high() / 104857.6) / 10.0);
          r.put("samples", estimate.samples());
          r.put("sampleFailures", estimate.failures());
          estimateSignature = signature;
          estimateCached = r;
          estimateTime = android.os.SystemClock.elapsedRealtime();
          return r;
        });
  }

  @PluginMethod
  public void downloadRegion(PluginCall c) {
    async(
        c,
        () -> {
          TilePlan p = plan(c);
          String key = c.getString("key", "").trim(),
              name = c.getString("name", "My region").trim();
          if (key.isEmpty() || key.length() > 256)
            throw new IllegalArgumentException(
                "Connect a valid LINZ key before downloading elevation.");
          if (name.isEmpty() || name.length() > 60)
            throw new IllegalArgumentException("Use a region name of 1–60 characters.");
          JSONObject cfg =
              new JSONObject()
                  .put("name", name)
                  .put("key", key)
                  .put("bounds", new JSONArray(bounds(c)))
                  .put(
                      "zoom",
                      "topo".equals(c.getString("imagery", "aerial")) ? 16 : c.getInt("zoom", 15))
                  .put("imagery", c.getString("imagery", "aerial"))
                  .put("topo", c.getBoolean("topo", true));
          String id = archive().create(cfg, p);
          RegionDownloadWorker.enqueue(getContext(), id);
          JSObject r = new JSObject();
          r.put("id", id);
          return r;
        });
  }

  @PluginMethod
  public void listRegions(PluginCall c) {
    async(
        c,
        () -> {
          JSObject r = new JSObject();
          r.put("regions", archive().list());
          return r;
        });
  }

  @PluginMethod
  public void pauseRegion(PluginCall c) {
    async(
        c,
        () -> {
          String id = id(c);
          archive().state(id, "paused", "");
          WorkManager.getInstance(getContext()).cancelUniqueWork("region-" + id).getResult().get();
          return new JSObject();
        });
  }

  @PluginMethod
  public void resumeRegion(PluginCall c) {
    async(
        c,
        () -> {
          String id = id(c);
          archive().region(id);
          archive().state(id, "paused", "");
          WorkManager.getInstance(getContext()).cancelUniqueWork("region-" + id).getResult().get();
          archive().state(id, "queued", "");
          RegionDownloadWorker.enqueue(getContext(), id);
          return new JSObject();
        });
  }

  @PluginMethod
  public void removeRegion(PluginCall c) {
    async(
        c,
        () -> {
          String id = id(c);
          archive().state(id, "paused", "");
          WorkManager.getInstance(getContext()).cancelUniqueWork("region-" + id).getResult().get();
          archive().remove(id);
          return new JSObject();
        });
  }

  @PluginMethod
  public void getSettings(PluginCall c) {
    async(
        c,
        () -> {
          JSObject r = new JSObject();
          r.put("offlineOnly", archive().offlineOnly());
          return r;
        });
  }

  @PluginMethod
  public void setOfflineOnly(PluginCall c) {
    io.execute(
        () -> {
          try {
            archive().offlineOnly(c.getBoolean("enabled", false));
            getActivity()
                .runOnUiThread(
                    () -> {
                      bridge.getWebView().clearCache(true);
                      c.resolve();
                    });
          } catch (Exception e) {
            c.reject(e.getMessage());
          }
        });
  }

  @PluginMethod
  public void overviewStatus(PluginCall c) {
    async(c, () -> OverviewStore.get(getContext()).status());
  }

  @PluginMethod
  public void removeOverviews(PluginCall c) {
    async(
        c,
        () -> {
          OverviewStore.get(getContext()).remove();
          return OverviewStore.get(getContext()).status();
        });
  }

  @PluginMethod
  public void importOverviews(PluginCall c) {
    Intent intent =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    startActivityForResult(c, intent, "overviewImportResult");
  }

  @ActivityCallback
  private void overviewImportResult(PluginCall c, ActivityResult r) {
    if (c == null) return;
    if (r.getResultCode() != Activity.RESULT_OK || r.getData() == null) {
      JSObject result = new JSObject();
      result.put("cancelled", true);
      c.resolve(result);
      return;
    }
    java.util.List<android.net.Uri> uris = new java.util.ArrayList<>();
    Intent intent = r.getData();
    if (intent.getClipData() != null) {
      for (int i = 0; i < intent.getClipData().getItemCount(); i++)
        uris.add(intent.getClipData().getItemAt(i).getUri());
    } else if (intent.getData() != null) uris.add(intent.getData());
    if (uris.size() > 100) {
      c.reject("Select no more than 100 overview ZIPs at a time.");
      return;
    }
    async(
        c,
        () -> {
          int imported = 0;
          OverviewStore store = OverviewStore.get(getContext());
          for (android.net.Uri uri : uris)
            try (InputStream in = getContext().getContentResolver().openInputStream(uri)) {
              if (in == null) throw new IOException("An overview ZIP could not be opened.");
              imported +=
                  store.importZip(in, status -> notifyListeners("overviewProgress", status));
            }
          if (imported == 0)
            throw new IOException(
                "No matching overview packs were found. Select the prepared South Island overview"
                    + " ZIPs.");
          return store.status();
        });
  }

  @PluginMethod
  public void importGpx(PluginCall c) {
    startActivityForResult(
        c,
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*"),
        "importResult");
  }

  @ActivityCallback
  private void importResult(PluginCall c, ActivityResult r) {
    if (c == null) return;
    if (r.getResultCode() != Activity.RESULT_OK
        || r.getData() == null
        || r.getData().getData() == null) {
      JSObject d = new JSObject();
      d.put("cancelled", true);
      c.resolve(d);
      return;
    }
    android.net.Uri uri = r.getData().getData();
    async(
        c,
        () -> {
          byte[] data;
          try (InputStream in = getContext().getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("The selected file could not be opened.");
            data = TileNetwork.read(in, 2 * 1024 * 1024);
          }
          String text = new String(data, StandardCharsets.UTF_8),
              upper = text.toUpperCase(java.util.Locale.ROOT);
          if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY"))
            throw new IOException("GPX documents containing external entities are not supported.");
          JSObject d = new JSObject();
          d.put("name", "Imported route.gpx");
          d.put("text", text);
          return d;
        });
  }

  @PluginMethod
  public void exportGpx(PluginCall c) {
    if (c.getString("text", "").length() > 4 * 1024 * 1024) {
      c.reject("GPX exceeds the export size limit.");
      return;
    }
    String name = c.getString("name", "route").replaceAll("[^a-zA-Z0-9_-]+", "-");
    if (name.length() > 60) name = name.substring(0, 60);
    startActivityForResult(
        c,
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/gpx+xml")
            .putExtra(Intent.EXTRA_TITLE, name + ".gpx"),
        "exportResult");
  }

  @ActivityCallback
  private void exportResult(PluginCall c, ActivityResult r) {
    if (c == null) return;
    if (r.getResultCode() != Activity.RESULT_OK
        || r.getData() == null
        || r.getData().getData() == null) {
      JSObject d = new JSObject();
      d.put("cancelled", true);
      c.resolve(d);
      return;
    }
    android.net.Uri uri = r.getData().getData();
    async(
        c,
        () -> {
          try (OutputStream out = getContext().getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("The GPX file could not be saved.");
            out.write(c.getString("text", "").getBytes(StandardCharsets.UTF_8));
          }
          return new JSObject();
        });
  }

  @PluginMethod
  public void exportRegionPdf(PluginCall c) {
    async(
        c,
        () -> {
          String regionId = id(c);
          JSONObject region = archive().region(regionId);
          if (!"complete".equals(region.optString("state"))
              || !(region.optBoolean("topo") || "topo".equals(region.optString("imagery"))))
            throw new IOException("Complete a region download including NZ Topo first.");
          JSONArray b = region.getJSONArray("bounds");
          double[] bounds = {b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3)};
          int[] extent = MapPdf.extent(bounds);
          if (MapPdf.pageCount(bounds) > 320)
            throw new IOException("Choose a smaller region for PDF export.");
          for (int y = extent[1]; y <= extent[3]; y++)
            for (int x = extent[0]; x <= extent[2]; x++)
              if (archive().size(regionId, TileAddress.tile("topo-raster", 16, x, y)) < 0)
                throw new IOException(
                    "This older region lacks maximum-detail Topo. Download it again with NZ Topo at"
                        + " zoom 16.");
          if (MapPdf.pageCount(bounds) > 320)
            throw new IOException("Choose a smaller region for PDF export.");
          getActivity()
              .runOnUiThread(
                  () ->
                      startActivityForResult(
                          c,
                          new Intent(Intent.ACTION_CREATE_DOCUMENT)
                              .addCategory(Intent.CATEGORY_OPENABLE)
                              .setType("application/pdf")
                              .putExtra(
                                  Intent.EXTRA_TITLE,
                                  region
                                          .optString("name", "region")
                                          .replaceAll("[^a-zA-Z0-9_-]+", "-")
                                      + ".pdf"),
                          "pdfResult"));
          return null;
        });
  }

  @ActivityCallback
  private void pdfResult(PluginCall c, ActivityResult result) {
    if (c == null) return;
    if (result.getResultCode() != Activity.RESULT_OK
        || result.getData() == null
        || result.getData().getData() == null) {
      JSObject r = new JSObject();
      r.put("cancelled", true);
      c.resolve(r);
      return;
    }
    android.net.Uri uri = result.getData().getData();
    async(
        c,
        () -> {
          String regionId = id(c);
          JSONObject region = archive().region(regionId);
          JSONArray b = region.getJSONArray("bounds");
          double[] bounds = {b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3)};
          try (OutputStream out = getContext().getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("The PDF file could not be opened.");
            MapPdf.write(
                out,
                region.optString("name", "Region"),
                bounds,
                (z, x, y) -> {
                  byte[] data = archive().read(regionId, TileAddress.tile("topo-raster", z, x, y));
                  if (data == null) return null;
                  android.graphics.Bitmap bitmap =
                      android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
                  if (bitmap == null) throw new IOException("A Topo tile could not be decoded.");
                  try {
                    if (bitmap.getWidth() != 256 || bitmap.getHeight() != 256)
                      throw new IOException("Invalid Topo tile dimensions.");
                    int[] pixels = new int[256 * 256];
                    bitmap.getPixels(pixels, 0, 256, 0, 0, 256, 256);
                    byte[] rgb = new byte[pixels.length * 3];
                    for (int i = 0; i < pixels.length; i++) {
                      int a = (pixels[i] >>> 24) & 255;
                      for (int channel = 0; channel < 3; channel++) {
                        int color = (pixels[i] >>> (16 - channel * 8)) & 255;
                        rgb[i * 3 + channel] = (byte) ((color * a + 255 * (255 - a) + 127) / 255);
                      }
                    }
                    return rgb;
                  } finally {
                    bitmap.recycle();
                  }
                });
          } catch (Exception error) {
            try {
              getContext().getContentResolver().delete(uri, null, null);
            } catch (Exception ignored) {
            }
            throw error;
          }
          return new JSObject();
        });
  }

  private boolean coarse() {
    return ContextCompat.checkSelfPermission(
            getContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
        == PackageManager.PERMISSION_GRANTED;
  }

  @PluginMethod
  public void startLocation(PluginCall c) {
    if (!coarse()) {
      requestPermissionForAlias("location", c, "locationPermission");
      return;
    }
    startGps(c);
  }

  @PermissionCallback
  private void locationPermission(PluginCall c) {
    if (!coarse()) {
      c.reject("Location permission was denied. Enable it in Android app settings to use GPS.");
      return;
    }
    startGps(c);
  }

  private void startGps(PluginCall c) {
    getActivity()
        .runOnUiThread(
            () -> {
              try {
                watching = true;
                subscribe();
                c.resolve();
              } catch (Exception e) {
                watching = false;
                c.reject(e.getMessage());
              }
            });
  }

  @SuppressWarnings("MissingPermission")
  private void subscribe() {
    if (!coarse()) throw new SecurityException("Location permission is required.");
    if (locations == null)
      locations = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
    locations.removeUpdates(listener);
    boolean
        fine =
            ContextCompat.checkSelfPermission(
                    getContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED,
        added = false;
    if (fine && locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
      locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000, 3, listener);
      added = true;
    }
    if (locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
      locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 5, listener);
      added = true;
    }
    if (!added)
      throw new IllegalStateException("Enable Location in Android settings to get a position.");
  }

  @PluginMethod
  public void stopLocation(PluginCall c) {
    getActivity()
        .runOnUiThread(
            () -> {
              watching = false;
              if (locations != null) locations.removeUpdates(listener);
              c.resolve();
            });
  }

  @Override
  protected void handleOnPause() {
    if (locations != null) locations.removeUpdates(listener);
    super.handleOnPause();
  }

  @Override
  protected void handleOnResume() {
    super.handleOnResume();
    if (watching)
      try {
        subscribe();
      } catch (Exception e) {
        JSObject d = new JSObject();
        d.put("error", e.getMessage());
        notifyListeners("location", d);
      }
  }

  @Override
  protected void handleOnDestroy() {
    watching = false;
    if (locations != null) locations.removeUpdates(listener);
    io.shutdownNow();
    super.handleOnDestroy();
  }
}
