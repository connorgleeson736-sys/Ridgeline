package nz.ridgeline.mobile;

import android.content.Context;
import android.graphics.BitmapFactory;
import java.io.*;

final class TileNetwork {
  static final class HttpFailure extends IOException {
    final int status;
    final String retryAfter;

    HttpFailure(int status, String retryAfter) {
      super(
          "Map provider returned HTTP "
              + status
              + ". Check your connection or LINZ key, then resume.");
      this.status = status;
      this.retryAfter = retryAfter;
    }
  }

  static final int MAX = 8 * 1024 * 1024;
  private static TileHttpClient instance;
  private static volatile int activeCacheMB = -1;
  private static volatile long activeCacheBytes;

  static int diskCacheMB(Context context) {
    return DiskCacheBudget.normalize(context.getSharedPreferences("mobile", Context.MODE_PRIVATE)
        .getInt("automaticDiskCacheMB", DiskCacheBudget.DEFAULT_MB));
  }

  static void diskCacheMB(Context context, int value) throws IOException {
    if (!context.getSharedPreferences("mobile", Context.MODE_PRIVATE).edit()
        .putInt("automaticDiskCacheMB", DiskCacheBudget.normalize(value)).commit())
      throw new IOException("The disk cache setting could not be saved.");
  }

  static long activeCacheBytes(Context context) {
    return activeCacheMB < 0 ? DiskCacheBudget.total(diskCacheMB(context), context.getCacheDir().getUsableSpace()) : activeCacheBytes;
  }

  static boolean cacheRestartRequired(Context context) {
    return activeCacheMB >= 0 && activeCacheMB != diskCacheMB(context);
  }

  static synchronized TileHttpClient client(Context context) {
    if (instance == null) {
      int requested = diskCacheMB(context);
      long budget = DiskCacheBudget.total(requested, context.getCacheDir().getUsableSpace());
      instance =
          new TileHttpClient(
              TileHttpClient.create(
                  new File(context.getApplicationContext().getCacheDir(), "map-http-v1"),
                  budget),
              TileNetwork::validate);
      activeCacheBytes = budget;
      activeCacheMB = requested;
    }
    return instance;
  }

  static byte[] fetch(Context context, TileAddress tile, String key) throws IOException {
    return client(context).fetch(tile, key, new TileDemand(TileDemand.DOWNLOAD)).data();
  }

  static byte[] fetch(Context context, TileAddress tile, String key, TileDemand demand)
      throws IOException {
    return client(context).fetch(tile, key, demand).data();
  }

  static TileHttpClient.Resource resource(Context context, TileAddress tile, String key)
      throws IOException {
    return client(context).fetch(tile, key, false);
  }

  static byte[] sample(Context context, TileAddress tile, String key) throws IOException {
    return client(context).fetch(tile, key, true).data();
  }

  private static void validate(TileAddress tile, byte[] bytes, String type) throws IOException {
    if (tile.raster()) {
      BitmapFactory.Options o = new BitmapFactory.Options();
      o.inJustDecodeBounds = true;
      BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
      if (o.outWidth != 256 || o.outHeight != 256)
        throw new IOException("Map provider did not return a valid 256-pixel tile.");
    } else if (bytes.length > 0 && type != null) {
      type = type.toLowerCase(java.util.Locale.ROOT);
      if (type.contains("html") || type.contains("json"))
        throw new IOException("Map provider returned an unexpected response.");
    }
  }

  static byte[] read(InputStream in, int max) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[16384];
    int n;
    while ((n = in.read(buffer)) != -1) {
      if (out.size() + n > max) throw new IOException("File exceeds size limit.");
      out.write(buffer, 0, n);
    }
    return out.toByteArray();
  }
}
