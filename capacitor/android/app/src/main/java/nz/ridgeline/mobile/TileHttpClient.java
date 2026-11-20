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
  private final long cacheLimitBytes;
  private final Validator validator;
  private final TileDispatcher<Resource> dispatcher = new TileDispatcher<>(2);
  private final java.util.concurrent.atomic.AtomicLong warmed = new java.util.concurrent.atomic.AtomicLong(),
      evictionScans = new java.util.concurrent.atomic.AtomicLong();

  static OkHttpClient create(File directory) {
    return create(directory, CACHE_BYTES);
  }

  static OkHttpClient create(File directory, long bytes) {
    if (bytes < 0) throw new IllegalArgumentException("Negative disk cache budget");
    if (bytes == 0 && directory.isDirectory()) {
      // Only on process startup, before a client owns this journal. Saved maps
      // use a different store. No concurrent cache reopen or request teardown.
      try (Cache old = new Cache(directory, 1)) { old.delete(); }
      catch (IOException ignored) { /* Remain cache-free if cleanup fails. */ }
    }
    return new OkHttpClient.Builder()
        .cache(bytes == 0 ? null : new Cache(directory, bytes))
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build();
  }

  TileHttpClient(OkHttpClient client, Validator validator) {
    cacheLimitBytes = client.cache() == null ? 0 : client.cache().maxSize();
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
        new Request.Builder().url(url).header("User-Agent", "Ridgeline-Android/0.16.0").build();
    Call call = (sample ? sampling : client).newCall(request);
    output.onCancel(call::cancel);
    SharedTileBody body = null;
    boolean evictInvalid = false, mayBeCached = false;
    try (Response response = call.execute()) {
      int status = response.code();
      if (status != 200 && !(status == 204 && !tile.raster()))
        throw new TileNetwork.HttpFailure(status, response.header("Retry-After"));
      evictInvalid = true;
      // A body read from the disk cache was committed earlier. A network body
      // only becomes a cache entry once it is fully read (OkHttp aborts the
      // cache write on a mid-body failure), so interrupted transfers leave
      // nothing to evict and must not scan the whole cache journal.
      mayBeCached = response.networkResponse() == null;
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
        mayBeCached = true;
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
      // An oversized body may still be committed while its stream is drained on close.
      if (error instanceof SharedTileBody.TooLarge
          || evictInvalid && mayBeCached && !call.isCanceled()) evict(url);
      if (body != null) body.fail(error);
      throw error;
    } catch (RuntimeException error) {
      evict(url);
      if (body != null) body.fail(new IOException("Map tile validation failed", error));
      throw error;
    }
  }

  /**
   * Read the disk-cache journal and open pooled TLS connections to the given
   * provider origins before the first tile needs them. The HEAD responses are
   * discarded, bypass the dispatcher and are never cached; failures are ignored.
   */
  void warm(String... origins) {
    if (client.cache() != null)
      try {
        client.cache().initialize();
      } catch (IOException ignored) {
        // The first real request retries cache initialisation.
      }
    for (String origin : origins) {
      Request request =
          new Request.Builder()
              .url(origin)
              .head()
              .header("User-Agent", "Ridgeline-Android/0.16.0")
              .cacheControl(CacheControl.FORCE_NETWORK)
              .build();
      sampling
          .newCall(request)
          .enqueue(
              new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {}

                @Override
                public void onResponse(Call call, Response response) {
                  response.close();
                  warmed.incrementAndGet();
                }
              });
    }
  }

  private void evict(String url) {
    evictionScans.incrementAndGet();
    if (client.cache() != null)
      try {
        var urls = client.cache().urls();
        while (urls.hasNext()) if (url.equals(urls.next())) urls.remove();
      } catch (IOException ignored) {
      }
  }

  Map<String, Long> stats() {
    Map<String, Long> values = new java.util.HashMap<>(dispatcher.stats());
    values.put("warmedConnections", warmed.get());
    values.put("httpCacheEvictionScans", evictionScans.get());
    if (client.cache() != null) {
      values.put("httpCacheLimitBytes", cacheLimitBytes);
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
