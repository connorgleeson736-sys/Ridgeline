package nz.ridgeline.mobile;

import android.content.Context;
import android.net.*;
import android.webkit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

final class TileGateway {
  private final TileArchive archive;
  private final ConnectivityManager connectivity;
  private final Context context;

  TileGateway(Context context) {
    this.context = context.getApplicationContext();
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
    if (uri.getPath().startsWith("/_ridge/overviews/")) {
      if (!"https".equals(uri.getScheme()) || !"GET".equals(request.getMethod()))
        return response(403, "text/plain", new byte[0]);
      try {
        byte[] bytes = OverviewStore.get(context).read(uri.getPath());
        return response(
            bytes == null ? 404 : 200, "image/webp", bytes == null ? new byte[0] : bytes);
      } catch (Exception e) {
        return response(503, "text/plain", new byte[0]);
      }
    }
    TileAddress tile = TileAddress.local(uri.toString());
    if (tile == null || !request.getMethod().equals("GET"))
      return response(
          403, "text/plain", "Unsupported map resource".getBytes(StandardCharsets.UTF_8));
    try {
      byte[] data = archive.read(tile);
      if (data != null) return response(200, tile.mime(), data);
      int failure = 404;
      String retryAfter = null;
      if (!archive.offlineOnly() && connected())
        try {
          TileHttpClient.Resource result =
              TileNetwork.resource(context, tile, TileAddress.queryKey(uri.toString()));
          // Preserve upstream freshness instead of granting every response a new hour.
          return response(200, tile.mime(), result.data(), result.cacheControl(), null);
        } catch (IOException e) {
          failure =
              e instanceof TileNetwork.HttpFailure ? ((TileNetwork.HttpFailure) e).status : 503;
          if (e instanceof TileNetwork.HttpFailure)
            retryAfter = ((TileNetwork.HttpFailure) e).retryAfter;
        }
      data = TileResampler.parent(archive, tile);
      if (data != null) return response(200, "image/png", data, "no-store", null);
      return response(
          failure,
          "text/plain",
          "Outside downloaded coverage or map provider unavailable"
              .getBytes(StandardCharsets.UTF_8),
          "no-store",
          retryAfter);
    } catch (Exception e) {
      return response(
          503,
          "text/plain",
          "Map data is temporarily unavailable".getBytes(StandardCharsets.UTF_8));
    }
  }

  private WebResourceResponse response(int status, String mime, byte[] data) {
    return response(status, mime, data, status == 200 ? "private, max-age=3600" : "no-store", null);
  }

  private WebResourceResponse response(
      int status, String mime, byte[] data, String cacheControl, String retryAfter) {
    Map<String, String> headers = new HashMap<>();
    headers.put("Access-Control-Allow-Origin", "https://localhost");
    headers.put("Cache-Control", cacheControl);
    headers.put("X-Content-Type-Options", "nosniff");
    if (retryAfter != null) headers.put("Retry-After", retryAfter);
    return new WebResourceResponse(
        mime,
        null,
        status,
        status == 200 ? "OK" : status == 404 ? "Not Found" : "Unavailable",
        headers,
        new ByteArrayInputStream(data));
  }
}
