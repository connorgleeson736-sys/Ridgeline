> RETIRED: historical implementation notes. Cesium is removed from production. See [THREE_VIEWER_STATUS.md](THREE_VIEWER_STATUS.md).

# Selectable Cesium viewer

Status: implemented and validated; production build passed. This source milestone is ready for publication. The Sites version/deployment attached to this commit is the authoritative publication receipt.

## User experience

Open Map layers → Terrain renderer → Original or Cesium. Original MapLibre remains the default; Cesium is downloaded only after selection. Both use the existing LINZ imagery and Terrain-RGB service. No custom LiDAR meshes, AI imagery, World Terrain, ion account, or national preprocessing is involved.

Routes, drawing state, saved sketches, GPX and profile computations are shared. Renderer switching preserves the camera target, zoom and heading. Controls include peak navigation/search, imagery choice, natural colour, subtle terrain shading, labels, relief, low power and session API-key replacement. Cesium's 2D option is a top-down globe view without terrain relief, not a separate flat-map projection.

The original remains visible while Cesium's library, datum grid, centre elevation and first terrain frame prepares. A failed start leaves Original usable. Normal operation has one active viewer; there is a brief overlap while preparing the replacement. Switching back destroys Cesium's viewer, worker, controls and listeners, then recreates the original map. Preferences stay in shared application state; Original is the default after a full page reload.

## Architecture

- `public/cesium-renderer.mjs`: lazy loader, native Cesium terrain provider, UI adapter, imagery and shared-route rendering. Uses real Cesium RequestScheduler integration for cancellation and source backpressure. No private Cesium APIs in application code.
- `public/cesium-terrain.js`: pixel-centre-aware Terrain-RGB decoding, shared boundary resampling, geoid interpolation, bounded fetch/cache engine.
- `public/cesium-terrain-worker.js`: worker request, transfer and cancellation protocol. Local fallback uses the same code if worker initialization fails.
- `public/app.js`: shared planner and renderer switch. `public/performance.js` supplies the existing measured resolution governor to either adapter.
- `public/assets/cesium/`: official CesiumJS 1.146.0 distribution; npm integrity and every served vendor asset hash in `provenance.json`.
- `public/assets/nzgeoid2016-nz.{json,bin}`: centimetre-quantized extract of the original LINZ NZGeoid2016 grid distributed by PROJ. Bounds 165–180° E, 33–49° S; 1-minute source spacing. Source and extracted SHA256 hashes are recorded. Display converts NZVD2016 heights by h = H + N; profiles keep their original unexaggerated values. This is not a plate-motion or survey-grade reference-frame transformation.

Terrain uses 257×257 edge-aligned nodes resampled from 256×256 centre-sampled rasters. Neighbour reads are deduplicated; every shared edge uses the same samples and geoid evaluation. Cesium supplies terrain skirts/LOD handling. Maximum source zoom is 18, imagery 19. Coverage and child masks prevent endless refinement outside the NZ region or beyond source detail. Missing source tiles can use coarser LINZ ancestors; failed requests never synthesize rock detail. Outside the viewer's NZ extent the provider supplies no high-resolution terrain.

Worker raw decoded cache: 24 MiB, or 16 MiB on reported ≤4 GB devices. At most 6 source fetches (3 with Save Data) and 8 requested terrain jobs. Encoded tiles reuse the existing profile CacheStorage namespace, with a 64-item limit and 24-hour expiry. Browser/Cesium native caches handle imagery. Cesium terrain cache: 96 tiles normally, 64 on reported low memory, 48 Save Data. Screen-space terrain target: 1.5 pixels normally, 3 in low power; this is an engine LOD control, not a verified bound on DEM measurement error.

No per-frame hillshade texture or new cliff material: optional subtle derivative-based shading overlays the authentic photo. Rendering is requested on scene changes, hidden tabs pause the default loop, and labels are throttled. Rendering effects and source imagery remain different from MapLibre, so this is not yet an equal-quality performance benchmark.

## Validation

- `node checks/cesium-terrain.cjs`: RGB decoding, NaN handling, geoid interpolation, pixel-centre alignment, bit-identical E/S tile borders, fetch deduplication, concurrency/cache bounds, cancellation and actual worker protocol.
- `node --experimental-vm-modules checks/cesium-renderer.cjs`: runs the shipped browser bundle's actual Cesium math, availability, RequestScheduler cancellation, heightmap CPU tessellation and material construction. Runs the actual planner module and dynamic imports; checks switch/repeated teardown, failed-start recovery, routes/storage, API-key replacement, layers and 2D/3D/power controls. The WebGL Viewer and DOM are mocked, so this does not verify actual GPU rendering.
- Existing base viewer/performance/elevation/bootstrap checks, profile worker success and failure paths, and R2 storage contracts pass.
- `node checks/base-production.mjs`: verifies both engines' production asset hashes, lazy initialization and absent retired mesh datasets.
- Authentic source QA: 32 original LINZ tiles at z16/z18, 2,097,152 valid source samples, 1,028 exact shared-edge comparisons with 0 m heightmap edge difference. Two actual Cesium heightmap meshes tessellated. Maximum sampled decoded mesh height difference versus supplied heightmap: ~0.107 m. Cesium's GPU height quantization and skirts mean identical input tile edges alone do not certify perfectly invisible rendered seams. These are source/CPU checks, not terrain survey accuracy or FPS measurements. See `CESIUM_SOURCE_VALIDATION.json`.

## Reproduce authentic source checks

```sh
python tools/fetch-cesium-check-data.py /absolute/scratch/cesium-source-qa
CESIUM_QA_TILES=/absolute/scratch/cesium-source-qa node --experimental-vm-modules checks/cesium-renderer.cjs
```

Download preparation requires Python, numpy and Pillow. Raw input files are reproducible QA fixtures, not deployed datasets. The script resumes existing original PNG downloads and never prints the API key.

## Recovery and publication

Project: `appgprj_6acc83afb6588191aff43dc542d98b5f`, existing Sites repository. Keep the owner-private audience and retained R2 binding. The Sites source helper owns commit/push/packaging. Use `node /root/.codex/plugins/cache/openai-curated-remote/sites/1.0.0/scripts/build-site.mjs`, then production checks and source-helper packaging. See `BASE_VIEWER_STATUS.md` for the previous baseline and deployment details.

Outstanding verification: supported control-browser capability is unavailable in this environment; no preview server or alternative browser was started. Actual browser visuals, WebGL worker execution, terrain/imagery refinement and laptop/Chromebook FPS require device testing. Do not claim a measured speedup. A future quantized-mesh service is a separate project, not part of this heightmap renderer.


## Startup correction — 15 October 2026

A user reported that selecting Cesium never produced a visible viewer. Server logs contained no Worker errors. Live LINZ elevation requests at zooms 0–4, 8 and 12 all returned HTTP 200 and full finite-alpha tiles, so those checks did not establish a missing-root-data problem.

Two confirmed switch defects were found: the hidden viewer waited for `globe.tilesLoaded` (all high/medium/low queues empty), and render-loop failures during preparation were only sent to a callback that suppressed them while Original was active. The former can delay a usable partially loaded view; the latter hides the useful failure reason. The exact failure in the user's browser has not been observed.

The replacement gate accepts a rendered frame with terrain available at the camera target, allowing background refinement. Fatal render errors reject startup immediately and retain Original. Startup stages and persistent failure text are visible in Map layers. The whole startup, including module loading, has a 45-second cancellable deadline. Selecting Original during preparation cancels without losing the current map. Cache versions were advanced.

Regression checks now deliberately keep `tilesLoaded=false`, exercise unavailable target terrain followed by available terrain, inject render-loop failures, cancel preparation, and trigger the complete-startup deadline. Previous mocks always reported all tiles loaded, which missed this state. Actual browser GPU verification remains unavailable.
