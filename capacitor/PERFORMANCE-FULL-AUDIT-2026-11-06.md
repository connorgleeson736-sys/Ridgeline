# Capacitor full latency and performance audit — 6 November 2026

Scope: the Capacitor app at the tip of `capacitor-latency-audit` (0.20.3 Audit
source plus the 5 November research), covering startup, the JS → native
request path, the native HTTP and offline layers, the image admission queue,
and the earlier research findings. Native Rust is out of scope. No APK was
built and nothing was measured on a handset; every number below says where it
came from.

This audit adds **three implemented fixes**. Each changes only what happens at
startup or on the native transport side. Imagery resolution, terrain detail,
the renderer and the quality policies are unchanged.

## Implemented in this change

| # | Change | Measured effect | Where |
| --- | --- | --- | --- |
| 1 | Compact South Island catalogue loaded with `JSON.parse` | Main-thread parse **302 ms → 59 ms** (median of 9, Chromium, 4× CPU throttle); file 2.71 MB → 1.45 MB | `scripts/build-web.mjs` |
| 2 | Native warm-up at app launch: offline index/SQLite, overview manifest, HTTP-cache journal, provider TLS connections | First tile reuses an already-open pooled connection (verified in a test). Saves one DNS + TCP + TLS setup on the first visible tiles; not timed on a phone | `NativeWarmup.java`, `TileHttpClient.warm`, `MainActivity` |
| 3 | No full HTTP-cache scan after a transfer is interrupted mid-body | One scan costs **75–260 ms** on a 10,000-entry cache (desktop SSD, warm page cache, 20k file opens). Before this change every interrupted transfer ran one | `TileHttpClient.transfer` |

### 1. South Island catalogue: 5× faster main-thread parse

`build-web.mjs` rewrites the website's compact 190 KB `south-overviews.js`
into an object literal holding a `natural` and a `raw` row for every tile.
Each row has a 120-character URL and a nested range object. The result is
2.71 MB. Only 22 of the 5,255 catalogued tiles have different natural and raw
bytes.

`imagery-catalog.mjs` imports this module before the **first South Island
imagery tile** can be resolved. That puts its parse directly on the critical
path for the app's main region. Chromium was tested on a fresh page with no code
cache, at 4× CPU throttle to approximate a mid-range phone:

| | Bytes | Median | Min | Max |
| --- | --- | --- | --- | --- |
| Before | 2,713,134 | 302.0 ms | 220.4 ms | 561.4 ms |
| After | 1,446,047 | 59.3 ms | 54.0 ms | 73.5 ms |

The build now does two things:

- It emits the single-row `{url, range}` form when both grades are identical. `regional-imagery.js` already accepts that form for both grades.
- It wraps the data in `JSON.parse("…")`, which V8 parses much faster than an equivalent object literal.

The website file is untouched. A new test,
`tests/startup-latency.test.mjs`, resolves every catalogued tile for both
grades the same way the reader does. It checks the exact URL, pack, offset and
length against `overview-catalog.json`. A separate before/after comparison of
the generated file gave identical results for all 10,626 tile/grade lookups.
`scripts/browser-check.mjs` passes with no page or console errors, including
25 overview reads.

### 2. Native warm-up at launch

Before this change, all native state was created lazily by the first
intercepted tile request, on a WebView I/O thread and under `synchronized`
singletons. That included the offline index and SQLite store, the overview
manifest (about 5,300 ranges), and the OkHttp client and its disk-cache
journal. Every concurrent first request waited behind that work. The provider
connection also started cold: DNS, TCP and TLS ran on the critical path of the
first visible tiles, and with HTTP/2 every later tile multiplexes over that
same first connection.

Meanwhile `index.html` asked the **WebView** to preconnect to LINZ and Esri.
The app never uses those WebView sockets, because all provider reads go through
`https://localhost/_ridge/` to native OkHttp. They only competed for the radio
at startup.

Now `MainActivity.onCreate` starts `NativeWarmup` before the WebView is
created. It initialises those components on a daemon thread and sends one HEAD
request to each provider origin through the shared connection pool. The HEADs:

- bypass the tile dispatcher;
- use `FORCE_NETWORK` and are never stored;
- are discarded on completion;
- are skipped in offline-only mode or with no connectivity.

Failures are ignored, and every component still initialises lazily if the
warm-up has not finished. The build also removes the unused WebView preconnect
links.

Verification: `warmUpOpensAPooledConnectionReusedByTheFirstTile` (MockWebServer)
shows the HEAD request, then the first tile GET with sequence number 1 on the
same connection, so no new handshake. Only the tile occupies the disk cache.
The saving per launch is about one round trip for DNS plus one for TCP plus
one or two for TLS on the user's network. It was not timed on a handset.

### 3. No cache-journal scan for interrupted transfers

`TileHttpClient.evict` removes a URL by iterating `Cache.urls()`. That opens a
snapshot of **every** cache entry (metadata and body files) while repeatedly
taking the DiskLruCache monitor that every cache read and write also needs.

Before this change, the scan ran for any non-cancel I/O failure after a 200
status, including read timeouts and connections dropped mid-body. Those are
routine on mobile networks. A network drop with several transfers in flight
started one full scan per transfer, which slowed every cache hit while the
scans ran.

OkHttp aborts the cache write when a network body fails mid-read, so those
failures never leave an entry to remove. Eviction now runs only when an entry
may actually exist:

- an oversized body, which can be committed while its stream drains on close;
- a fully read body that failed validation;
- a body that was served from the disk cache.

`httpCacheEvictionScans` is now reported in the native stats.

Benchmark: 10,000 cached tiles, about the 256 MB default budget at typical
tile sizes, on a desktop JVM with SSD and warm page cache. Five consecutive
scans took 261, 118, 86, 75 and 78 ms. Phone flash and a cold page cache will be
slower. New tests cover both directions:

- `interruptedBodyIsNotCachedAndSkipsTheCacheJournalScan`: no scan, and the next load refetches. It fails against the old condition (mutation check).
- `invalidCompleteBodyIsStillEvicted`.

The 27 existing `TileHttpClientTest`, `TileDispatcherTest` and
`SharedTileBodyTest` cases still pass. They were compiled against OkHttp 4.12.0
and MockWebServer with small Android stubs, because this workspace has no
Android SDK. Gradle unit tests, lint and an APK build still need to run on a
machine with SDK 36.

## Highest-value remaining work (not implemented here)

Ranked by expected user-visible latency. Items 1–4 come from the 5 November
research and are still open; this audit re-checked the code paths and found
them unchanged.

1. **Obsolete transfers starving visible imagery.** `raster-residency.js`
   parks reloading tiles that already have textures/DEMs regardless of
   `pendingLimit`. Their transfers keep image-queue slots, so visible requests
   wait behind off-screen work. In the controlled reproduction, cancelling
   obsolete transfers cut readiness from 1,200 ms to 202 ms (injected delays).
   This is the largest single opportunity, but it touches tile lifecycle,
   abort and cache-adoption semantics. It needs integration tests before it
   ships. See `LATENCY-BREAKTHROUGHS-2026-11-05.md` §1.
2. **WebP survey-mask inputs.** The colour channels of mask PNGs are discarded.
   WebP was 88% smaller with identical alpha in 8 live samples (paced replay
   792 → 144 ms). Requires `TileAddress` format support and a PNG fallback.
3. **Motion-detail policy.** Full detail returns only 350 ms after `moveend`,
   and orbit/pitch gestures register as zero speed. Restoring focused detail
   at `moveend` brings full-detail requests 350 ms earlier. Best done *after*
   item 1, otherwise faster refinement adds to slot pressure. This lives in
   shared website code (`public/motion-detail.js`), so it needs a website
   decision or an app-only build patch.
4. **Local adaptive terrain triangulation** (45.8% fewer triangles in the
   prototype) and **cache-aware HLOD admission**. These are GPU/traffic
   improvements and need on-device validation.
5. **JS transport stream re-wrapping (new, unmeasured).** `native-transport.js`
   wraps every provider response body in a new `ReadableStream`, purely to
   notice completion. Each chunk takes an extra promise hop on the thread that
   issued the fetch, which for raster imagery is the main thread. Completion
   could instead be detected from the decorated `arrayBuffer()`/`blob()` result,
   which is how MapLibre consumes tiles. Expected gain is small per tile but
   spread across every tile; profile on a device before changing it.

Areas re-checked with no significant new opportunity:

- foreground dispatcher (uncapped, shared transfers, subscriber-aware cancellation);
- priority batching over the JS bridge (coalesced to one JNI call per task);
- offline reads (indexed SQLite, negative cache);
- overview range reads (direct asset file descriptors);
- connectivity (one callback, not a binder query per tile);
- the image queue's staged-byte bounds.

## Test status note

`npm test` on the unmodified branch tip already has 4 failing tests: 8, 14, 22
and 45 (`cache-efficiency`, `coordinate-index-cache`, `invalidation-work`,
`package`). The same 4 fail after this change, and nothing else does. The
assertions are stale against the current generated assets and should be
updated or re-baselined separately. The new `startup-latency` tests pass.
