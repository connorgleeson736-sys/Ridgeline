package nz.ridgeline.mobile;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.*;
import java.io.IOException;
import org.json.*;

public final class RegionDownloadWorker extends Worker {
  private volatile TileDemand demand;
  private static final String CHANNEL = "offline-regions";

  public RegionDownloadWorker(@NonNull Context c, @NonNull WorkerParameters p) {
    super(c, p);
  }

  static void enqueue(Context c, String id) throws Exception {
    String generation = TileArchive.get(c).queue(id);
    Constraints constraints =
        new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build();
    WorkManager.getInstance(c)
        .enqueueUniqueWork(
            "region-" + id,
            ExistingWorkPolicy.REPLACE,
            new OneTimeWorkRequest.Builder(RegionDownloadWorker.class)
                .setInputData(
                    new Data.Builder()
                        .putString("id", id)
                        .putString("generation", generation)
                        .build())
                .setConstraints(constraints)
                .addTag("ridgeline-regions")
                .build());
  }

  private ForegroundInfo foreground(String name, int done, int total) {
    NotificationManager manager =
        getApplicationContext().getSystemService(NotificationManager.class);
    manager.createNotificationChannel(
        new NotificationChannel(
            CHANNEL, "Offline map downloads", NotificationManager.IMPORTANCE_LOW));
    PendingIntent pending =
        PendingIntent.getActivity(
            getApplicationContext(),
            0,
            new Intent(getApplicationContext(), MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification notification =
        new NotificationCompat.Builder(getApplicationContext(), CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading " + name)
            .setContentText(done + " / " + total + " map files")
            .setProgress(total, done, false)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
    int id = 1000 + Math.floorMod(getId().hashCode(), 100000);
    return Build.VERSION.SDK_INT >= 29
        ? new ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        : new ForegroundInfo(id, notification);
  }

  @NonNull
  @Override
  public Result doWork() {
    String id = getInputData().getString("id"), generation = getInputData().getString("generation");
    if (id == null || generation == null) return Result.failure();
    TileArchive archive = TileArchive.get(getApplicationContext());
    int done = 0;
    long bytes = 0;
    final long[] committed = {0, 0};
    try {
      if (!archive.exists(id)) return Result.success();
      JSONObject r = archive.region(id);
      String state = r.getString("state");
      if (state.equals("paused") || state.equals("complete")) return Result.success();
      JSONArray b = r.getJSONArray("bounds");
      TilePlan plan =
          new TilePlan(
              new double[] {b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3)},
              r.getInt("zoom"),
              r.getString("imagery"),
              r.getBoolean("topo"),
              r.optInt("topoZoom", 16));
      if (isStopped() || !archive.begin(id, generation)) return Result.success();
      archive.total(id, generation, plan.tiles.size());
      setForegroundAsync(foreground(r.getString("name"), 0, plan.tiles.size())).get();
      demand = new TileDemand(TileDemand.DOWNLOAD);
      String key = r.getString("key");
      RegionTransfer.Result result =
          RegionTransfer.run(
              plan.tiles,
              archive.sizes(id),
              t -> {
                if (getApplicationContext().getFilesDir().getUsableSpace() < 32L * 1024 * 1024)
                  throw new IOException("Device storage is nearly full. Free space, then resume.");
                byte[] data = archive.read(t);
                return data == null
                    ? TileNetwork.fetch(getApplicationContext(), t, key, demand)
                    : data;
              },
              batch -> archive.writeBatch(id, batch, generation),
              (count, size) -> {
                committed[0] = count;
                committed[1] = size;
                archive.progress(id, generation, count, size);
                setForegroundAsync(foreground(r.getString("name"), count, plan.tiles.size()));
              },
              () -> !isStopped() && archive.active(id, generation),
              demand);
      done = result.done();
      bytes = result.bytes();
      String lastError = result.error();
      if (!archive.active(id, generation)) return Result.success();
      archive.progress(id, generation, done, bytes);
      archive.finish(
          id,
          generation,
          done == plan.tiles.size() ? "complete" : "incomplete",
          done == plan.tiles.size() ? "" : lastError + " Resume to retry missing files.");
      return Result.success();
    } catch (Exception e) {
      done = (int) committed[0];
      bytes = committed[1];
      try {
        if (archive.active(id, generation)) {
          archive.progress(id, generation, done, bytes);
          archive.finish(
              id,
              generation,
              "incomplete",
              e.getMessage() == null ? "Download interrupted. Resume to retry." : e.getMessage());
        }
      } catch (Exception ignored) {
      }
      return Result.failure();
    }
  }

  @Override
  public void onStopped() {
    super.onStopped();
    if (demand != null) demand.cancel();
    String id = getInputData().getString("id"), generation = getInputData().getString("generation");
    if (id != null && generation != null)
      try {
        TileArchive.get(getApplicationContext())
            .finish(id, generation, "paused", "Download interrupted. Resume when connected.");
      } catch (Exception ignored) {
      }
  }
}
