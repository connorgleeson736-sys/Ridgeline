# Loading and caching review — Android 0.12.0

Reviewed 28 October 2026. Priority: faster panning/zooming into new areas, with an owner-approved removable online cache of at most 256 MB. Original + Direct, source zooms, Topo density, imagery pixels and terrain settings are preserved.

## Research and initial options

| Idea | Expected benefit | Decision |
| --- | --- | --- |
| Shared native HTTP client, connection pool and standard HTTP cache | Connection reuse across workers/downloads; HTTP/2 where negotiated; fewer repeat transfers | Implemented with OkHttp 4.12.0 |
| Merge simultaneous requests for the same resource | Avoid duplicate transfers | Implemented for map/download requests; estimation keeps a separate shorter timeout group |
| Remember saved-tile locations and misses | Avoid repeatedly probing up to 30 region directories | Implemented, bounded to 4,096 paths/misses |
| Release locks before reading tile bodies | Allow independent offline reads together | Implemented; overview-pack reads also no longer serialize |
| Separate short control operations from large jobs | Pause/settings/status need not queue behind exports/imports/estimates | Implemented; large jobs remain serial to bound memory |
| Distinguish coarse fallback cache policy | Prevent temporary enlarged tiles receiving an hour of cache life | Implemented; parent fallback is `no-store` |
| Cancel obsolete native requests during rapid panning | Free bandwidth for the new viewport | Deferred: requires request IDs and subscriber-aware cancellation across workers |
| Native prioritisation of visible tiles over background work | Improve cold loading under bandwidth contention | Existing JS scheduling retained; a native priority bridge needs request metadata and device measurements |
| Concurrent region downloads | Shorter downloads | Deferred: global limits, foreground priority, backoff and pause/generation checks need to be designed together |
| Persistent ownership index / shared overlap storage | Faster first lookups; less duplicate disk storage | Deferred: requires archive migration and recovery/deletion logic |
| More RAM, lower detail or lossy recompression | Could reduce work but compromise memory or image quality | Not used |

Android's source documentation states that `shouldInterceptRequest` runs off the UI thread. Moving it to another executor alone does not fix its existing waits. Sharing work and reducing lock contention address the actual unnecessary work.

OkHttp documents reusable connection pools, HTTP/2 multiplexing, fresh cache hits, conditional revalidation and fully consuming/closing response bodies. A single cache instance owns the directory. `HttpURLConnection` can already reuse connections: the previous path did not necessarily establish a fresh TCP connection for every tile. The changes add explicit shared transport, HTTP caching and coalescing.

Sources read:

- [Android WebViewClient source/threading contract](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/webkit/WebViewClient.java)
- [OkHttp caching and cache ownership](https://github.com/square/okhttp/blob/master/docs/features/caching.md)
- [OkHttp HTTP/2 and connection-pool concurrency](https://github.com/square/okhttp/blob/master/docs/contribute/concurrency.md)

The Android developer documentation host was unavailable from this environment; Android's source documentation above was accessible.

## Implementation and safeguards

- Native cache: private Android `cacheDir/map-http-v1`, separate from saved regions. Limit **256,000,000 bytes** of cache-accounted entries (approximately 244 MiB); journal/filesystem overhead can add a small amount. This does not allocate 256 MB of RAM. Android can reclaim it without removing downloaded regions or bundled packs.
- OkHttp handles provider Cache-Control, ETag/Last-Modified, Vary, expiry and credential/URL separation. Errors, malformed successes and oversized responses cannot remain reusable successful tiles. Raster validation still checks 256 × 256 bounds without decoding full pixels in the transport layer.
- The gateway forwards the *remaining* explicit freshness lifetime. A native cache hit does not grant another full upstream lifetime. Without explicit max-age, the JS cache conservatively receives no-store; native caching can still use standard HTTP expiry/revalidation rules.
- There is no stale-on-error override. Existing ancestor interpolation is unchanged. Ancestor responses now receive no-store; an already displayed fallback does not automatically refresh merely because of this header change.
- Single-flight retains only outstanding work. Completed/failed entries are removed. Consumers read shared bytes without modifying them. Short sampling timeouts cannot shorten map/download requests. Foreground connect/read timeouts stay 12/15 seconds. Retry-After reaches the existing JS retry policy.
- Saved-tile writes invalidate the affected memo entry after atomic commit. Region removal clears the memo, allowing discovery of overlapping regions. Descriptors open under the region lock; tile bodies read outside it. Existing write generation, pause and deletion protections remain. Metadata/writes retain their original locking.
- Overview catalogues are immutable after construction; each read owns its descriptor/channel. Removing their global read lock preserves bytes, offsets, bounds checks and pack verification.
- Downloaded-maps-only mode bypasses the native HTTP cache. Cached online tiles are not represented as saved offline regions. Estimation rechecks that setting before each sample if it changes during estimation.
- Heavy exports/imports/estimates use a separate serial queue. Control work no longer queues behind them. This does not remove every possible filesystem/metadata wait.

## Verification and limits

Native tests use real local HTTP with MockWebServer: coalescing, exact bytes, expiry/replacement, conditional 304 responses, restart persistence, eviction, errors/retry headers, malformed responses, oversized chunked bodies and empty vector tiles. Separate tests cover interruptions/retries, bounded lookup eviction, write invalidation, overlapping regions and independent control work.

Deterministic results:

- Eight overlapping requests for one uncacheable tile: **one HTTP transfer**.
- One completed estimation sample followed by two normal requests: **one HTTP transfer**, identical bytes.
- An expired ETag tile revalidates with its original body; the following fresh read needs no additional network request.
- 100 repeated hits and 100 repeated misses: **two directory searches**. This measures lookup searches, not phone filesystem time.

Packaging tests compare renderer modules and the shared resolution policy against website source, retaining the existing Topo enhancement (zoom-18 source tiles and 128 px source sizing on dense screens). Browser checks use the real WebGL engine with controlled tiles and a mocked native bridge. JVM HTTP tests, rather than that mock, validate native caching.

No emulator or physical-phone speed benchmark is claimed. Cold loading still depends on mobile connectivity, provider latency, tile count, decoding and GPU work. HTTP/2 requires provider negotiation. Cache benefits depend on provider freshness rules and available storage. See the APK validation record for final test counts and build hashes.
