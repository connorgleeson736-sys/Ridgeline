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
              r.getBoolean("topo"));
      for (TileAddress t : plan.tiles) {
        long size = archive.size(id, t);
        if (size >= 0) {
          done++;
          bytes += size;
        }
      }
      if (isStopped() || !archive.begin(id, generation)) return Result.success();
      archive.total(id, generation, plan.tiles.size());
      archive.progress(id, generation, done, bytes);
      setForegroundAsync(foreground(r.getString("name"), done, plan.tiles.size())).get();
      int failures = 0;
      String lastError = "";
      for (TileAddress t : plan.tiles) {
        if (isStopped() || !archive.active(id, generation)) return Result.success();
        if (archive.size(id, t) >= 0) continue;
        if (getApplicationContext().getFilesDir().getUsableSpace() < 32L * 1024 * 1024)
          throw new IOException("Device storage is nearly full. Free space, then resume.");
        byte[] data = null;
        for (int attempt = 0;
            attempt < 2 && !isStopped() && archive.active(id, generation);
            attempt++)
          try {
            data = archive.read(t);
            if (data == null) data = TileNetwork.fetch(t, r.getString("key"));
            break;
          } catch (IOException e) {
            lastError = e.getMessage();
          }
        if (data == null) {
          if (++failures >= 6) throw new IOException(lastError);
          continue;
        }
        if (!archive.write(id, t, data, generation)) return Result.success();
        done++;
        bytes += data.length;
        if (done % 16 == 0) {
          archive.progress(id, generation, done, bytes);
          setForegroundAsync(foreground(r.getString("name"), done, plan.tiles.size()));
        }
      }
      if (!archive.active(id, generation)) return Result.success();
      archive.progress(id, generation, done, bytes);
      archive.finish(
          id,
          generation,
          done == plan.tiles.size() ? "complete" : "incomplete",
          done == plan.tiles.size() ? "" : lastError + " Resume to retry missing files.");
      return Result.success();
    } catch (Exception e) {
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
    String id = getInputData().getString("id"), generation = getInputData().getString("generation");
    if (id != null && generation != null)
      try {
        TileArchive.get(getApplicationContext())
            .finish(id, generation, "paused", "Download interrupted. Resume when connected.");
      } catch (Exception ignored) {
      }
  }
}
