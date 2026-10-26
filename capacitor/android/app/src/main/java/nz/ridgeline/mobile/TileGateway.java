package nz.ridgeline.mobile;

import android.content.Context;
import android.net.*;
import android.webkit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class TileGateway {
  private final TileArchive archive;
  private final ConnectivityManager connectivity;

  TileGateway(Context context) {
    archive = TileArchive.get(context);
    connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
  }

  private boolean connected() {
    NetworkCapabilities caps = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
    return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
  }

  public WebResourceResponse intercept(WebResourceRequest request) {
    Uri uri = request.getUrl();
    if (!"localhost".equals(uri.getHost())
        || uri.getPath() == null
        || !uri.getPath().startsWith("/_ridge/")) return null;
    TileAddress tile = TileAddress.local(uri.toString());
    if (tile == null || !request.getMethod().equals("GET"))
      return response(
          403, "text/plain", "Unsupported map resource".getBytes(StandardCharsets.UTF_8));
    try {
      byte[] data = archive.read(tile);
      if (data != null) return response(200, tile.mime(), data);
      int failure = 404;
      if (!archive.offlineOnly() && connected())
        try {
          data = TileNetwork.fetch(tile, TileAddress.queryKey(uri.toString()));
          return response(200, tile.mime(), data);
        } catch (IOException e) {
          failure =
              e instanceof TileNetwork.HttpFailure ? ((TileNetwork.HttpFailure) e).status : 503;
        }
      data = TileResampler.parent(archive, tile);
      if (data != null) return response(200, "image/png", data);
      return response(
          failure,
          "text/plain",
          "Outside downloaded coverage or map provider unavailable"
              .getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      return response(
          503,
          "text/plain",
          "Map data is temporarily unavailable".getBytes(StandardCharsets.UTF_8));
    }
  }

  private WebResourceResponse response(int status, String mime, byte[] data) {
    return new WebResourceResponse(
        mime,
        null,
        status,
        status == 200 ? "OK" : status == 404 ? "Not Found" : "Unavailable",
        Map.of(
            "Access-Control-Allow-Origin",
            "https://localhost",
            "Cache-Control",
            status == 200 ? "private, max-age=3600" : "no-store",
            "X-Content-Type-Options",
            "nosniff"),
        new ByteArrayInputStream(data));
  }
}
