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
            call.resolve(op.run());
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
          JSObject r = new JSObject();
          r.put("total", p.tiles.size());
          r.put("lowMiB", Math.max(1, p.tiles.size() * 12 / 1024));
          r.put("highMiB", Math.max(2, p.tiles.size() * 100 / 1024));
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
                  .put("zoom", c.getInt("zoom", 15))
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
