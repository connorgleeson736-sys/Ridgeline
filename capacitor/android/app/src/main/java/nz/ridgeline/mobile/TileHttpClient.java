package nz.ridgeline.mobile;

import java.io.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** One connection pool and HTTP cache for map workers, estimates and region downloads. */
final class TileHttpClient {
  static final long CACHE_BYTES = 256_000_000L;

  interface Validator {
    void validate(TileAddress tile, byte[] data, String type) throws IOException;
  }

  record Resource(byte[] data, String cacheControl) {}

  private final OkHttpClient client, sampling;
  private final Validator validator;
  private final SingleFlight<Resource> pending = new SingleFlight<>();

  static OkHttpClient create(File directory) {
    return new OkHttpClient.Builder()
        .cache(new Cache(directory, CACHE_BYTES))
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build();
  }

  TileHttpClient(OkHttpClient client, Validator validator) {
    this.client =
        client
            .newBuilder()
            .addNetworkInterceptor(
                chain -> {
                  Response r = chain.proceed(chain.request());
                  // A transient provider failure must not become a persistent map hole.
                  return r.isSuccessful() || r.code() == 304
                      ? r
                      : r.newBuilder().header("Cache-Control", "no-store").build();
                })
            .build();
    sampling =
        this.client
            .newBuilder()
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(3500, TimeUnit.MILLISECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build();
    this.validator = validator;
  }

  Resource fetch(TileAddress tile, String key, boolean sample) throws IOException {
    return load(tile, tile.network(key), sample);
  }

  // URL injection is package-private for deterministic local HTTP/cache tests.
  Resource load(TileAddress tile, String url, boolean sample) throws IOException {
    // Keep short estimation timeouts from shortening a foreground map request.
    return pending.run(url + (sample ? "\nsample" : "\nmap"), () -> request(tile, url, sample));
  }

  private Resource request(TileAddress tile, String url, boolean sample) throws IOException {
    Request request =
        new Request.Builder().url(url).header("User-Agent", "Ridgeline-Android/0.12.0").build();
    boolean evictInvalid = false;
    try (Response response = (sample ? sampling : client).newCall(request).execute()) {
      int status = response.code();
      if (status != 200 && !(status == 204 && !tile.raster()))
        throw new TileNetwork.HttpFailure(status, response.header("Retry-After"));
      evictInvalid = true;
      ResponseBody body = response.body();
      if (body == null) throw new IOException("Map provider returned an empty response.");
      if (body.contentLength() > TileNetwork.MAX)
        throw new IOException("Map tile exceeds the download size limit.");
      byte[] data = TileNetwork.read(body.byteStream(), TileNetwork.MAX);
      validator.validate(tile, data, response.header("Content-Type"));
      return new Resource(data, remainingCacheControl(response, System.currentTimeMillis()));
    } catch (IOException invalid) {
      // Close the body before eviction: draining a malformed/oversized response
      // during close must not commit an invalid success back into the cache.
      if (evictInvalid && client.cache() != null)
        try {
          var urls = client.cache().urls();
          while (urls.hasNext()) if (url.equals(urls.next())) urls.remove();
        } catch (IOException ignored) {
        }
      throw invalid;
    }
  }

  static String remainingCacheControl(Response r, long now) {
    CacheControl policy = r.cacheControl();
    int seconds = policy.maxAgeSeconds();
    if (policy.noCache() || policy.noStore() || seconds < 0) return "no-store";
    long age = 0;
    try {
      age = Math.max(0, Long.parseLong(r.header("Age", "0")));
      age = Math.min(age, Integer.MAX_VALUE) * 1000;
    } catch (NumberFormatException ignored) {
    }
    long received = r.receivedResponseAtMillis(), sent = r.sentRequestAtMillis();
    long apparent = 0;
    try {
      apparent =
          Math.max(
              0,
              received
                  - ZonedDateTime.parse(r.header("Date", ""), DateTimeFormatter.RFC_1123_DATE_TIME)
                      .toInstant()
                      .toEpochMilli());
    } catch (RuntimeException ignored) {
    }
    long currentAge =
        Math.max(apparent, age + Math.max(0, received - sent)) + Math.max(0, now - received);
    long remaining = Math.max(0, seconds - (currentAge + 999) / 1000);
    return remaining == 0 ? "no-store" : "private, max-age=" + remaining;
  }
}
