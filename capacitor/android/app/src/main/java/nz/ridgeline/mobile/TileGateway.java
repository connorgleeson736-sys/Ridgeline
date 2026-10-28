package nz.ridgeline.mobile;

import android.content.Context;
import android.net.*;
import android.webkit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

final class TileGateway {
  private final TileRequestRegistry requests;
  private final TileConnectivity connectivity;
  private final Context context;

  TileGateway(Context context) {
    this(context, new TileRequestRegistry());
  }

  TileGateway(Context context, TileRequestRegistry requests) {
    this.context = context.getApplicationContext();
    this.requests = requests;
    connectivity = TileConnectivity.get(context);
  }

  private boolean connected() {
    return connectivity.connected();
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
        OverviewStore.Image image=OverviewStore.get(context).open(uri.getPath());
        if(image==null)return response(404,"text/plain",new byte[0]);
        Map<String,String> headers=headers("private, max-age=3600",null);headers.put("Content-Length",Integer.toString(image.length()));
        return new WebResourceResponse("image/webp",null,200,"OK",headers,image.data());
      } catch (Exception e) {
        return response(503, "text/plain", new byte[0]);
      }
    }
    TileAddress tile = TileAddress.local(uri.toString());
    if (tile == null || !request.getMethod().equals("GET"))
      return response(
          403, "text/plain", "Unsupported map resource".getBytes(StandardCharsets.UTF_8));
    TileDemand demand =
        requests.get(
            header(request, "X-Ridge-Request"), priority(header(request, "X-Ridge-Priority")));
    try {
      if (demand.cancelled()) return response(499, "text/plain", new byte[0]);
      TileArchive archive = TileArchive.get(context);
      byte[] data = archive.read(tile);
      if (data != null) return response(200, tile.mime(), data);
      int failure = 404;
      String retryAfter = null;
      if (!"1".equals(header(request, "X-Ridge-Only-Saved"))
          && !archive.offlineOnly()
          && connected())
        try {
          var lease =
              TileNetwork.client(context)
                  .open(tile, TileAddress.queryKey(uri.toString()), false, demand);
          try {
            TileHttpClient.Resource result = lease.await();
            Map<String, String> headers = headers(result.cacheControl(), null);
            headers.put("X-Ridge-Expires-At", Long.toString(result.expiresAt()));
            return new WebResourceResponse(
                tile.mime(), null, 200, "OK", headers, result.stream(lease));
          } catch (Exception error) {
            lease.close();
            throw error;
          }
        } catch (IOException e) {
          failure =
              e instanceof TileNetwork.HttpFailure ? ((TileNetwork.HttpFailure) e).status : 503;
          if (e instanceof TileNetwork.HttpFailure)
            retryAfter = ((TileNetwork.HttpFailure) e).retryAfter;
        }
      if (demand.cancelled()) return response(499, "text/plain", new byte[0]);
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
    Map<String, String> headers = headers(cacheControl, retryAfter);
    return new WebResourceResponse(
        mime,
        null,
        status,
        status == 200 ? "OK" : status == 404 ? "Not Found" : "Unavailable",
        headers,
        new ByteArrayInputStream(data));
  }

  private Map<String, String> headers(String cacheControl, String retryAfter) {
    Map<String, String> headers = new HashMap<>();
    headers.put("Access-Control-Allow-Origin", "https://localhost");
    headers.put("Cache-Control", cacheControl);
    headers.put("X-Content-Type-Options", "nosniff");
    if (retryAfter != null) headers.put("Retry-After", retryAfter);
    return headers;
  }

  private static String header(WebResourceRequest r, String name) {
    for (var e : r.getRequestHeaders().entrySet())
      if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
    return null;
  }

  private static int priority(String value) {
    try {
      return Integer.parseInt(value);
    } catch (Exception e) {
      return 0;
    }
  }
}
