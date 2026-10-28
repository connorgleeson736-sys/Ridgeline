package nz.ridgeline.mobile;

import java.io.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** Shared, prioritised native transfers. Headers and body bytes arrive before validated EOF. */
final class TileHttpClient implements AutoCloseable {
  static final long CACHE_BYTES = 256_000_000L;

  interface Validator {
    void validate(TileAddress tile, byte[] data, String type) throws IOException;
  }

  static final class Resource {
    final SharedTileBody body;
    final Response metadata;

    Resource(SharedTileBody body, Response metadata) {
      this.body = body;
      this.metadata = metadata;
    }

    byte[] data() throws IOException {
      return body.bytes();
    }

    byte[] data(TileDispatcher<Resource>.Lease lease) throws IOException {
      lease.onClose(body::wake);
      return body.bytes(lease::closed);
    }

    String cacheControl() {
      return remainingCacheControl(metadata, System.currentTimeMillis());
    }

    long expiresAt() {
      String cc = cacheControl();
      return cc.startsWith("private, max-age=")
          ? System.currentTimeMillis() + Long.parseLong(cc.substring(17)) * 1000
          : 0;
    }

    InputStream stream(TileDispatcher<Resource>.Lease lease) {
      lease.onClose(body::wake);
      return body.stream(lease::closed, lease::close);
    }
  }

  private final OkHttpClient client, sampling;
  private final Validator validator;
  private final TileDispatcher<Resource> dispatcher = new TileDispatcher<>(2);

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

  TileDispatcher<Resource>.Lease open(
      TileAddress tile, String key, boolean sample, TileDemand demand) throws IOException {
    return openURL(tile, tile.network(key), sample, demand);
  }

  TileDispatcher<Resource>.Lease openURL(
      TileAddress tile, String url, boolean sample, TileDemand demand) throws IOException {
    return dispatcher.acquire(
        url + (sample ? "\nsample" : "\nmap"),
        demand,
        publisher -> transfer(tile, url, sample, publisher));
  }

  Resource fetch(TileAddress tile, String key, boolean sample) throws IOException {
    return load(tile, tile.network(key), sample);
  }

  Resource load(TileAddress tile, String url, boolean sample) throws IOException {
    try (var lease =
        openURL(
            tile, url, sample, new TileDemand(sample ? TileDemand.ESTIMATE : TileDemand.VISIBLE))) {
      Resource r = lease.await();
      r.data(lease);
      return r;
    }
  }

  Resource fetch(TileAddress tile, String key, TileDemand demand) throws IOException {
    try (var lease = open(tile, key, false, demand)) {
      Resource r = lease.await();
      r.data(lease);
      return r;
    }
  }

  private void transfer(
      TileAddress tile, String url, boolean sample, TileDispatcher.Publication<Resource> output)
      throws IOException {
    Request request =
        new Request.Builder().url(url).header("User-Agent", "Ridgeline-Android/0.15.0").build();
    Call call = (sample ? sampling : client).newCall(request);
    output.onCancel(call::cancel);
    SharedTileBody body = null;
    boolean evictInvalid = false;
    try (Response response = call.execute()) {
      int status = response.code();
      if (status != 200 && !(status == 204 && !tile.raster()))
        throw new TileNetwork.HttpFailure(status, response.header("Retry-After"));
      evictInvalid = true;
      ResponseBody input = response.body();
      if (input == null) throw new IOException("Map provider returned an empty response.");
      if (input.contentLength() > TileNetwork.MAX) throw new SharedTileBody.TooLarge();
      body = new SharedTileBody(input.contentLength(), TileNetwork.MAX);
      Resource resource = new Resource(body, response.newBuilder().body(null).build());
      output.publish(resource);
      byte[] chunk = new byte[16384];
      int n;
      try (InputStream in = input.byteStream()) {
        while ((n = in.read(chunk)) != -1) {
          if (output.cancelled()) throw TileDispatcher.abort();
          body.append(chunk, n);
        }
      }
      byte[] exact = body.snapshot();
      try {
        validator.validate(tile, exact, response.header("Content-Type"));
      } catch (IOException invalid) {
        evict(url);
        evictInvalid = false;
        throw invalid;
      }
      evictInvalid = false;
      if (output.cancelled()) throw TileDispatcher.abort();
      output.complete();
      body.finish(exact);
    } catch (IOException error) {
      if (evictInvalid && (!call.isCanceled() || error instanceof SharedTileBody.TooLarge))
        evict(url);
      if (body != null) body.fail(error);
      throw error;
    } catch (RuntimeException error) {
      evict(url);
      if (body != null) body.fail(new IOException("Map tile validation failed", error));
      throw error;
    }
  }

  private void evict(String url) {
    if (client.cache() != null)
      try {
        var urls = client.cache().urls();
        while (urls.hasNext()) if (url.equals(urls.next())) urls.remove();
      } catch (IOException ignored) {
      }
  }

  Map<String, Long> stats() {
    Map<String, Long> values = new java.util.HashMap<>(dispatcher.stats());
    if (client.cache() != null) {
      values.put("httpCacheHits", (long) client.cache().hitCount());
      values.put("httpCacheRequests", (long) client.cache().requestCount());
      values.put("httpNetworkRequests", (long) client.cache().networkCount());
    }
    return values;
  }

  @Override
  public void close() {
    dispatcher.close();
  }

  static String remainingCacheControl(Response r, long now) {
    CacheControl policy = r.cacheControl();
    int seconds = policy.maxAgeSeconds();
    if (policy.noCache() || policy.noStore() || seconds < 0) return "no-store";
    long age = 0;
    try {
      age = Math.min(Math.max(0, Long.parseLong(r.header("Age", "0"))), Integer.MAX_VALUE) * 1000;
    } catch (NumberFormatException ignored) {
    }
    long received = r.receivedResponseAtMillis(), sent = r.sentRequestAtMillis(), apparent = 0;
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
