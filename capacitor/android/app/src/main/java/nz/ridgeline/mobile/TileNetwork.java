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

  private static synchronized TileHttpClient client(Context context) {
    if (instance == null)
      instance =
          new TileHttpClient(
              TileHttpClient.create(
                  new File(context.getApplicationContext().getCacheDir(), "map-http-v1")),
              TileNetwork::validate);
    return instance;
  }

  static byte[] fetch(Context context, TileAddress tile, String key) throws IOException {
    return resource(context, tile, key).data();
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
