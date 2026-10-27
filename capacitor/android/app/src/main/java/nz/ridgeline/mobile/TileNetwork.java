package nz.ridgeline.mobile;

import android.graphics.BitmapFactory;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

final class TileNetwork {
  static final class HttpFailure extends IOException {
    final int status;

    HttpFailure(int status) {
      super(
          "Map provider returned HTTP "
              + status
              + ". Check your connection or LINZ key, then resume.");
      this.status = status;
    }
  }

  static final int MAX = 8 * 1024 * 1024;

  static byte[] fetch(TileAddress tile, String key) throws IOException {
    return fetch(tile, key, 12000, 15000);
  }

  static byte[] sample(TileAddress tile, String key) throws IOException {
    return fetch(tile, key, 2500, 3500);
  }

  private static byte[] fetch(TileAddress tile, String key, int connectTimeout, int readTimeout)
      throws IOException {
    HttpURLConnection connection = (HttpURLConnection) new URL(tile.network(key)).openConnection();
    connection.setConnectTimeout(connectTimeout);
    connection.setReadTimeout(readTimeout);
    connection.setInstanceFollowRedirects(false);
    connection.setRequestProperty("User-Agent", "Ridgeline-Android/0.2.0");
    try {
      int status = connection.getResponseCode();
      if (status != 200 && !(status == 204 && !tile.raster())) throw new HttpFailure(status);
      int length = connection.getContentLength();
      if (length > MAX) throw new IOException("Map tile exceeds the download size limit.");
      byte[] bytes;
      try (InputStream in = connection.getInputStream()) {
        bytes = read(in, MAX);
      }
      if (tile.raster()) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (o.outWidth != 256 || o.outHeight != 256)
          throw new IOException("Map provider did not return a valid 256-pixel tile.");
      } else if (bytes.length > 0) {
        String type = connection.getContentType();
        if (type != null && (type.contains("html") || type.contains("json")))
          throw new IOException("Map provider returned an unexpected response.");
      }
      return bytes;
    } finally {
      connection.disconnect();
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
