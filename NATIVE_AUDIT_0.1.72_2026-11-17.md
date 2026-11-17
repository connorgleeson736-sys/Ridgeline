# Performance audit — Ridgeline Native 0.1.72 and the Bushline Maps website

17 November 2026. Read-only audit: no source, build or deployment was changed.

Audited source: commit `b2de7fb` ("Te Araroa and DOC cards (0.1.72)") on branch `claude/youthful-hypatia-lrzouw` — `native-wgpu/` (Rust renderer and Kotlin app) and `website/`. Neither exists on `main`. The earlier `PERFORMANCE_AUDIT_2026-11-17.md` covers the older website and the Capacitor app on `main`; it is not about this app.

## Method and limits

- **Read in full:** frame loop and gestures (`TerrainView.kt`), tile scheduling and preparation (`TileLoader.kt`, upload queue and admission, DEM sampling), overlay selection and publication (`MapLayers.kt`, `VectorTile.kt`), imagery sourcing (`NativeImagery.kt`), start-up (`MainActivity.kt`), build configuration, and the renderer's render, upload, adaptive-mesh, picking, resolution and shader code.
- **Skimmed or not reviewed:** the sheet and planner UI (`TrackSheet`, `PlannerUi`, `OfflineUi`, `WalkOptions`), walk routing, offline region storage and downloads, `HttpTileBackend`, the JNI glue in `android.rs`, and the internals of `labels.rs`, `routes.rs` and `overlay.rs`.
- **Measurements** were made on a cloud x86 host: the real renderer on a software Vulkan driver with live LINZ elevation, the app's Kotlin code compiled for the JVM, and `curl` timings from a US host.
- **Not measured:** anything on a phone. Triangle counts, byte counts and request counts carry over to a device; millisecond figures do not.

## Findings, ranked by expected benefit

| # | Where | Finding | Evidence | Expected gain |
|---|---|---|---|---|
| N1 | App | Zoom 9–12 imagery is streamed from an uncached `r2.dev` address | ~0.30–0.41 s per tile on a warm connection, 40–103 KB each; LINZ ~0.04–0.05 s, 5–22 KB | First imagery of every zoomed-out view |
| N2 | App | No tile is requested until two JSON catalogues (2.9 MB) are parsed | `loadView` returns until `imageryReady`; 206 ms cold parse on desktop | Several hundred ms off every cold start |
| N3 | Renderer | The default view submits ~631,000 triangles per frame; about 40% are off-screen | Real renderer, real LINZ elevation | Largest remaining GPU lever on low-end phones |
| N4 | Renderer | The adaptive mesh barely helps in alpine terrain | Relaxing the error budget from 0.6 to 2.0 px: 631k → 608k triangles | Same lever as N3 |
| N5 | App | Elevation dominates bytes: 43 source tiles, 4.2 MB for the default view | Fetched from LINZ | Known; needs a format change |
| N6 | App | No baseline profile, and R8 is off | `build.gradle.kts` | Faster Kotlin paths right after each install |
| N7 | App | Decode pools are fixed at two threads each | `TileLoader.kt` | Unknown — needs a device check |
| N8 | App | Sea-level terrain tiles take a slow sampling path | 0.23 ms → 1.99 ms per tile on desktop | Small |
| S1 | Website | Overview imagery defaults to the same `r2.dev` address | `build.mjs` | As N1, if deployed without `OVERVIEW_URL` |
| S2 | Website | 23 of 25 start-up modules are not preloaded | Cold start 788 → 675 ms with preloads | ~110 ms on a cold visit |

## Native app

### N1 — Overview imagery comes from `r2.dev`

`map-defaults.json` sets `overviewUrl` to `https://pub-b39506879e9740798b22fa3186e4206d.r2.dev`. In the Base APK, `NativeImagery.streamed()` sends every zoom 9–12 aerial tile there (zoom ≤ 8 is bundled). The address has no edge cache; the site's own README calls it "slow (uncached, rate-limited)".

| Same eight coordinates, one warm connection | Time per tile | Size |
|---|---|---|
| `r2.dev` overview tile | 0.30–0.41 s | 40–103 KB |
| LINZ CDN tile | 0.04–0.05 s | 5–22 KB |

The default view needs 15 such tiles (zoom 11 and 12). Timings are from a US host; compare them with each other. Serving the bucket through a cached custom domain, as the README already recommends for the website, removes most of the gap.

### N2 — Tile loading waits for catalogue parsing

`TileLoader.loadView` returns immediately until `imagery.initialize()` finishes. That call parses `overview-index.json` (2,237,545 bytes) and `gap-atlas.json` (720,344 bytes) with `org.json`. Terrain requests do not need either file but wait all the same.

- Desktop JVM, cold: 165 ms + 41 ms. Interpreted only: about 3 s. A phone will sit between the two.
- Options: start terrain requests before the catalogue is ready; split the bundled zoom ≤ 8 "coarse" table from the 8,957-entry tile table; or ship a compact binary index.

### N3 and N4 — Triangle load

I rendered the default Aoraki camera through the real renderer with real LINZ elevation (29 terrain tiles, 73 image tiles, 50 draw calls).

| Screen | Triangles per frame | Triangles per pixel |
|---|---|---|
| 720×1520 | 631,288 | 0.58 |
| 1080×2280 | 655,104 | 0.27 |

- Only about 370,000 full-density triangles fall inside the viewport. Roughly **40% of what is submitted is off-screen**, because each tile draws its whole region. Splitting a tile into separately culled blocks would cut that.
- On the 720p screen, **23% of on-screen triangles cover less than one pixel** and 42% less than two (10% and 20% at 1080p).
- The per-tile rule uses the tile's worst cell, so one cliff keeps a whole tile at full density:

| Error budget / cell limit | Triangles (720×1520) |
|---|---|
| 0.6 px / 10 px (shipped) | 631,288 |
| 2.0 px / 20 px | 607,712 |
| No error limit / 10 px | 414,152 |
| No limits | 76,704 |

A looser error budget while moving therefore gains almost nothing here. The local (per-block) meshes studied in `ADAPTIVE-MESH-STUDY-2026-11-06.md` are what would help; the gap between 631k and 77k is the room available. This matches the 7 November phone trace, which found the app GPU-bound on a 720p Mali-G71.

### N5 — Elevation bytes

The 29 terrain tiles of the default view need 43 LINZ elevation tiles, 4.2 MB, against roughly 2 MB for all 73 image tiles. The project's own audit already records this and that fixing it needs pre-built meshes or a bundling service.

### N6 — Build configuration

The `performance` build type is non-debuggable, but R8 is off and there is no baseline profile or `profileinstaller` dependency. A freshly sideloaded APK runs its Kotlin under the JIT until Android compiles it in the background, and every new version resets that. With 73 versions installed in three weeks, most test sessions run on freshly installed code. A baseline profile covering the tile pipeline would compile it at install time.

### N7 — Fixed two-thread pools

`geometryWorkers`, `imageWorkers` and the transport's local-read pool are each two threads, whatever the phone has. The 7 November trace showed image decoding at about 3.4 s of CPU in 15 s, so they were not saturated on average, but bursts after a fast pan may queue. The loading diagnostics already show "Imagery CPU active/queued"; check it on a multi-core phone before changing anything.

### N8 — Sea-level tiles

`DemSampling.meshAtSourceZoom` takes a slow interpolation path whenever a height is exactly 0, to preserve signed zero. An all-sea tile takes 1.99 ms against 0.23 ms for a mountain tile on desktop. Small, but it applies to every coastal view.

### Checked, no action needed

- **Vector tile decode and styling:** 0.01–0.9 ms decode and 0.03–4.1 ms styling per tile on real LINZ tiles, including dense Wellington tiles. The single cartography thread is not a bottleneck.
- **View selection:** 52 µs median for a 96-tile plan.
- **Renderer CPU per frame:** 0.4–0.6 ms for the default view.
- **Start-up on the main thread:** only small reads (a 20 KB style file, `map-defaults.json` read twice, a small logo).
- **Per-frame Kotlin work** in `drawFrame` and the upload queue: small collections, no notable cost.

## Website (`website/`, Bushline Maps)

The new site already does what the old one lacked: content-hashed paths with `immutable` caching, preloads for the engine and data, small images, no third-party scripts. In a controlled cold load (10 Mbps, 80 ms latency) the first tile request went out at 788 ms, against 2016 ms for the old site.

- **S1:** `build.mjs` takes `OVERVIEW_URL` from the app's `map-defaults.json` when unset, so a production build points at `r2.dev` (see N1). The README documents this; the build does not warn.
- **S2:** only `main.js` and the engine are preloaded. The other 23 modules are discovered across two more levels. Adding `modulepreload` for all of them moved the first tile request from 788 ms to 675 ms.
- **Smaller:** own JavaScript is unminified (284 KB raw, 98 KB gzip); `/data/*` is cached for one day although its URLs carry a version and could be `immutable`.
- `bushlinemaps.co.nz` did not resolve from here, so nothing was measured against a live deployment.

## Not established

- Phone frame rate, GPU time, upload cost and memory behaviour.
- Whether the per-frame upload limits (four uploads or 650 KiB a frame; one image a frame while moving) hold back refinement on a real device. The app's "GPU admission" diagnostics would show it.
- `r2.dev` latency from New Zealand.
