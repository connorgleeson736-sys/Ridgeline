# Performance audit — website and Ridgeline 3D (Capacitor 0.16.0)

17 November 2026. Read-only audit: no source, build or deployment was changed.

## Scope and method

- **Website:** every script the planner loads from `public/` (entry, tile pipeline, terrain adapters, workers), the hosting worker, and the deployed site's response headers.
- **App:** `capacitor/` — the build transforms, `mobile/*.js`, and all Java under `nz.ridgeline.mobile`. The earlier Kotlin app in `android/` (untouched since 26 October) was only skimmed.
- **Measurements:** headless Chromium against the live site and against a local copy of `public/` with real LINZ/Esri tiles. Rendering used software WebGL, so **network, request and JavaScript numbers are meaningful; frame rate and GPU cost are not**. Nothing was run on a phone or emulator; app findings come from code reading plus desktop measurements of the built `www/` bundle.
- Local profiling runs had no `/api/lidar` endpoint, so zoom ≤ 12 South Island overview tiles returned 404 there. That does not affect the startup or header findings.

## Findings, ranked by expected benefit

| # | Where | Finding | Evidence | Expected gain |
|---|---|---|---|---|
| W1 | Website | Every asset is served `max-age=0, must-revalidate`, so each visit revalidates ~100 files | Live headers; repeat-visit test | Repeat visits: time to first tile request **1381 → 518 ms** (with W2, W3) |
| W2 | Website + app | The 53 px logo is a 1.47 MB 1254×1254 PNG; the favicon is another 1.34 MB | Live `content-length: 1503308`; largest request on cold load | 2.8 MB → about 25 KB on first load |
| W3 | Website | Three serial script stages before the map can start | Live cold load timeline | Cold: time to first tile request **2016 → 1299 ms** (with W1, W2) |
| W4 | Website | South Island overview tiles (zoom 9–12) come from an uncached Worker → R2 range endpoint | TTFB 0.44–1.47 s per tile, 80–104 KB each | Faster first imagery at the default view |
| A1 | App | The build inflates the overview index from 186 KB to 2.71 MB of JavaScript | Built `www/south-overviews.js`; 9 → 53 ms evaluate on desktop | Removes a main-thread stall at first tile |
| A2 | App | Native tile services initialise lazily on the first tile request, behind locks | Code | Shorter time to first tile on cold start |
| A3 | App | Region downloads are capped at two transfers and write every tile twice | Code | Faster downloads; browsing cache survives |
| A4 | App | Two system calls per tile request to check connectivity | Code | Small per-tile saving |
| A5 | App | Each in-flight tile blocks a WebView pool thread; body is re-wrapped in a JS stream | Code + Chromium source | Unknown — needs a phone trace |
| W5 | Website | The app's 0.14–0.16 CPU fixes exist only as build-time patches; the website runs the old code | Profile comparison | ~18% less adapter JavaScript while moving |
| W6 | Website | Elevation tiles go through a second, 64-entry browser cache | Instrumented run | 2–13 ms per elevation tile on first visit |
| W7 | Website + app | Boundary imagery is decoded, composited, PNG-encoded, then decoded again | 28 composites just to load the default view | Faster imagery along survey boundaries |
| D1 | Repo | 41 MB of the 72 MB in `public/` is unreferenced | Reference scan | Smaller deploys; no runtime effect |

## Website

### W1 — Versioned assets are never cached without a round trip

The live site returns `cache-control: public, max-age=0, must-revalidate` for everything, including `?v=`-versioned scripts, the engine and content-hashed imagery. `build/sites-worker.ts` only runs first for `/`, and there is no `_headers` file, so its comment about "long-lived vendor assets" does not hold in production.

- A repeat visit made about 100 conditional requests to the site before the map could start.
- Fix: serve `public, max-age=31536000, immutable` for URLs carrying `?v=` and for content-hashed paths under `/assets/`. Keep `index.html` and `viewer.mjs` on `no-cache`.

### W2 — Oversized logo and favicon

- `assets/ridgeline-kea-expanded.png`: 1,503,308 bytes, 1254×1254, displayed at about 53 px in the launcher. It is fetched at the same moment as the scripts.
- `assets/ridgeline-kea-favicon-v2.png`: 1,342,502 bytes, same dimensions.
- Resized test copies: 132 px PNG is 22 KB (WebP 3 KB); 96 px favicon is 12 KB.
- The app packages both files too, plus the unreferenced `ridgeline-kea.png` (218 KB).

### W3 — Serial script waterfall

`index.html` → `viewer.mjs` → 49 static imports → `app.js` (dynamic import that waits for all of them). Live cold load: `viewer.mjs` finished at 1.6 s, the last import at 4.2 s, `app.js` at 4.5 s, first tile request at 4.9 s.

- The 52 non-engine files total 454 KB raw / 157 KB gzip. They could be one file.
- Lowest-risk fix: `<link rel="modulepreload">` for the engine, the imports and `app.js`. That is what the test variant used.
- Minor: `terrain-worker.js` imports `geo.js?v=4` while the page imports `v=5`, so the same file is fetched twice.

Controlled comparison (local server, 10 Mbps, 80 ms latency), time until the first map tile is requested:

| | As deployed | With W1 + W2 + W3 |
|---|---|---|
| Cold visit | 2016 ms, 2344 KB from the site | 1299 ms, 678 KB |
| Repeat visit | 1381 ms | 518 ms |

### W4 — Overview tiles are slow to deliver

5,255 of the 5,313 precomputed South Island tiles (all of zoom 9–12) are fetched as byte ranges from `/api/lidar/files/<pack>.bin`. Each is a Worker invocation plus an R2 read, returned `private`, so nothing is cached at the edge. The default Aoraki view requests these first.

| Request (same coordinates) | Size | Time |
|---|---|---|
| Site overview range endpoint | 80–104 KB | 0.77–1.76 s total (TTFB 0.44–1.47 s) |
| LINZ CDN tile | 5–22 KB | 0.15–0.74 s |
| Site static asset | 111 KB | 0.25–0.43 s |

Timings are from a US host with a new connection per request; compare them with each other, not as absolute values. Options: give each tile its own cacheable URL (static asset or Worker Cache API with `public, immutable`), and consider whether 80–100 KB per overview tile is needed.

### W5 — Website still runs the unoptimised adapters

`capacitor/scripts/optimise-web.mjs` rewrites six website files at app build time (`native-residency.js`, `raster-residency.js`, `response-cache.js`, `terrain-core.js`, `terrain-streaming.js`, `tile-cache.js`). The originals in `public/` are unchanged, so the website has none of those fixes, and the app depends on exact string matches against website source.

Same scripted pan/zoom, about 395 frames, main-thread self time:

| | Website source | With app patches applied |
|---|---|---|
| Ridgeline adapter scripts | ~830 ms | ~680 ms |
| `relatedCoordinates` (largest single function) | 111 ms | 72 ms |
| MapLibre engine | ~2050 ms | ~2050 ms |

The gain is real but modest, and run-to-run noise is of similar size. The stronger reason to move the fixes into `public/` is to have one source and drop the string patching.

### W6 — Duplicate elevation cache

`response-cache.js` checks CacheStorage before every elevation request, clones and stores each response, and trims the cache to 64 entries. LINZ already sends `max-age=604800`, so the browser HTTP cache covers this. The app removed the layer in 0.16.0; the website still has it.

- First visit: 29 lookups, 0 hits, 28 writes; lookup p50 2.6 ms, p95 13.4 ms before each network request starts.
- Reload: lookups p95 36.7 ms, max 127.7 ms.

### W7 — Boundary imagery round trip

Tiles on a survey boundary are fetched as two or more images, decoded, composited on a canvas, encoded to PNG, transferred, cached as PNG bytes and decoded again by the engine. Loading the default view alone ran 28 such jobs (23 boundary tiles).

- Returning an `ImageBitmap` for display and encoding lazily (or not at all) would remove an encode and a decode per tile.
- If the worker fails, composition falls back to the main thread. One profiling run showed 2.1 s of main-thread PNG encoding this way. I could not reproduce it (a second run did all 41 jobs in the worker), so treat it as a risk, not a measured norm.

### Checked, no action needed

- Route autosave rebuilds the saved list twice per edit, but costs only 3–6 ms per point with 20 routes of 400 points.
- Diagnostics and tile tracing are opt-in and idle by default.
- Per-frame policy code (`performance.js`, `motion-detail.js`, `surface-focus.js`) is cheap.

## App (Capacitor 0.16.0)

### A1 — Overview index inflated 14×

`build-web.mjs` replaces each compact `[pack, offset, bytes]` row with two objects holding a full URL and a repeated 64-character pack hash. Result: `south-overviews.js` grows from 186 KB to **2.71 MB**, evaluated on the main thread when the first zoom ≤ 12 South Island tile is requested, with those tile fetches waiting on it. Desktop evaluate time went from 9 ms to 53 ms; expect several times that on a phone. Keeping the compact rows and building the URL at lookup avoids it.

The native side has the same shape: `overview-manifest.json` is 442 KB, parsed with `org.json` on the first overview request.

### A2 — First tile pays for all native start-up

`TileArchive.get`, `OverviewStore.get` and `TileNetwork.client` are synchronized lazy singletons created inside `TileGateway.intercept`. The first tile request reads `index.json`, opens SQLite and runs five statements, parses the overview manifest and opens six asset descriptors, and builds the HTTP client whose 256 MB disk cache reads its journal on first use. Other tile requests queue behind those locks. Warming all three on a background thread in `MainActivity.onCreate` would overlap that work with WebView start-up.

### A3 — Region downloads

- `RegionTransfer` starts three producers, but `TileDispatcher` admits only two background transfers, so one producer is always waiting. A 12,000-tile region is fetched two at a time.
- Download tiles go through the caching HTTP client, so each is written to the 256 MB OkHttp cache and again to SQLite. A large region evicts the whole online browsing cache.
- Every 16-tile batch does a full-sync SQLite commit, a `wal_checkpoint(FULL)` and an fsynced rewrite of `index.json`.

### A4 — Connectivity check per tile

`TileGateway.connected()` calls `getActiveNetwork()` and `getNetworkCapabilities()` for every online tile request. A registered `NetworkCallback` holding the last state would remove both calls.

### A5 — Interception path (needs a device trace)

This is the main structural difference from Chrome on the same phone, and I could not measure it here.

- `intercept` blocks in `lease.await()` until response headers arrive, and the stream then blocks on body reads. Current Chromium runs `shouldInterceptRequest` on its shared thread pool as a may-block task (checked in `aw_contents_io_thread_client.cc`), so each in-flight tile holds a pool thread.
- `native-transport.js` wraps every native response body in a JavaScript `ReadableStream`. Every consumer then calls `arrayBuffer()`, so the early-streaming design from 0.13.0 has no reader that benefits from it.
- Suggested experiment: compare `RidgeMobile.loadingStats()` and a Chrome remote trace with interception limited to saved tiles and offline mode.

### A6 — Smaller items

- The app ships six modules used only by the excluded luma renderer (`terrain-view-worker.js`, `terrain-data.js`, `terrain-patches.js`, `terrain-primitives.mjs`, `terrain-scheduler.mjs`, `terrain-gpu-common.mjs`).
- Start-up is `mobile.js` → `viewer.mjs` → 48 modules → `app.js`, about 50 intercepted asset requests. Bundling would cut that to a handful.
- `TileDispatcher.pump` rescans all jobs under a global lock on every priority change. Job counts are small, so this is low priority.

### Earlier Kotlin app (`android/`)

Lightly reviewed. `ElevationRepository.profile` samples points one at a time, so uncached elevation tiles for a route profile are fetched serially.

## Repository

- **D1:** unreferenced in `public/assets`: `gap-imagery-v1`, `-v2`, `-v3` (36.6 MB, 2,014 files), `imagery-boundary-v2` and `-v3` (4.6 MB), `ridgeline-kea.png`, `icon.svg`. The app build already excludes most of them; the website deploy does not.
- `package.json` carries starter-template dependencies (Next, Recharts, Radix and others) that the planner never loads. Build-time cost only.

## Not established

- Phone frame rate, GPU cost and WebView behaviour.
- Absolute map-ready time: under software rendering the map took 27 s or more to report loaded, which says nothing about real devices.
- Why the imagery worker became unavailable in the one run described under W7.
