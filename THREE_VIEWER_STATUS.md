# Ridgeline GPU terrain viewer — recovery and validation

Updated 2026-10-15. Project: `appgprj_6acc83afb6588191aff43dc542d98b5f`.
Checkout: `/workspace/sites/ridgeline-base-viewer`.
Production: https://ridgeline-nz.connorgleeson736.chatgpt.site

GPU replacement implementation complete; publication is tracked by the native Sites deployment result. This document and the benchmark JSON files travel with the committed source. Preserve the same project, owner-private audience and existing R2 checkpoint binding.

## Current behaviour

Original MapLibre is now the startup viewer, following the user’s latest choice. Three.js is loaded lazily only when explicitly selected; Original also remains the startup/runtime failure fallback. See BASE_VIEWER_STATUS.md for the newer imagery/cache work. Cesium and the old custom LiDAR viewers remain removed. Original routes, browser-saved sketches, GPX, profiles, API-key replacement, peak navigation, imagery sources, 2D/3D, relief and shading controls are shared.

**GPU displacement is the production terrain path.** CPU RTIN meshes are no longer generated for each tile. The previous RTIN algorithm and Martini are retained only in `checks/fixtures` for comparison, and are excluded from production.

**TAAU is off by default**, following the user's report that Three.js was substantially slower than Original. The default draws the terrain directly, without colour/depth/velocity render targets or full-screen temporal passes. TAAU remains optional in Map layers; its targets are created lazily and disposed when disabled. Output density is capped at 1.25× DPR and 1.5 million pixels (1× / 0.9 million in low-power mode). Source imagery and DEM detail remain available at the same maximum zooms.

## Architecture and performance changes

- Official Three.js 0.186.1 WebGPURenderer, with official WebGL2 fallback. Existing vendor integrity hashes remain unchanged.
- Source worker downloads/decodes edge-aligned LINZ Terrain-RGB into a Float32 heightfield. It calculates encoded normal textures and measured maximum geometric error at five regular grid levels, independently for each of four tile quadrants. It produces **no per-tile vertex or index buffers**.
- Vertex shaders displace reusable grids using nearest-filtered R32F height textures at exact source-node coordinates. No float-linear-filter extension is required. Geographic height scaling uses the actual Mercator row. Finest LOD retains every decoded sample; this does not imply survey accuracy or invent missing terrain.
- Five small quadrant grid geometries are shared across the whole scene. Each tile quadrant chooses its grid from measured local error and projected metres per pixel, with hysteresis. Close complex surfaces can retain the complete measured grid; smoother and more distant quadrants use fewer vertices. Normal and imagery textures retain fine shading independent of grid density.
- **One shared terrain material** uses Three's per-object `reference` texture bindings and object uniforms. Native checks verify separate elevation/imagery bindings, and 24 additional terrain objects reuse the existing compiled shaders. Do not replace these references with plain TextureNode `onObjectUpdate`: TextureNode setup can reset its update type and leave the first tile's texture bound.
- Parent quadrants remain until the child is ready. Covered parent quadrants are hidden objects, so they submit no terrain draw or fragment-discard work. Skirts cover spatial/LOD boundaries; no claim of perfect geomorphing is made.
- Frustum selection prioritises projected demand. Leaf targets are 48 normal / 28 low-power. Approximate triangle targets are 550k during movement, 850k at rest, 260k in low-power mode. The error budget adjusts when over target; it is not a guaranteed hard cap. Quadrants use their own camera distance. Stationary refinement resets the movement error budget.
- Three concurrent tile jobs, bounded decoded/prepared caches (24 MiB / 8 MiB), shared source requests, timeouts, cancellation and one upload per frame. Requests outside the current view are cancelled after a short grace period. Prepared edge grids are reused by zoom-19 children. Resident tile accounting targets 130/70 MiB; driver/browser/framebuffer memory is additional.
- Pointer picking ray-marches the cached authoritative heightfields, rather than intersecting hundreds of thousands of CPU triangles. Hover picking is skipped during camera movement and rate-limited. Profiles keep their separate existing authoritative sampler.
- Default rendering settles after a short window and issues no further GPU draws until needed. Visibility changes pause/resume drawing. Label updates are throttled. All source textures, workers, listeners, geometry and optional temporal targets are disposed on teardown.

Authentic LINZ imagery and measured elevation services are unchanged. No new satellite images, generic cliff textures, AI detail or elevation super-resolution have been introduced. Elevation requests stop at service zoom 18; terrain above that uses exact parent quadrants. Imagery can reach zoom 19. Service zoom is not proof of original 1 m LiDAR survey coverage. This remains a heightfield viewer, without overhang reconstruction or verified climbing routes.

## Evidence and limits

`GPU_TERRAIN_BENCHMARK.json` records warm medians for seven samples after three warmups in the same V8 process, using four authentic Mount Cook tiles at source zooms 16 and 18. The initial comparison measured approximately **3× faster CPU preparation**, with **55–72% fewer worker-transfer bytes**. It excludes network, image decoding and rendering, and does not establish device FPS. Do not cite the isolated-VM timings from `gpu-patches.cjs` as native worker latency; that check is for correctness.

`GPU_TERRAIN_NATIVE_VALIDATION.json` records **real native offscreen WebGPU rendering through Dawn and Mesa lavapipe**, not a browser. It verifies distinct imagery/elevation bindings, visible GPU displacement, native rendering and TAAU colour/depth/motion, resolution changes and history reset. Pixel checks identify separate coloured terrain areas and surface changes when measured displacement is flattened. These are synthetic source/material tests, not a screenshot of the hosted website or a hardware-performance benchmark. The null-device mode only validates shaders/descriptors.

`checks/gpu-patches.cjs` verifies winding, complete quadrant coverage, source error at every measured node for every LOD, finest-level zero source-node interpolation error, normals and worker payload shape. Geometry/height calculations are source-relative and subject to ordinary Float32 rounding on the GPU. Skirts are visual seam coverage, not measured features.

Remaining verification: supported-browser visual inspection, WebGL2 pixel tests, touch interactions and real laptop/Chromebook FPS, latency and process/GPU memory. Managed browser QA was unavailable in this session; do not install or substitute an unsupported browser path. Original remains the comparison/fallback. No claim is made that Three.js is now faster than Original on the user's device.

## Files and recovery

Main implementation:

- `public/three-renderer.mjs`: camera, tile selection, shared patch selection, source loading, planner adapter, optional TAAU and lifecycle.
- `public/gpu-terrain.mjs`: shared geometries, displaced TSL material, source textures and heightfield picking.
- `public/terrain-patches.js`: worker-safe source error and normal preparation; reusable topology.
- `public/three-terrain-worker.js` and `public/terrain-data.js`: cancellable source/cache pipeline.
- `public/app.js`: existing shared planner and renderer switch.

Run from the checkout:

```sh
node checks/base-viewer.cjs
TEST_WORKER=ok node checks/base-viewer.cjs
TEST_WORKER=fail node checks/base-viewer.cjs
TEST_BOOTSTRAP=1 node --experimental-vm-modules checks/base-viewer.cjs
node checks/base-performance.cjs
node checks/base-elevation.cjs
node checks/lidar-storage.mjs
node checks/three-data.cjs
node checks/gpu-patches.cjs
node checks/gpu-benchmark.mjs
node --experimental-vm-modules checks/three-renderer.cjs
node --experimental-vm-modules checks/three-planner.cjs
```

Authentic QA fixtures: `python tools/fetch-three-check-data.py /absolute/scratch/three-source-qa` (numpy/Pillow), then set `THREE_QA_TILES=/absolute/scratch/three-source-qa` for the source/benchmark checks. Source hashes are recorded in `THREE_SOURCE_FIXTURE.json`. Historical RTIN numeric validation remains runnable with `node checks/three-terrain.cjs`; it is not the production terrain generator.

Install optional `webgpu@0.6.2` in an external QA directory. Run `DAWN_MODULE=/absolute/qa/node_modules/webgpu/index.js node checks/three-graph.mjs` for null-device validation. `DAWN_BASIC_FEATURES=1` additionally checks without optional device features. For real pixel checks, select `DAWN_BACKEND=vulkan` with a supported software/hardware Vulkan ICD. In this session, Mesa 25.2.8 was unpacked into `/workspace/scratch/3dd2ae8bd0a2/mesa-qa`; set `VK_DRIVER_FILES` to its `usr/share/vulkan/icd.d/lvp_icd.json` and `LD_LIBRARY_PATH` to its `usr/lib/x86_64-linux-gnu`. Tests advance the native renderer's animation clock between simulated frames; otherwise frame-scoped temporal updates do not run after resets.

Use the Sites skill's `build-site.mjs`, then `node checks/base-production.mjs`. Native QA packages and fixtures are not website dependencies/assets. The Sites source helper owns final checks, commit/push and archive generation; pass fresh credentials through hidden stdin, never files or output. Publish its exact returned commit/archive to this existing owner-private Site, retaining any returned saved version/deployment IDs. Do not consider publication verified until a native terminal success result is returned. Never replace the R2 storage binding or the original viewer.
