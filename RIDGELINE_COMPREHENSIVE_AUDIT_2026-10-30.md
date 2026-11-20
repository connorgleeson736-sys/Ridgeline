# Ridgeline — Comprehensive Technical Architecture and Performance Audit
**Date:** 2026-10-30  
**Status:** Source-level investigation and implementation handoff; no application changes or device benchmarks performed  
**Target repository:** connorgleeson736-sys/Ridgeline  
**Audited branch:** native-rust-wgpu, commit 6a84dab6bc632633bd704aacce004d41a0a1120b  
**Website reference:** public/ on that branch; authoritative Capacitor 0.18.0 reference d880143418ac46ece98adff0b3b2fbaedf044d46  
**Native implementation:** native-wgpu/ on the audited branch  
**Related previous evidence:** capacitor/PERFORMANCE-0.18.0.md, capacitor/PERFORMANCE-AUDIT.md, capacitor/LOADING-OPTIMISATIONS.md, native-wgpu/validation/*

> **For the implementing AI:** Read this document, GOAL.md, NATIVE_PROGRESS.md, capacitor/PERFORMANCE-0.18.0.md, and native-wgpu/README.md before editing. Preserve original LINZ imagery, original source resolution, official geographic masks, terrain accuracy, working caches, route behaviour and user-facing controls. This document is a prioritised investigation plan, not authority to make every change. Do not make speculative performance claims or quietly replace real terrain/photography with synthetic approximations. Implement changes in small independently verifiable stages, save/commit each stage, and compare against a frozen baseline. Prefer the current renderer unless controlled evidence justifies replacing it.

## 0. Scope, evidence and interpretation

The repository contains several different products, which must not be confused:

1. **Website:** public/index.html -> public/viewer.mjs -> patched MapLibre GL JS 6.14.0 plus custom terrain/imagery/residency/route modules. Optional luma.gl viewer. Hosted through the Cloudflare/Vinext asset worker.
2. **Capacitor 0.18.0:** a packaged WebView build with generated/patched web assets, native transport, offline archive and Android-specific optimisations. Its generated capacitor/www is not committed as source on this branch. Public/ and the packaged APK are NOT automatically byte-identical.
3. **New fully native Android app:** native-wgpu/android Kotlin UI and tile pipeline + native-wgpu/renderer Rust/wgpu Vulkan/GLES renderer. Separate package, data and rendering architecture.
4. **Older native Android prototype:** android/; do not accidentally modify it when working on native-wgpu/.
5. **Data/asset pipelines:** scripts/imagery, tools/lidar_pipeline, public/assets, lib/lidar-storage.mjs.

Inspected source modules and existing validation/research records across these areas, including request queues, workers, image/DEM processing, GPU drawing, retention, caching, offline downloads, route planning, UI, deployment and tests. This is NOT a line-by-line formal verification of every vendored binary, image asset or generated APK. No real phone or production browser session was executed; GitHub code and existing reports were reviewed. Label findings as:
- **CONFIRMED:** directly observable code structure, configuration, algorithm or documented acceptance gap.
- **LIKELY/CONDITIONAL:** performance or correctness risk inferred from code, requiring instrumentation.
- **EXPERIMENT:** alternative architecture with uncertain payoff.
- **NOT ESTABLISHED:** claims such as specific FPS improvements or phone visual parity without device traces.

The 0.18.0 report records an earlier phone workload with approximately 100.7 ms mean movement frames, 183.8 ms p95, LINZ selection ~22 ms mean / 50.5 ms p95, terrain selection ~11.3 / 26.3 ms, and texture preparation ~6.4 / 15 ms. It also describes 15 active / 33 queued image jobs and ~442 ms p95 image-decode *elapsed* time. These are mixed/inclusive timings, NOT additive pure CPU or GPU times. The earlier 0.17.0/0.18.0 optimisations already reduced specific selection and allocation costs in controlled host fixtures, but no new phone FPS improvement was established. Do not repeat those fixes as though absent.

## 1. Executive ranking

| ID | Priority | Subsystem | Evidence | Finding / next action |
|---|---|---|---|---|
| WEB-01 | P0 | Browser image queue | CONFIRMED | Image admission slots remain occupied through decode; separately bound delivery, decode, prepared bytes and GPU upload. |
| WEB-02 | P0 | Main-thread selection | CONFIRMED prior trace | Re-profile 0.18.0 on device; determine which MapLibre tile selection, RTT, visibility and event paths still dominate. |
| WEB-03 | P1 | Regional image composition | CONFIRMED | One actual worker handles a scheduler permitting two composites; investigate a bounded 2–3 worker pool only if composite backlog is observed. |
| WEB-04 | P1 | Renderer internal hooks | CONFIRMED | Many patches depend on private MapLibre 6.14.0 internals; add compatibility and lifecycle/invalidation contract tests. |
| WEB-05 | P1 | RTT/direct terrain | CONDITIONAL | Measure RTT rebuilds, direct fallback frequency, image-texture binds and off-screen drape reuse. |
| WEB-06 | P1 | Cache/memory | CONFIRMED | The 96–3000 MiB slider controls selected caches, not total browser/GPU/process memory; enforce pressure-aware global policy. |
| WEB-07 | P1 | Off-screen loading | CONFIRMED | Preparation is idle/visible-ready/pressure gated and effectively serial; tune based on real idle time and reuse, not blanket concurrency. |
| NAT-01 | P0 | Native acceptance | CONFIRMED | Physical-device FPS, real-terrain visual parity and mixed-LOD no-skirt seam acceptance remain open. |
| NAT-02 | P1 | Native CPU workers | CONFIRMED | Two imagery + two geometry workers, two local lookup workers; benchmark staged 3/2 and 4/3 configurations. |
| NAT-03 | P1 | Native GPU upload | CONFIRMED | 650 KiB per-frame upload cap and two-frame backlog guard may limit tile admission; instrument stage throughput and test idle bursts. |
| NAT-04 | P1 | Native GPU resources | EXPERIMENT | Texture arrays/layer pools, stable index allocations and grouped draws could reduce resource churn. |
| NAT-05 | P1 | Terrain selection | CONFIRMED | Source tile demand uses projected size/focus, not precomputed per-tile geometric error; evaluate error-driven LOD. |
| NAT-06 | P1 | Terrain source reuse | CONFIRMED | Render geometry can request z22 from z18 source DEM; investigate source-relative UV/DEM reuse. |
| NAT-07 | P1 | Route geometry | CONFIRMED | Segment x terrain-cover nested traversal and full route vertex-buffer replacement; spatially index and cache per segment. |
| NAT-08 | P1 | Transport admission | CONFIRMED | Repeated linear filtering/minimum scan of all queued requests; profile before introducing a priority heap. |
| DATA-01 | P1 | Offline identity | CONFIRMED | Native resource keys and derived cache namespace incorporate URL/API key; design stable versioned identities with safe migration. |
| DATA-02 | P1 | Offline lifecycle | CONFIRMED | Region download jobs are tied to activity loader executors; support resumable persistent transfer. |
| DATA-03 | P2 | R2 asset range serving | CONDITIONAL | Sequential chunk reads and per-range requests can amplify latency; measure origin calls and first-byte time. |
| OPS-01 | P1 | Builds/profiling | CONFIRMED | No GitHub Actions workflow on audited branch; no continuous Android artifact, regression or device benchmark pipeline. |
| UX-01 | P1 | Native parity | CONFIRMED | Web-only topo/labels/direct shading and native route/UI differences need feature-by-feature acceptance, not only GPU tests. |

## 2. Current concurrency and queue topology — critical for tuning

### 2.1 Website / MapLibre pipeline

Source: public/app.js:122-125, public/loading-controls.js, public/terrain-scheduler.mjs, scripts/maplibre-image-queue.js, public/regional-imagery.js, public/regional-scheduler.js, public/regional-imagery-worker.js, public/terrain-core.js, public/terrain-view-worker.js.

| Stage | Current implementation | What its limit actually controls |
|---|---|---|
| MapLibre engine worker pool | min(2, max(1, hardwareConcurrency-2)) | Primarily tile parsing/source worker tasks; does NOT make main-thread terrain selection or GPU rendering parallel. |
| Foreground image admission | 8 while moving, 12 stationary by default; user slider 1–96 | Patched image queue's running jobs, including delivery and decode. Cached jobs reserve at most one slot when ready bytes exist. |
| Decode admission | Defaults to current image limit; separate setDecodeLimit API exists | Browser image decode concurrency, not number of dedicated JavaScript workers. |
| Encoded imagery byte cache | Budgeted, deduplicated and cancellable | Shared compressed bodies, not decoded bitmaps or GPU textures. |
| Regional composition scheduler | concurrency=2, queue max 32, queued inputs max 16 MiB | Admission to regional composition tasks. Both admitted tasks currently target ONE dedicated worker. |
| Regional composition workers | 1 | Heavy mask rasterisation, bitmap/canvas composition and lossless PNG encoding execute on this worker. Async tasks can overlap waits, but JavaScript CPU is not parallel within a single worker. |
| Profile worker | 1, single active profile request | Appropriate for the current single route profile; do not multiply unless multiple independent profiles are needed. |
| Optional luma terrain worker | 1 | DEM fetch/decode/preparation; terrainJobs allows up to 4 and may raise to <=8 but these are promises handled by one worker. |
| Luma DEM fetch concurrency | 6 default, configurable to 16 | Concurrent I/O tasks inside a worker, NOT 6–16 CPU threads. |
| Browser imagery predictive | Default 2, maximum 12; 24/gesture and 60/minute in website preload | Background compressed-tile requests, gated by foreground and cache pressure. |
| Browser off-screen pending | Imagery 4, DEM 2 default | Number of non-visible requests retained while moving, not image decoder or GPU worker count. |
| Idle expanded-view preparation | Single active tile at a time, gated on visible completion | May prepare cached GPU tiles, but only after foreground is ready; not a continuous overscan renderer. |

**WEB-01: Delivery and decode currently share image-job slots.** In scripts/maplibre-image-queue.js, run(job) increments the active image count before fetching and decrements it in finally AFTER prepareImage/decode. This means a slow decoder can keep a network admission slot occupied after the body has already arrived. The reported 442 ms p95 is *elapsed decode stage*, so determine how much is waiting versus actual decoder CPU. Experiment with two independent semaphores: (a) delivery/encoded-body admission and (b) bounded bitmap decode. Release delivery admission after the encoded body is received, but enforce an explicit byte budget on downloaded bodies waiting for decode. Do not create an unbounded decoded or encoded queue. Keep cached-ready fairness, cancellation, source freshness and transfer ownership. Measure time in each queue separately. This may help cold coverage more than simply raising the image limit.

**WEB-03: Regional worker parallelism is not equivalent to scheduler concurrency.** The worker's onmessage handler is async, but mask generation, 2D canvas operations, pixel grading and convertToBlob work are not made multi-core by scheduling two jobs on one worker. First collect composite queued/active counts, input bytes, decode time, mask time, PNG encode time, worker CPU and p95 latency. Only if this stage is a bottleneck, test a pool of 2 workers on mid/high-end and 3 on high-end, with each worker owning a small bounded mask cache. Route jobs to least-loaded workers, transfer ArrayBuffers, propagate cancellation and generation IDs, and preserve exact pixel output. Do not add workers to solve ordinary LINZ tile decoding; the regional worker is only used for special source-boundary/composite tiles. The 0.18.0 report explicitly says there was no regional composition backlog in its exported workload.

**MapLibre worker count:** Raising 2 -> 4 may improve vector tile parsing/label placement in topo-heavy views, but may increase CPU contention with WebGL main thread and decoder. It is unlikely to directly accelerate aerial image delivery or main-thread terrain selection. Test 1/2/4 with overlays off/on. Do not default to 8+ on a low-memory phone.

**Image admission:** Test moving/stationary combinations 8/12 (baseline), 12/16, 16/24, and 24/32 on high-end hardware ONLY, with independently bounded decode at 4/6/8. 96 is a diagnostic upper bound, not a recommended default. For each run measure bytes/sec, server status 429/5xx, per-host connection count, encoded queue bytes, image decode waiting/CPU, first useful texture, p95 frame time, heap peak and number of tiles actually drawn. Increasing requests can worsen frame time through decode and GPU upload contention.

**More DEM jobs:** The MapLibre raster-DEM path is not the same as the optional luma terrain worker. Investigate DEM source-request admission and decoded DEM preparation separately. For luma, a second actual terrain worker may help CPU-heavy tile preparation if CPU time and queueing dominate, but each worker duplicates DEM caches and can fetch identical halo parents. Before pooling workers, centralise deduplication of source DEMs and maintain a bounded shared admission service. Otherwise extra workers can INCREASE network requests, memory and cold-start time.

### 2.2 Fully native Kotlin/Rust pipeline

Source: native-wgpu/android/app/src/main/java/nz/ridgeline/nativeapp/TileLoader.kt:29-37,99-103,112-151,153-258; TileTransport.kt:17-30,92-118; TerrainView.kt:124-150; native-wgpu/renderer/src/residency.rs.

| Stage | Current configuration | Important qualification |
|---|---|---|
| Local disk/offline lookup | 2 threads | Separate from network admission; confirmed cached hit can bypass blocked network requests. |
| Network admission | 8 foreground moving / 12 stationary, plus at most 2 speculative | TileTransport controls starts; raising OkHttp dispatcher alone does not raise this. |
| OkHttp dispatcher | maxRequests=32, maxRequestsPerHost=16 | Additional ceiling; provider and HTTP/2 behaviour must be measured. |
| CPU geometry preparation | 2 priority-queue workers | Futures suspend tasks instead of blocking a worker on network. |
| CPU image preparation | 2 priority-queue workers | Bitmap decode, crop, compositing and RGBA conversion. |
| Prepared disk cache writes | 1 background worker, queue 8 | Avoids synchronous writes on render/preparation threads. |
| GPU upload queue | capacity 192 | Demand-pruned, coalesces duplicate ownership. |
| GPU upload admission | 650 KiB byte cap per draw frame; up to 4 cheap uploads; two outstanding-frame backpressure | Potential throughput choke; adaptive policy already exists and must be preserved. |
| Resident GPU imagery | base 24–128, with up to 25% extra image count borrowing unused terrain budget | Byte budget prevents both maximum count limits being fully populated. |
| Idle warm preparation | Disabled by default; at most 2 extra imagery + 2 geometry sources per camera revision | Protects foreground; not a broad ring cache. |

**NAT-02: Increase CPU workers only when their queues are actually saturated.** Two image workers and two geometry workers can leave cores idle if CPU preparation is the limiting stage, but 2+2 is sensible on a low-end A20-class phone. The 8/12 network admission already runs independently; doubling CPU workers does not double provider throughput. Benchmark:
- Low-end baseline 2 image / 2 geometry / 2 local; optional 3/2 only if spare CPU and memory.
- Mid-range 3 image / 2 geometry, then 4/3.
- High-end 4 image / 4 geometry if CPU work, memory and thermals permit.
- Local lookup 2 -> 4 only if local lookup queue time is high; cached lookups already have separate threads.
Use bounded queues, priority promotion, cancellation and memory-pressure feedback. Preserve UI and GPU thread headroom. Do not create one thread per tile/halo.

**NAT-03: GPU upload can become a self-reinforcing bottleneck.** A full 256x256 RGBA tile is 256 KiB before mipmaps; a 129x129 float DEM is ~65 KiB. The 650 KiB per-frame byte limit allows only a small number of images per frame, and fewer frames means fewer opportunities to upload. The theoretical 650 KiB x 60 FPS is ~38 MiB/s, not an observed rate. Measure queue depth, upload bytes/sec, upload count/frame, JNI time, mipmap time, GPU completion and presentation latency. Keep the conservative cap while actively dragging; test larger **idle-only** upload bursts (e.g. 1–2 MiB) when the GPU is demonstrably idle and the two-frame backlog guard is clear. An unconditional increase could make p95 frame time worse. Preserve visible fallback and newest-camera ownership.

**NAT-08: Queue selection does repeated full scans.** TileTransport.pump filters all jobs by phase/consumer/speculation and calls minByOrNull in a loop for each admitted request. With a large queue this is potentially O(active starts x queued jobs), even though jobs are capped at 512. Instrument queue sizes and time spent inside pump; only if material, replace with a priority structure supporting promotion, cancellation, foreground reservation, aging and stable tie-breaks. Mutable priorities invalidate naive heap entries: use versioned lazy invalidation or indexed heap. Keep subscriber deduplication and immediate local-hit bypass.

**Network admission tuning:** Test 8/12+2 baseline, 12/16+2, then 16/20+2 on faster devices. If >16 requests to the same host are genuinely beneficial, coordinate the OkHttp per-host cap. Track provider 429s, HTTP/2 multiplexing, latency, bytes/sec, cancellation success, thermal pressure and actual GPU readiness. Do not raise maxRequestsPerHost merely because it is numerically smaller than a proposed foreground limit.

## 3. Website: main thread, terrain selection and rendering

### WEB-02 — Re-profile 0.18.0 selection on device [P0; CONFIRMED earlier bottleneck]
The website uses patched MapLibre 6.14.0 with many cooperating adapters: performance.js, adaptive-terrain.js, terrain-visibility.js, native-residency.js, raster-residency.js, selection-interval.js, maplibre-direct-sampling.js, terrain-composition.js, prepare-ahead.js. Previous work already memoised LOD-invariant integrals, parent DEM keys, geographic indexes and projection matrices. Do not redo these without a fresh profile. Collect JavaScript sampling stacks on the actual WebView after 0.18.0, and break out tile selection by source, terrain.update, getSourceTile, getMinMaxElevation, RTT prepare, tile manager update, route source updates and style invalidation. Instrument counts and per-frame work, not only elapsed time around asynchronous operations. Establish which work survives when no tile arrives and no overlays are enabled.

### WEB-04 — Private MapLibre internals are a maintenance risk [P1; CONFIRMED]
The adapters monkey-patch or access private methods/fields such as map._render, tileManager.update, painter.render, terrain.getTerrainMesh, renderToTexture.prepareForRender, _textureDifference, _sourceTileCache, _outOfViewCache and _inViewTiles. Most have version guards for 6.14.0, but changing engine versions or adapter install order can silently break resource ownership, cache invalidation or cancellation. Centralise capability checks and adapter lifecycle registration; add tests for install/destroy/reinstall, style replacement, WebGL context loss/restoration, missing private members, source revisions, stale asynchronous callbacks and rollback after exceptions. Treat the vendored engine + patch scripts + generated Capacitor asset as a pinned versioned product, not a generic npm MapLibre dependency. Avoid another layer of monkey-patches unless it removes more complexity than it adds.

### WEB-05 — RTT composition versus direct image sampling [P1; CONDITIONAL]
MapLibre terrain normally composites raster/overlay stacks into render-to-texture (RTT) terrain drapes. native-residency.js caches/retains drapes, tracks source revisions and attempts to reuse finished stacks. maplibre-direct-sampling.js bypasses composition for eligible terrain patches but has a maximum of 8 imagery textures per patch; ineligible/overlaid patches fall back to RTT. Instrument per-frame: direct patches, composed patches, number of source images per patch, RTT allocations, repaint reasons, stack count, compositing GPU time and shader texture binds. Compare identical views with Direct off/on and overlays off/on. Do not assume Direct always wins: up to eight texture samplers and branch tests per fragment may be expensive on older mobile GPUs, whereas a precomposed RTT costs upfront but samples one texture afterward. Test performance after warmup separately from first arrival. Avoid blanket removal of RTT because mixed overlays and source boundaries need correct composition.

### WEB-07 — Off-screen preparation is more constrained than its UI suggests [P1; CONFIRMED]
prepare-ahead.js only works when enabled, not moving, not low-power/save-data, visible tiles ready, caches below pressure thresholds, and suitable native tile managers are available. It has one active tile, source/rate/byte budgets, and optional RTT composition. Expanded view does not mean all tiles within 30–70 degrees are loaded, and it does not mean those tiles are drawn every frame. Instrument why each opportunity is declined: not idle, incomplete foreground, cache pressure, image queue, DEM queue, source byte budget, per-minute rate, unsupported manager. Report requested vs actually prepared vs later reused tiles. If reuse benefit is proven, permit a small parallel source-fetch lane but keep GPU composition serial/budgeted; do not allow speculative downloads to delay visible data. A separate warm-coverage policy should retain coarse coverage and likely rotation destinations within a memory fraction, not promise full-resolution 70-degree overscan on every phone.

### WEB-08 — Cached resources must not become immortal [P1; CONDITIONAL]
raster-residency.js sets native out-of-view cache size to MAX_SAFE_INTEGER and implements its own byte-bound eviction. native-residency.js additionally retains RTT drapes; tile-cache.js and response-cache.js retain encoded inputs. This layered design is intentional, but risks inaccurate total-memory accounting or missed lifecycle invalidation. Build a single diagnostic memory ledger: source raster/DEM resident estimates, RTT GPU textures, compressed RAM, decoded images, CacheStorage, queued encoded inputs, worker heaps and WebGL textures. Mark actual vs estimated bytes; browser JS cannot know all driver memory. Confirm eviction callbacks release GPU resources and pending requests after source changes, style changes, WebGL context loss and low-memory signals. Never remove useful off-screen tiles simply because they are not currently drawn.

### WEB-09 — GPU pixel ratio and terrain complexity [P1; CONDITIONAL]
performance.js dynamically controls device pixel ratio and adaptive terrain grid density. A lower pixel ratio can improve fragment-bound frames but cannot fix main-thread selection stalls or slow network admission. Benchmark fixed 0.75/1.0/1.25 DPR at the same viewport and camera, with native selection and image jobs held constant. Measure whether fill rate, fragment shader or RTT render target area dominates. Keep topo map sharpness and avoid blurry near-field terrain as a hidden default.

### WEB-10 — Adaptive terrain analysis costs [P2; CONFIRMED implementation]
adaptive-terrain.js derives tile error metadata from resident DEM cooperatively, with bounded idle work and shared meshes at 32/64/128. This is already a quality-preserving optimisation. Measure analysis queue length, per-slice CPU time, cache invalidations and mesh variant switches. Precomputing DEM error metadata offline could reduce first-visit work, but must be tied to DEM dataset/version and actual tile elevation provenance. Do not reduce geometry everywhere merely to lower triangle count; cliffs, ridges and silhouettes require high resolution.

### WEB-11 — Overlay and label cost [P2; CONDITIONAL]
topographic-overlays.js uses one shared MapLibre vector source; labels and routes add layer/style updates. Test overlays all off, peaks only, huts/routes, contours and all on. Record vector worker parse, symbol layout, glyph requests, RTT invalidations, draw calls and selection CPU. Only increase MapLibre workers if vector parse/symbol layout queueing is proven. Reuse immutable source definitions and batch visibility updates. Avoid individual DOM labels for dense peak sets; use map symbol layers/priority rules. Keep major-peak hierarchy at distant zoom.

### WEB-12 — Route editor and elevation graph [P2; CONDITIONAL]
route-interactions.js uses queryRenderedFeatures/project for pointer hit-testing, with RAF-coalesced hover. elevation-chart.js uses SVG, creates paths/ticks and calculates selection statistics. Test a 2,000-point route under drag, zoom, hover, profile refresh and multiple saved routes. Instrument source.setData frequency, SVG DOM replacements, queryRenderedFeatures, layout/reflow, profile cancellation and chart rebuilds. Maintain transient drag preview independently from persisted route geometry; commit a final change only on pointer release. Consider segment spatial indexes and precomputed cumulative distance/ascent arrays for large profiles, but preserve exact selection statistics and GPX export.

### WEB-13 — Cold start and static modules [P2; CONFIRMED structure]
public/viewer.mjs imports the ~1.09 MB vendored MapLibre module and many application modules before importing app.js. Heavy gap/south catalogues are already lazily loaded through imagery-catalog.mjs; do not accidentally make them eager. Measure JS parse/evaluate, network waterfall, first visible coarse image and worker startup on an uncached phone. Consider lazy-loading optional diagnostics and optional renderer modules if initial evaluation is material. Avoid huge monolithic refactors just to save a small startup delay. public/assets contains thousands of image files; test build/deploy time and CDN cache headers separately from browser startup.

### WEB-14 — Potential future z>19 luma terrain bug [P2; LATENT, NOT CURRENTLY REPRODUCED]
terrain-view-worker.js uses d.x >> 1 / d.y >> 1 to identify a z18 parent for any d.z > 18 and selects an offset using d.x % 2. This is valid for a z19 child, but not for z20+; the shift should depend on d.z - 18, and sampling/crop coordinates would need the corresponding scale. Current luma traversal is capped at z19, so do NOT claim this causes current visual errors. Add an explicit z<=19 assertion or implement/test general ancestor sampling before raising luma maximum zoom.

## 4. Website: delivery, imagery, caches and storage

### WEB-15 — Decode and encoded-body copies [P1; CONDITIONAL]
tile-cache.js, response-cache.js and regional-imagery.js deliberately give independent consumers byte snapshots so worker transfer cannot detach shared buffers. Preserve this invariant. Measure allocated bytes and copies per tile at the boundary between fetch, response.arrayBuffer, worker transfer, PNG composite, MapLibre decode and GPU upload. Use immutable shared references only where transfer ownership is not violated; do not replace safe copies with unsafe zero-copy. Limit simultaneous full RGBA intermediates and release ImageBitmap/OffscreenCanvas resources promptly. Browser workers are not free: duplicated decoded caches can outweigh extra parallelism.

### WEB-16 — Special imagery composition [P1; CONFIRMED structure]
regional-blend.js performs official hard-mask compositing with decoded LINZ/Esri/fine-survey images, canvas operations, optional colour compensation, then lossless PNG encoding. This is necessary for exact source boundaries. Prioritise caching finished composites and avoiding redundant parent Esri decode/crops where source identity and mask metadata permit. Consider a worker pool only if composite queue time dominates; preserve official mask pixels, no smoothing, and no generic imagery substitution. Any alternate PNG/WebP format must be pixel-validated and honour alpha/premultiplication and colour grading. A faster but visibly different boundary is a regression.

### WEB-17 — Source-aware overzoom and tile reuse [P1; EXPERIMENT]
At imagery zooms above the actual provider ceiling, the renderer may create child images from a lower-zoom parent (Esri is capped at z18 in this code). Reuse one parent GPU texture with per-child UV transform when there is no new source detail and no child-specific mask/composite. For boundary tiles and high-resolution LINZ surveys, preserve exact source routing and child pixels. The same principle applies to DEM source overzooming. Measure duplicate bytes, decoded surfaces and GPU allocations before implementing; MapLibre's native overzoom and source cache may already avoid some duplicates.

### WEB-18 — Cache tiers, freshness and invalidation [P1; CONFIRMED structure]
Cache tiers include browser HTTP cache, CacheStorage, encoded RAM caches, MapLibre tile caches, out-of-view source pools, RTT drapes and (in Capacitor) native OkHttp/SQLite. They do not share one memory budget. Build a per-tile trace with stable identity, source revision, compressed byte hit/miss, decode, GPU residency and RTT draw. Preserve Cache-Control/Age semantics, negative-result expiry, provider fallback and independent subscriber cancellation. Invalidation must distinguish content changes, source-key changes, style/grade changes and geometry-only changes. Existing 0.14–0.18 work already addressed several redundant lookups and invalidations; compare against those reports before adding caches.

### WEB-19 — Source retry/backoff and fairness [P2; CONFIRMED structure]
tile-cache.js retries 429/5xx with Retry-After; tile-retry.js and provider-health.js separately handle visible errors and DEM fallback. Confirm there is no retry amplification between layers when the same upstream source fails. Add metrics for retries by stage, provider, response status, cancellation, revalidation and useful successful pixels. Avoid retrying 401/403 indefinitely. Preserve complete fallback coverage while LINZ is unavailable.

### WEB-20 — Imagery overviews and geographic data [P2; CONFIRMED]
Low-zoom South Island overviews and gap catalogues are precomputed; the index avoids loading large catalogues for irrelevant regions. Keep range-addressable packs and avoid eager loading all imagery or copying all overview bytes to app storage. Verify data provenance, source-version metadata, colour-grade consistency at zoom transitions, exact boundary masks and zoom0–12 overview coverage. An optimisation that lowers source resolution or changes grade across zooms violates product goals.

## 5. Native Android/Rust renderer — deeper architectural opportunities

### NAT-01 — Correctness before speed [P0; CONFIRMED open acceptance]
native-wgpu/README.md and validation/quality-work/checks.json explicitly leave physical-phone FPS, visual parity and mixed-LOD real-terrain seams open. Skirts were removed by request. Test real adjacent DEM tiles with different grid LODs, missing children, parent fallback, steep pitch, high exaggeration and near-cliff silhouettes. Edge stitching must align both topology and actual shared elevation samples; geomorph where necessary. Do not silently restore generic skirts, stretch imagery onto cliffs, or hide cracks with flat colours. A host Vulkan/GLES smoke pass is not proof of phone parity.

### NAT-04 — Persistent GPU texture pools/arrays [P1; EXPERIMENT]
renderer/src/lib.rs currently creates per-image 256x256 RGBA8 textures with nine mip levels, per-height 129x129 R32Float textures, associated views/bindings and dynamic draw state. residency.rs coordinates retained payload bytes and allows images to borrow unused geometry allowance. Prototype fixed-capacity texture_2d_array layers for images and heights with a free-list, LRU/generation IDs, explicit fence-safe reuse and per-patch layer indices. Validate adapter texture-array limits, formats, mip generation, Android Vulkan/GLES compatibility and memory pressure. This alone reduces allocation/bind churn; it does NOT automatically reduce draw calls. To batch draws, compatible index topology, texture bindings and instance buffers must also be shared. Keep the existing renderer as an A/B fallback. Record texture create/destroy, bind changes, draw calls, GPU memory, CPU submit time and p95 frame time. No reliable 5–30% FPS prediction exists without those measurements.

### NAT-05 — Terrain geometric-error hierarchy [P1; EXPERIMENT with confirmed missing metadata]
renderer/src/selection.rs selects a bounded NZ quadtree using conservative elevation bounds, projected tile size and a focus preference. renderer/src/adaptive.rs simplifies based on resident terrain error, but that information is not used as precomputed request-planning metadata. Generate a compact versioned quadtree of min/max heights, geometric error and dataset availability from real LINZ DEM tiles. Project geometric error into screen space and refine when error exceeds a calibrated pixel threshold; maintain a focus floor and hysteresis. Use conservative bounds for unavailable metadata and do not assume smooth valleys are always safe to simplify. Test every source seam, steep pitch, overzoom and camera rotation against the original visible coverage.

### NAT-06 — Decouple DEM source resolution from render mesh resolution [P1; EXPERIMENT]
Native terrain can select render geometry through z22 even though source DEM data is capped at z18. Currently DemSampling prepares a new 129x129 heightfield per render tile, including overzoomed regions. Some resampling improves mesh interpolation but adds no new source information. Prototype retaining source DEM once with UV transforms and sampling it from multiple child patches, or a hybrid source-tile/geometry-tile cache. Account for halos, interpolation, nodata, parent fallback and exact border agreement. GPU vertex texture fetches can cost more than precomputed heightfields on old mobile hardware: benchmark both on real devices. Do not replace all nationwide terrain with triangle meshes or voxel terrain.

### NAT-09 — Index atlas lifetime and adaptive variants [P1; CONFIRMED]
renderer/src/lib.rs maintains an index atlas keyed by terrain rectangle and edge/LOD variants. When it cannot fit new ranges, it clears/repopulates the mapping; overflow may create temporary buffers. Instrument atlas resets, cache misses, uploaded index bytes, temporary buffers and time in index generation during camera movement. If churn is material, use canonical precomputed patterns, a persistent allocator with LRU/segmented eviction, or a bounded set of stable variants. Avoid creating unique topology for minor camera changes. Validate exact triangle coverage, winding, culling and seams.

### NAT-10 — Mipmap work and image upload [P1; CONFIRMED]
renderer/src/mipmaps.rs generates eight lower mip levels for each image using GPU compute/render passes or a CPU fallback on unsupported adapters. The correctness fallback is deliberate; retain it. Batch compatible mip operations, reuse GLES temporary targets, and avoid generating mips for duplicate/unchanged resident tiles. Instrument mip time separately from texture creation, queue.write_texture and GPU completion. Precomputed mip pyramids are a possible disk-cache experiment but require source/version/colour/alpha consistency. Do not remove mipmaps: distant texture aliasing would worsen.

### NAT-07 — Spatially indexed incremental route draping [P1; CONFIRMED]
renderer/src/routes.rs loops over route segments and terrain cover, samples/clips the segment against each region, then recreates a route vertex buffer on rebuild. Complexity scales with segments x cover size; route selection and waypoint changes can also trigger geometry work. Build a spatial index from route segments to intersected tile bounds; cache draped segment meshes by route revision and terrain source/version. Rebuild only changed segments/tiles, and keep waypoint highlight separate from persistent path geometry. Validate exact line positioning, route editing, terrain arrivals and selected-point visuals. This is likely important for long GPX tracks.

### NAT-11 — More efficient bitmap path [P1; CONDITIONAL]
NativeImagery.kt decodes to Bitmap, uses IntArray pixels, may create additional Bitmap/Canvas copies for boundary composition, and TileLoader.kt loops over 65,536 pixels to premultiply into a direct ByteBuffer. Profile bitmap decode, crop, composition, getPixels, RGBA conversion and allocation separately. A fast path for opaque, unchanged LINZ tiles might copy/convert directly into a native-compatible buffer, with pixel-verified channel ordering and alpha semantics. Keep current exact composite path for official boundaries and fine-survey masks. If 2 image workers are CPU-saturated, test 3 or 4 before undertaking JNI/native decoder rewrites.

### NAT-12 — GPU rendering resolution and shading [P2; EXPERIMENT]
Native renderer currently renders at full surface resolution and applies image grade without dynamic sunlight/shadow/AO. A controlled render-resolution scale (e.g. 0.75–1.0 under measured GPU pressure) may help fill-rate-limited devices but sacrifices sharpness; use hysteresis and retain full resolution when stationary. Subtle directional hillshading from actual DEM normals can improve terrain shape, but adds vertex/fragment work and must not change source imagery. Shadows and SSAO are much more expensive; postpone until baseline frame time and terrain correctness are established.

### NAT-13 — Unified CPU/GPU memory controller [P1; CONFIRMED partial accounting]
The GPU residency target counts retained image/height payloads, not all device memory. Kotlin simultaneously holds decoded DEM rasters, ready direct buffers, composed image bitmaps, encoded bodies, prepared disk metadata and pending uploads; Rust holds CPU heightfields, index/instance buffers, GPU mip/depth/swapchain resources. Build separate actual/estimated counters and a coordinated pressure controller. Release speculative work and duplicate CPU representations first; protect demanded/ancestor coverage. Do not increase GPU image count to 512 or 1024 without checking device memory and graphics driver allocation failures.

### NAT-14 — Demand/coverage ownership and JNI traffic [P2; CONDITIONAL]
TerrainView.kt and TileLoader.kt exchange source-plan buffers, resident IDs, upload demand and camera snapshots. Cached plans, coalesced frames and duplicate suppression are already implemented. Instrument JNI calls, copied bytes, time per plan and cache hit/miss before moving more planning into Rust. Avoid splitting authoritative source ownership between Kotlin and Rust in ways that create duplicate requests or stale GPU uploads. A versioned immutable plan packet per camera revision is preferable to multiple loosely coupled mutable sets if this becomes complex.

### NAT-15 — Native route profiles [P1; CONFIRMED]
TileLoader.kt:411-424 samples the route at fixed DEM zoom 14. Provide a fast coarse profile followed by progressively refined real-source DEM elevations where available, with a visible resolution/accuracy indicator. Group profile samples by DEM tile, reuse cached rasters and cancel stale revisions. Never infer high-resolution elevation from interpolation alone or silently use imported GPX heights as ground truth.

### NAT-16 — Native UI feature parity [P1; CONFIRMED incomplete migration]
The native app is not yet feature-equivalent to the Capacitor planner. Maintain a feature-by-feature matrix for route editing/insertion/undo, hover elevation graph, GPX import/export, saved routes, multiple routes, topo 2D, peak/hut/route labels, source boundaries, offline packs, GPS, shading, cache controls and phone gestures. A fast renderer without these features does not meet the goal of an almost identical native replacement. Keep Android view inflation/layout and accessibility profiling separate from GPU benchmarks.

## 6. Data, offline and backend

### DATA-01 — Stable offline resource identity [P1; CONFIRMED]
native-wgpu TileLoader builds provider URLs containing an API key, and TileTransport ResourceKey hashes the URL plus online/offline mode. PreparedTerrainCache namespaces also include the key. Key rotation can therefore fragment caches or cause saved resources not to match current requests. Design canonical resource IDs: provider + dataset + z/x/y + processing variant + source revision; store credential identity separately for network authentication and respect per-user privacy. Migration must preserve existing saved regions and avoid accidentally treating unauthorised cached resources as reusable. Test key rotation, source-version changes, offline-only mode and region deletion.

### DATA-02 — Persistent offline transfers [P1; CONFIRMED]
Native region downloads are run by a single-thread executor owned by TileLoader and cancelled/shut down with the activity. Use a persistent manifest with per-tile completion, resumable checksums, bounded concurrency, pause/cancel, retry/backoff and Android-appropriate background transfer mechanisms. Avoid unbounded 2,000-tile memory lists for very large future regions; stream tile plans and progress to durable storage. Verify provider terms, data attribution, storage estimates and failure recovery. In Capacitor, an existing separate native downloader has bounded producers; do not regress it.

### DATA-03 — R2 content-addressed range service [P2; CONDITIONAL]
lib/lidar-storage.mjs serves content-addressed file descriptors and Range reads over R2 chunks, with a small descriptor cache and sequential chunk streaming. This is reasonable for large overview packs and private checkpoint data. Instrument descriptor-cache hits, R2 get count/range, chunk boundary crossings, TTFB, bytes delivered vs requested, 206 correctness, ETag/expiry and abort propagation. If small tile-range requests routinely cross chunk boundaries or incur repeated R2 round trips, consider chunk layout changes or bounded coalescing at pack-generation time. Do not disable content hashes or turn owner-private checkpoint endpoints into unauthenticated public writes; platform authentication is described as external to this handler and must be independently verified in deployment.

### DATA-04 — Provenance and visual fidelity [P1; CONFIRMED requirement]
Keep exact LINZ/Esri source routing, official hard masks, raw aerial pixels except documented grade/composition, and genuine DEM. Track source zoom, capture/version, fallback reason and geographic coverage per tile. Avoid generic cliff textures, blurred survey boundaries, silent source downgrades, artificial elevation or changing colour grade at far zoom. For steep overhangs, heightfields fundamentally cannot represent multiple elevations per XY; only add hybrid precomputed photogrammetry/LiDAR meshes in small validated regions with suitable oblique imagery and correct provenance. Do not impose a nationwide irregular mesh system.

### DATA-05 — Security, privacy and operational resilience [P2; REVIEW ITEM]
The client-visible LINZ key is intentionally public in the existing product, but it must not be treated as a secret or logged unnecessarily. Confirm upstream quota, key rotation, API allowlists, cross-origin policy, saved-route privacy, offline region ownership and diagnostic redaction. Do not assume that lib/lidar-storage.mjs itself authenticates requests: its comment says platform dispatch authenticates them. Verify that claim in the actual deployed routing configuration before exposing storage. Distinguish checkpoint storage from public imagery delivery.

## 7. Testing, build reproducibility and diagnostics

### OPS-01 — Continuous build/artifact delivery [P1; CONFIRMED]
The audited native-rust-wgpu branch has no .github/workflows entries. Add a GitHub Actions workflow (on a separate commit) that builds the native app from a pinned JDK/SDK/NDK/Rust/Gradle environment, runs focused Kotlin/Rust tests and publishes a downloadable debug APK artifact. Keep large optional overview packs outside the minimal test build unless explicitly requested. Report build provenance, source commit, APK checksum and whether live device tests ran. GitHub Actions cannot access uncommitted Codex workspace artifacts; it must build committed source or receive an explicitly uploaded artifact.

### OPS-02 — Metrics required to make worker tuning meaningful [P0]
Add a low-overhead diagnostic export, opt-in, with monotonic stage timestamps and byte counts. Distinguish:
- Camera/frame: vsync, CPU main-thread duration, render-thread duration, GPU timestamp where supported, presented FPS/p50/p95/p99, long tasks and thermal status.
- Selection: tile candidates, visible leaf count, selection passes, DEM bounds calls, RTT invalidations and active source revisions.
- Network: queued/active by provider and priority, per-host streams, connection reuse, cache hits, TTFB, transfer time, status/retry, bytes/sec and abort-to-stop latency.
- Decode: queued/active count, wait time, actual CPU or wall time, bitmap allocations and peak decoded bytes.
- Composition: queue wait, mask load/rasterisation, bitmap decode, canvas composite, PNG encoding and worker count.
- GPU: texture creations, bind changes, mip passes, uploaded bytes, upload queue depth, draw calls, triangles, RTT passes, index-atlas resets, GPU memory estimates and device-lost events.
- Memory: JS heap, WebView native RSS/PSS, Kotlin heap, decoded RAM, Rust CPU buffers, GPU allocations, CacheStorage/SQLite/disk budgets and eviction counts.
- Useful outcome: first complete coarse coverage, first sharp coverage, texture ready-to-drawn delay, revisit hit rate and holes/seams.

Do not add expensive diagnostic loops to every frame by default. Sample at a bounded cadence, retain a fixed ring buffer and make export explicit.

### OPS-03 — Benchmark matrix [P0]
Freeze a baseline APK/site build and provider fixture set. Use the SAME camera paths, imagery grade, pixel ratio, terrain exaggeration, layer visibility, cache state and network conditions for every comparison. Run cold and warm separately:
A. Aoraki close-up: pitch 58/80, near-cliff rotation, mixed z18/z22 terrain, sharp imagery and mixed LOD seams.
B. South Island overview: z8–12, zoom transitions and cached range reads.
C. West Coast/Fiordland official LINZ/Esri boundary: exact mask/composite, multiple parent crops.
D. Long GPX route: 2,000 points, drag/insert/hover/zoom, profiles and labels.
E. Revisit: rotate away/back repeatedly; record redundant network/decode/upload and time to sharp pixels.
F. Network constrained: 60 Mbps with realistic latency and 429/503 injection; offline-only and cancellation.
G. Device classes: low-memory Android, mid-range Android and desktop Chromium. Record SoC, WebView version, backend Vulkan/GLES, RAM and thermal state.

Minimum 5 matched runs per condition; report median and p95 with raw traces, not only best-run FPS. Stop an experiment if p95 frame time, thermal stability, memory pressure, cache correctness, pixel output or provider errors regress even if average FPS rises.

### OPS-04 — Quality invariants / regression tests [P0]
Keep and extend the existing large checks/ and native-wgpu tests. Required invariant categories: exact source pixels/masks, correct terrainRGB decoding, DEM seam equality, no mixed-LOD cracks, full parent fallback coverage, non-overlapping tile cover, route draping/picking, tile cancellation without stale GPU upload, independent consumers, byte-cache freshness, offline key migration, source revision invalidation, WebGL context restoration, worker termination, no duplicate cache ownership, profile correctness and correct GPX round-trip. Real device visual acceptance is separate from synthetic software Vulkan/GLES and host CPU tests.

## 8. Proposed controlled concurrency experiments

| Experiment | Change one variable | Hypothesis | Primary win condition | Stop condition |
|---|---|---|---|---|
| W1 | MapLibre workers 2 -> 4 | Faster vector parse/symbol layout | Lower overlay-enabled parse backlog without p95 frame regression | Main thread or worker CPU contention increases |
| W2 | Image network admission 8/12 -> 12/16 -> 16/24 | Better cold delivery throughput | Earlier useful image with bounded queue bytes | More decode wait, 429s, frame stalls |
| W3 | Decode cap 12 -> 4/6/8 independently | Avoid decoder oversubscription | Lower p95 decode wait + main frame cost | Decode backlog or memory explodes |
| W4 | Split network and decode semaphores | Prevent finished network bodies blocking delivery slots | Faster cold cover at same memory and quality | Unbounded encoded queue or cancellation regression |
| W5 | Regional worker pool 1 -> 2 -> 3 | Parallel boundary composition | Lower composite p95 on boundary scene | Mask-cache duplication, memory or frame regression |
| W6 | Luma terrain workers 1 -> 2 | Parallel DEM CPU prep | Lower terrain queue wait with shared DEM inputs | Duplicate halo fetches / memory spikes |
| W7 | Predictive 0/2/4, DEM pending 2/4 | More useful near-screen tiles | Higher revisit/turn hit rate without visible delay | Speculative traffic displaces foreground |
| N1 | Native CPU prep 2+2 -> 3+2 -> 4+3 | Decode/prep bottleneck | Lower ready-queue delay, stable frame time | Thermal, memory or GPU queue grows |
| N2 | Native foreground 8/12 -> 12/16 -> 16/20 | Network bottleneck | Lower first-complete-cover latency | Per-host 16 cap, errors, CPU contention |
| N3 | Native idle upload cap 650 KiB -> 1/2 MiB | GPU upload queue bottleneck | Faster sharp-ready time with stable p95 | GPU backlog or missed vsync rises |
| N4 | GPU texture array backend toggle | Resource binding/creation bottleneck | Fewer allocations/draw-state changes, same pixels | GLES incompatibility or shader slowdown |

**Do not change all parameters simultaneously.** Separate network admission, CPU decode, composition workers, terrain workers, GPU uploads and GPU rendering. Workers increase parallelism only for work actually executed on independent threads; they cannot remove GPU saturation or the browser main-thread MapLibre selection cost.

## 9. Recommended execution sequence and handoff contract

**Phase 0 — Establish baseline (no quality changes):** Build current website/Capacitor/native sources; preserve commits and existing checks; capture device traces and a representative source/visual snapshot. Report exact tested commit and backend. If a device is unavailable, explicitly mark performance questions unverified.

**Phase 1 — Low-risk instrumentation:** Add queue-time/active-time metrics for MapLibre delivery vs decode, native local/network/CPU/GPU stages, RTT/draw work and memory pressure. Validate that instrumentation changes do not itself materially alter frame time.

**Phase 2 — Website image pipeline:** If WEB-01 is supported by measured queue time, split delivery and decode admissions with a bounded encoded-body queue. Run W2/W3/W4 independently. Do not start with 96 image requests. Verify source-cache reuse, cancellation, pixel identity and loaded-cover continuity.

**Phase 3 — Worker count:** Run W1/W5/W6 only in workloads where the corresponding worker is demonstrably busy. Do not add regional workers for ordinary non-boundary LINZ imagery. Keep a low-RAM fallback and limit duplicated worker caches.

**Phase 4 — Native pipeline:** Run N1/N2/N3 independently; focus on upload throughput and decoder CPU before increasing GPU residency. Protect foreground during gestures and maintain the latest-camera frame/backpressure policy.

**Phase 5 — Rendering architecture prototypes:** Implement GPU texture-array pooling, persistent index atlas and error-driven terrain LOD behind feature flags, one at a time. Preserve the existing shared-grid heightfield engine as a reference. Compare Vulkan and GLES, then real device. No unconditional full rewrite.

**Phase 6 — Correctness/product:** Fix cross-LOD seams, progressive route profiles, incremental route draping, offline stable identities/background downloads and native UI parity. Introduce hybrid high-detail meshes only in data-rich mountain regions after source validation.

**Phase 7 — CI and release:** Add a reproducible GitHub Actions APK artifact workflow, versioned benchmark results, regression gates and clear distinction between host synthetic tests and actual device acceptance.

### Definition of done for any optimisation
1. A short statement of observed bottleneck, before/after counts and exact measurement method.
2. A bounded implementation with rollback or feature toggle for risky changes.
3. Source and generated-build parity where applicable, including Capacitor asset generation.
4. Tests for cancellation, lifecycle, stale callbacks, source revision, seams and pixel accuracy relevant to the change.
5. Same-scene device metrics: p50/p95 frame time, useful tile latency, memory, GPU uploads and errors; no invented FPS claims.
6. One atomic commit with implementation, tests and a concise validation note. Avoid unrelated refactors and retain incremental checkpoints.

## 10. File index for the implementing AI

**Website entry and renderer:** public/index.html; public/viewer.mjs; public/app.js; public/performance.js; public/adaptive-terrain.js; public/terrain-visibility.js; public/terrain-composition.js; public/native-residency.js; public/raster-residency.js; public/maplibre-direct-sampling.js; public/selection-interval.js; public/motion-detail.js; public/prepare-ahead.js; public/terrain-streaming.js.

**Website requests/workers:** scripts/maplibre-image-queue.js; scripts/patch-image-scheduler.py; public/loading-controls.js; public/terrain-scheduler.mjs; public/tile-cache.js; public/response-cache.js; public/regional-imagery.js; public/regional-scheduler.js; public/regional-imagery-worker.js; public/regional-blend.js; public/terrain-core.js; public/terrain-worker.js; public/terrain-data.js; public/terrain-view-worker.js; public/imagery-preload.js; public/provider-health.js; public/tile-retry.js.

**Website data/UI:** public/imagery-catalog.mjs; public/imagery-catalog-index.js; public/nz-overview.js; public/topographic-overlays.js; public/route-interactions.js; public/route-geometry.js; public/elevation-chart.js; public/imagery.js; public/terrain-patches.js; public/imagery-diagnostics.js; public/bottleneck-diagnostics.js; public/tile-trace.js; public/resource-ledger.js.

**Capacitor and backend:** capacitor/mobile/native-transport.js; capacitor/mobile/mobile.js; capacitor/scripts/build-web.mjs; capacitor/PERFORMANCE-0.18.0.md; capacitor/LOADING-OPTIMISATIONS.md; capacitor/MAIN-THREAD-OPTIMISATIONS.md; lib/lidar-storage.mjs; build/sites-worker.ts; scripts/imagery/; tools/lidar_pipeline/.

**Fully native Kotlin:** native-wgpu/android/app/src/main/java/nz/ridgeline/nativeapp/TileLoader.kt; TileTransport.kt; HttpTileBackend.kt; NativeImagery.kt; DemSampling.kt; PreparedTerrainCache.kt; GpuUploadQueue.kt; TerrainView.kt; RouteController.kt; OfflineStore.kt; OfflineUi.kt; PlannerUi.kt; ElevationProfileView.kt.

**Fully native Rust:** native-wgpu/renderer/src/lib.rs; android.rs; selection.rs; residency.rs; adaptive.rs; mipmaps.rs; routes.rs; terrain.wgsl; routes.wgsl; native-wgpu/core/src/heightfield.rs; surface.rs.

**Prior validation:** native-wgpu/validation/quality-work/; native-wgpu/validation/pipeline-cache/; native-wgpu/validation/cache-revisit/; native-wgpu/validation/gestures-loading/; capacitor/PERFORMANCE-0.18.0-benchmarks.json; checks/.

## 11. External technical references

- MapLibre worker count: https://maplibre.org/maplibre-gl-js/docs/API/functions/setWorkerCount/
- MapLibre parallel image requests: https://maplibre.org/maplibre-gl-js/docs/API/functions/setMaxParallelImageRequests/
- Chrome runtime profiling: https://developer.chrome.com/docs/devtools/performance
- Android GPU Inspector: https://developer.android.com/agi
- Android background work: https://developer.android.com/develop/background-work/background-tasks/persistent
- WebGPU/wgpu texture arrays: https://docs.rs/wgpu/latest/wgpu/struct.TextureDescriptor.html

**Bottom line:** The strongest near-term website experiment is separating image delivery from decoder admission, not blindly increasing MapLibre workers. The strongest native experiments are staged CPU preparation concurrency, idle GPU upload throughput, and stable GPU resource pooling. The most important architectural correctness work remains terrain error-driven LOD and mixed-LOD seams. Every claimed performance gain requires matched device evidence.
