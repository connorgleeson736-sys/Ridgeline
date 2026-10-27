package nz.ridgeline.mobile;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import okio.Buffer;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** Real local HTTP: transport/cache correctness without Android or provider credentials. */
public class TileHttpClientTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  interface Handler {
    MockResponse respond(RecordedRequest e) throws Exception;
  }

  final AtomicInteger requests = new AtomicInteger();
  final TileAddress tile = TileAddress.tile("vector", 14, 12000, 9000);
  final byte[] bytes = {0, 1, 127, -128, -1, 10, 99};
  MockWebServer server;
  Cache cache;
  TileHttpClient http;
  volatile Handler handler;

  @Before
  public void setup() throws Exception {
    server = new MockWebServer();
    handler = e -> reply(200, "public, max-age=60", bytes);
    server.setDispatcher(
        new okhttp3.mockwebserver.Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
            requests.incrementAndGet();
            try {
              return handler.respond(request);
            } catch (InterruptedException e) {
              throw e;
            } catch (Exception error) {
              return new MockResponse().setResponseCode(500);
            }
          }
        });
    server.start();
    cache = new Cache(temp.newFolder("http"), 1024 * 1024);
    http = client(cache);
  }

  TileHttpClient client(Cache storage) {
    return new TileHttpClient(
        new OkHttpClient.Builder().cache(storage).build(),
        (t, data, type) -> {
          if (type != null && type.contains("html")) throw new IOException("Unexpected HTML");
        });
  }

  @After
  public void cleanup() throws Exception {
    http.close();
    server.shutdown();
    cache.close();
  }

  String url(String path) {
    return server.url(path).toString();
  }

  TileHttpClient.Resource load(String path) throws IOException {
    return http.load(tile, url(path), false);
  }

  static MockResponse reply(int status, String cc, byte[] data) {
    return new MockResponse()
        .setResponseCode(status)
        .setHeader("Cache-Control", cc)
        .setHeader("Content-Type", "application/x-protobuf")
        .setBody(new Buffer().write(data));
  }

  @Test
  public void repeatAndEstimateSamplesReuseExactBytes() throws Exception {
    assertArrayEquals(bytes, http.load(tile, url("/tile"), true).data());
    assertArrayEquals(bytes, load("/tile").data());
    assertArrayEquals(bytes, load("/tile").data());
    assertEquals(1, requests.get());
    assertEquals(2, cache.hitCount());
    assertTrue(load("/tile").cacheControl().startsWith("private, max-age="));
  }

  @Test
  public void cacheSurvivesClientRestart() throws Exception {
    assertArrayEquals(bytes, load("/tile").data());
    File dir = cache.directory();
    http.close();
    cache.close();
    cache = new Cache(dir, 1024 * 1024);
    http = client(cache);
    assertArrayEquals(bytes, load("/tile").data());
    assertEquals(1, requests.get());
  }

  @Test
  public void providerNoStoreAndNoCacheAreRespected() throws Exception {
    handler = e -> reply(200, "no-store", bytes);
    assertEquals("no-store", load("/no-store").cacheControl());
    load("/no-store");
    handler = e -> reply(200, "no-cache, max-age=60", bytes);
    assertEquals("no-store", load("/no-cache").cacheControl());
    load("/no-cache");
    assertEquals(4, requests.get());
  }

  @Test
  public void staleTilesRevalidateWithoutRedownloadingBody() throws Exception {
    AtomicReference<String> condition = new AtomicReference<>();
    handler =
        e -> {
          String tag = e.getHeader("If-None-Match");
          condition.set(tag);
          return reply(
                  tag == null ? 200 : 304,
                  tag == null ? "max-age=0" : "max-age=60",
                  tag == null ? bytes : new byte[0])
              .setHeader("ETag", "\"v1\"");
        };
    assertArrayEquals(bytes, load("/tile").data());
    assertArrayEquals(bytes, load("/tile").data());
    assertEquals("\"v1\"", condition.get());
    assertArrayEquals(bytes, load("/tile").data());
    assertEquals(2, requests.get());
  }

  @Test
  public void newerBytesReplaceExpiredTile() throws Exception {
    byte[] newer = {9, 8, 7};
    handler = e -> reply(200, "max-age=0", requests.get() == 1 ? bytes : newer);
    assertArrayEquals(bytes, load("/tile").data());
    assertArrayEquals(newer, load("/tile").data());
    assertEquals(2, requests.get());
  }

  @Test
  public void sourceAndCredentialUrlsNeverCollide() throws Exception {
    handler = e -> reply(200, "max-age=60", e.getPath().getBytes(StandardCharsets.UTF_8));
    for (String path : List.of("/topo?api=a", "/topo?api=b", "/aerial?api=a")) {
      assertArrayEquals(path.getBytes(StandardCharsets.UTF_8), load(path).data());
      assertArrayEquals(path.getBytes(StandardCharsets.UTF_8), load(path).data());
    }
    assertEquals(3, requests.get());
  }

  @Test
  public void errorsNotCachedAndRetryAfterPreserved() throws Exception {
    handler = e -> reply(503, "max-age=600", bytes).setHeader("Retry-After", "2");
    for (int i = 0; i < 2; i++) {
      TileNetwork.HttpFailure f = assertThrows(TileNetwork.HttpFailure.class, () -> load("/tile"));
      assertEquals(503, f.status);
      assertEquals("2", f.retryAfter);
    }
    assertEquals(2, requests.get());
  }

  @Test
  public void malformedSuccessRejectedAndEvicted() throws Exception {
    handler = e -> reply(200, "max-age=600", bytes).setHeader("Content-Type", "text/html");
    assertThrows(IOException.class, () -> load("/tile"));
    handler = e -> reply(200, "max-age=60", bytes);
    assertArrayEquals(bytes, load("/tile").data());
    assertEquals(2, requests.get());
  }

  @Test
  public void oversizedChunkedSuccessCannotRemainCached() throws Exception {
    handler =
        e ->
            new MockResponse()
                .setHeader("Cache-Control", "max-age=600")
                .setChunkedBody(new Buffer().write(new byte[TileNetwork.MAX + 1]), 16384);
    assertThrows(IOException.class, () -> load("/tile"));
    handler = e -> reply(200, "max-age=60", bytes);
    assertArrayEquals(bytes, load("/tile").data());
    assertEquals(2, requests.get());
  }

  @Test
  public void emptyVectorTilesValid() throws Exception {
    handler = e -> reply(204, "max-age=60", new byte[0]);
    assertEquals(0, load("/tile").data().length);
  }

  @Test
  public void concurrentUncacheableRequestsUseOneTransfer() throws Exception {
    CountDownLatch entered = new CountDownLatch(1),
        release = new CountDownLatch(1),
        ready = new CountDownLatch(8),
        start = new CountDownLatch(1);
    ExecutorService callers = Executors.newFixedThreadPool(8);
    handler =
        e -> {
          entered.countDown();
          release.await(5, TimeUnit.SECONDS);
          return reply(200, "no-store", bytes);
        };
    List<Future<TileHttpClient.Resource>> results = new ArrayList<>();
    try {
      for (int i = 0; i < 8; i++)
        results.add(
            callers.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return load("/tile");
                }));
      assertTrue(ready.await(3, TimeUnit.SECONDS));
      start.countDown();
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      for (Future<?> f : results)
        assertThrows(TimeoutException.class, () -> f.get(20, TimeUnit.MILLISECONDS));
      assertEquals(1, requests.get());
      release.countDown();
      for (Future<TileHttpClient.Resource> f : results)
        assertArrayEquals(bytes, f.get(3, TimeUnit.SECONDS).data());
      assertEquals(1, requests.get());
    } finally {
      release.countDown();
      callers.shutdownNow();
    }
  }

  @Test
  public void boundedDiskCacheEvictsAndReloadsOriginalBytes() throws Exception {
    byte[] payload = new byte[700000];
    new Random(17).nextBytes(payload);
    handler = e -> reply(200, "max-age=60", payload);
    assertArrayEquals(payload, load("/first").data());
    assertArrayEquals(payload, load("/second").data());
    cache.flush();
    assertTrue(cache.size() <= cache.maxSize());
    assertArrayEquals(payload, load("/first").data());
    assertEquals(3, requests.get());
  }

  @Test
  public void browserFreshnessDoesNotRestartAfterNativeCacheHit() {
    Response r =
        new Response.Builder()
            .request(new Request.Builder().url(url("/tile")).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Cache-Control", "max-age=60")
            .header("Age", "5")
            .header("Date", "Thu, 01 Jan 1970 00:00:00 GMT")
            .sentRequestAtMillis(10000)
            .receivedResponseAtMillis(11000)
            .build();
    assertEquals("private, max-age=39", TileHttpClient.remainingCacheControl(r, 21000));
    assertEquals("no-store", TileHttpClient.remainingCacheControl(r, 100000));
    assertEquals(
        "no-store",
        TileHttpClient.remainingCacheControl(
            r.newBuilder().header("Cache-Control", "no-store").build(), 21000));
    assertEquals(
        "no-store",
        TileHttpClient.remainingCacheControl(
            r.newBuilder().removeHeader("Cache-Control").build(), 21000));
  }

  @Test
  public void headersAndPartialBytesArriveBeforeCompleteValidatedBody() throws Exception {
    handler =
        e -> reply(200, "max-age=60", new byte[64]).throttleBody(16, 100, TimeUnit.MILLISECONDS);
    TileDemand demand = new TileDemand(0);
    try (var lease = http.openURL(tile, url("/stream"), false, demand)) {
      var resource = lease.await();
      InputStream stream = resource.stream(lease);
      assertEquals(0, stream.read());
      ExecutorService reader = Executors.newSingleThreadExecutor();
      try {
        Future<byte[]> complete = reader.submit(() -> resource.data(lease));
        assertThrows(TimeoutException.class, () -> complete.get(30, TimeUnit.MILLISECONDS));
        demand.cancel();
        assertThrows(ExecutionException.class, () -> complete.get(1, TimeUnit.SECONDS));
      } finally {
        reader.shutdownNow();
        stream.close();
      }
    }
  }

  @Test
  public void abortingTheMapDoesNotCancelARegionSharingThatTransfer() throws Exception {
    handler = e -> reply(200, "max-age=60", bytes).throttleBody(2, 60, TimeUnit.MILLISECONDS);
    TileDemand map = new TileDemand(0), region = new TileDemand(300);
    try (var a = http.openURL(tile, url("/shared"), false, map);
        var b = http.openURL(tile, url("/shared"), false, region)) {
      var resource = b.await();
      map.cancel();
      assertThrows(InterruptedIOException.class, a::await);
      assertArrayEquals(bytes, resource.data(b));
      assertEquals(1, requests.get());
    }
  }

  @Test
  public void invalidImageCannotEndAStreamOrBecomeACachedSuccess() throws Exception {
    handler = e -> reply(200, "max-age=600", bytes).setHeader("Content-Type", "text/html");
    try (var lease = http.openURL(tile, url("/invalid"), false, new TileDemand(0))) {
      var resource = lease.await();
      try (InputStream in = resource.stream(lease)) {
        assertThrows(IOException.class, in::readAllBytes);
      }
    }
    handler = e -> reply(200, "max-age=60", bytes);
    assertArrayEquals(bytes, load("/invalid").data());
    assertEquals(2, requests.get());
  }
}
