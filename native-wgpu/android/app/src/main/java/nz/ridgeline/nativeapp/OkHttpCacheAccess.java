package nz.ridgeline.nativeapp;

import java.io.IOException;
import okhttp3.Cache;
import okhttp3.CacheControl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.cache.CacheRequest;
import okhttp3.internal.cache.CacheStrategy;
import okio.BufferedSink;
import okio.Okio;

/** Pinned OkHttp 5.3.2 JVM cache ABI. Java avoids Kotlin's unsupported invisible
 * member suppression. Check this adapter and cache roundtrips on every upgrade. */
final class OkHttpCacheAccess {
    static boolean cacheable(Response response) {
        return CacheStrategy.Companion.isCacheable(response, response.request());
    }
    static boolean usable(Response response, boolean offline) {
        CacheControl policy = offline ? CacheControl.FORCE_CACHE : new CacheControl.Builder().onlyIfCached().build();
        Request request = response.request().newBuilder().cacheControl(policy).build();
        CacheStrategy strategy = new CacheStrategy.Factory(System.currentTimeMillis(), request, response).compute();
        return strategy.getCacheResponse() != null && strategy.getNetworkRequest() == null;
    }
    static boolean contains(Cache cache, Request request) throws IOException {
        try (Response response = cache.get$okhttp(request)) { return response != null; }
    }
    static boolean write(Cache cache, Response response, byte[] body) throws IOException {
        CacheRequest entry = cache.put$okhttp(response);
        if (entry == null) return false;
        try (BufferedSink sink = Okio.buffer(entry.body())) {
            sink.write(body);
        } catch (IOException | RuntimeException error) {
            entry.abort();
            throw error;
        }
        return true;
    }
}
