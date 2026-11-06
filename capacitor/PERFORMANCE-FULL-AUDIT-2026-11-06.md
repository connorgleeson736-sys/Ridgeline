# Capacitor full latency and performance audit — 6 November 2026

Scope: the Capacitor app at the tip of `capacitor-latency-audit` (0.20.3 Audit
source plus the 5 November research), covering startup, the JS → native
request path, the native HTTP and offline layers, the image admission queue,
and the earlier research findings. Native Rust is out of scope. No APK was
built and nothing was measured on a handset; every number below says where it
came from.

Implemented fixes 1–3 (below) came first; 4–6 were added for the 0.21.0 Full
Audit APK. Imagery resolution, terrain detail, the renderer and the in-motion
quality policy are unchanged.

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
Android SDK. They were later run in Gradle with SDK 36 for the 0.21.0 build: 98 unit tests
pass and lint reports 0 errors.

## Added for the 0.21.0 Full Audit APK

The research items below were implemented and tested for
[`Ridgeline-Capacitor-Full-Audit-0.21.0-Base.apk`](checkpoints/README.md).
All are app-only build patches or native changes; website files are unchanged.

### 4. Obsolete off-screen reloads release their download slots

`raster-residency.js` parked unfinished off-screen tiles whenever they held a
texture/DEM, even with the off-screen allowance (4 imagery, 2 elevation) used
up. Their transfers kept image-queue slots, so visible tiles waited behind
them. The 5 November controlled replay measured 1,200 ms → 202 ms visible
readiness when obsolete transfers were cancelled (injected delays).

Now a reload that has resident data and arrives when the allowance is full is
still parked with its old texture/DEM, but its transfer is aborted. The engine
leaves such a tile `errored` with its data intact. When the tile returns to view,
it is shown with that data immediately and reloaded once the abort has settled.

These cases are deliberately left to finish:
- prepare-ahead jobs;
- DEM tiles whose image has already arrived (no live request controller);
- reloads that fit within the allowance.

Every map error listener already ignores `AbortError`. Tested in
`tests/obsolete-transfers.test.mjs` against the real pinned TileManager. With
12 reloads and an allowance of 4, exactly 4 stay in flight and 8 are released
with their textures kept. Every returning tile shows its old texture at once
and ends with the refreshed one. With an allowance of 0, all are released and
first loads without data follow the original path. Both tests fail without the patch.

### 5. WebP survey seam masks

Boundary composition uses only the alpha channel of fine-survey inputs
(`valid[i]>0` after scaling). I qualified LINZ WebP against PNG on **1,064 live
tiles covering all 46 catalogue surveys**, at every zoom from 13 to each
survey's maximum (19). Results:

- alpha was identical on all 1,064 tiles;
- every status matched;
- 37,797,239 PNG bytes against 4,028,922 WebP bytes, **89.3% less**.

Evidence: `validation/full-audit-0.21.0/survey-mask-qualification.json`.

- JS (`gap-imagery.js`, app build) requests `.webp`. If a WebP response fails or is not OK, it requests the original PNG instead (`gapMaskPngFallbacks`). A cancelled request never triggers that fallback.
- Native `TileAddress` accepts survey `.webp` as a new `mask-<survey>` address, fetched as WebP. Offline archive reads and saved-parent fallbacks use `saved()`, the unchanged `survey-<survey>` PNG identity, so existing downloaded regions still supply seam masks offline. Elevation never accepts WebP.

Tests: `tests/survey-mask-webp.test.mjs` and `TileAddressTest.webpSeamMasksFetchWebpButReadSavedSurveyPngs`.

### 6. Full detail at the end of movement

`motion-detail.js` held in-motion detail reduction for 350 ms after `moveend`.
The app now restores full detail at `moveend`. In-motion reductions,
thresholds and hysteresis are unchanged. `package.test.mjs` now allows exactly
this one difference from the website file.

I also tried counting orbit/pitch gestures as motion. That would *lower* detail
during rotations, which is a quality-policy change, so it was not shipped.

## Not shipped, with reasons

- **JS transport stream re-wrap.** Measured in Chromium (4× throttle, 64 ×
  100 KB): ~47 of ~61 ms of wrapper overhead is the per-chunk JS stream. A
  native `TransformStream` relay was not faster. Removing the wrapper loses the
  completion signal that retires native request entries, so it was left as is.
- **Local adaptive terrain triangulation.** A geometry change to terrain meshes
  that needs shader, picking and on-device validation. The project requires
  terrain detail and the renderer to be preserved.
- **Aggressive HLOD.** The research showed it can add up to 26% more cold
  parent requests. It should only be done as cache-aware admission, which is
  still a design rather than a measured change.

## Areas re-checked with no significant new opportunity



- foreground dispatcher (uncapped, shared transfers, subscriber-aware cancellation);
- priority batching over the JS bridge (coalesced to one JNI call per task);
- offline reads (indexed SQLite, negative cache);
- overview range reads (direct asset file descriptors);
- connectivity (one callback, not a binder query per tile);
- the image queue's staged-byte bounds.

## Test status note

`npm test` on the unmodified branch tip already has 4 failing tests:
`cache-efficiency`, `coordinate-index-cache`, `invalidation-work` and the
`package` engine/policy parity test. The parity test fails on
`maplibre-direct-sampling.js`, which already differs on the baseline. The
same 4 fail after these changes, and nothing else does. The
assertions are stale against the current generated assets and should be
updated or re-baselined separately. The new `startup-latency` tests pass.
