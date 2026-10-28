[Download 0.18.0 Full APK](Ridgeline-3D-0.18.0-Performance-Full.apk) · [Validation](Ridgeline-3D-0.18.0-validation.json) · [Source commit](https://github.com/connorgleeson736-sys/Ridgeline/commit/d880143418ac46ece98adff0b3b2fbaedf044d46)

# Ridgeline Performance 0.18.0 Full — selection CPU audit

28 October 2026 (Melbourne). Reference: the refreshed `android-preview-download` branch at `160cd38d44e2c7440fb52c998fc245ccf8b186f2`, its Full validation record, and the actual 0.17.0 Full APK, SHA256 `6e40929e6697ea9b158235f8fa780192331649bdc268be2d00666f7bd8752294`. Source baseline: `12da664ea164083341b2feb0d8df4b7b4f741b44`.

The user reports **no noticeable improvement in 0.17.0**, with both panning and imagery refinement slow. The previous 10–30% planning estimate did not establish a device improvement and should not guide expectations. **0.18.0 has no measured phone FPS or loading-time improvement yet.** This pass addresses three different, frequently executed paths inside selection/preparation rather than further tuning the earlier fallback-child index.

## New changes

### 1. Repeated numerical integration inside native tile selection

MapLibre's `hE` LOD policy calls two ten-sample trigonometric integrals per candidate tile. Within a covering traversal, their inputs are constant: field of view, vertical camera distance and range. The candidate's horizontal distance varies, but those integrals do not depend on it. This also affects the app's custom imagery, DEM and rendered-terrain policies, which wrap the native policy.

The policy now retains only its last invariant inputs/results. Field-of-view changes recompute the exponent, first integral and half-angle term; vertical-distance or range changes recompute the second integral and correction. Every tile still calculates its own distance-dependent result. Arithmetic order is retained; this is not an approximation or a new LOD rule. Non-finite inputs remain supported as before.

Verification against the actual shipped engine: **57,512 exact policy comparisons**, including changing camera quantities, signed zero and non-finite cases; **64 complete native selection scenes produce identical ordered tile IDs** across portrait/landscape, four pitches, four zooms and two policies.

### 2. Temporary tile objects in DEM ancestor lookup

`TerrainTileManager.getSourceTile` repeatedly used `id.scaledTo(level).key`. That constructs an overscaled ID and its canonical ID, then discards both after extracting their key. Visibility bounds, terrain selection and elevation preparation call this path frequently. The exploratory Chromium CPU capture independently showed `getSourceTile`, `scaledTo` and their lookup children consuming substantial time.

The method now uses the engine's existing `calculateScaledKey(level, true)`. It retains the same key cache, lookup order, wrap/overscale behavior, zoom bounds, cache touches and fallback decisions. No elevation samples or min/max bounds change. **2,500 randomized lookup scenarios** compare object results and every lookup key/order against the shipped engine. A sparse 512-query fixture eliminates **8,192 temporary overscaled IDs**, plus their canonical-ID allocations. Fully resident direct hits save less work.

### 3. Rebuilding identical terrain texture projection matrices

The regular terrain-coordinate method built a temporary matrix and a final Float32 matrix for each related source/terrain pair every preparation. Those texture projection matrices depend on the relative canonical tile coordinates; they do not depend on the live camera, source pixels or DEM heights.

A shared, bounded memo retains **at most 128 matrices / 8 KiB of matrix payload**, plus keys and map metadata. Cache misses run the original native matrix operations. Every returned coordinate still receives a fresh cloned ID and independent Float32Array, so callers cannot mutate a cached matrix or another caller's result. Live camera projection and the custom tile-range method remain unchanged. **600 randomized comparisons** verify exact matrices, parent/child, wrap and overscale behavior, then mutate outputs and verify subsequent results remain correct.

The benchmark includes 0.17.0's geographic prefilter in both versions. Comparing against an unfiltered old method would exaggerate the benefit; that comparison is not used here.

## Measured work reductions

Warmed, alternating cloud CPU batches, nine rounds; medians below. Each script loads its baseline engine from the actual 0.17.0 Full APK and checks correctness before timing. Final runs use the generated application engine, not a separate prototype.

| Measured subsystem and batch | 0.17.0 | 0.18.0 | Local speed ratio |
| --- | ---: | ---: | ---: |
| LOD arithmetic, 4,096 candidates sharing camera quantities | 3.183 ms | 0.496 ms | 6.42× |
| Sparse DEM parent lookup, 512 queries | 0.884 ms | 0.391 ms | 2.26× |
| Regular projection with existing prefilter, 16 images / 64 terrain patches | 0.077 ms | 0.054 ms | 1.43× |

These ratios **cannot be added or multiplied to predict app speed**. The matrix change saves a small absolute amount in this fixture. Real benefit depends on candidate count, DEM residency, device CPU/driver and what currently limits the frame. No new whole-app percentage estimate is offered. Full samples: [benchmark record](Ridgeline-3D-0.18.0-benchmarks.json).

## Trace interpretation and coverage of this review

The supplied report remains useful for prioritisation: roughly 100.7 ms mean movement frames, 183.8 ms p95, LINZ selection about 22 ms mean / 50.5 ms p95, terrain selection about 11.3 / 26.3 ms, and texture preparation about 6.4 / 15 ms. The image queue had 15 active / 33 queued jobs; decode elapsed p95 was about 442 ms. These are inclusive or asynchronous measurements; they are not additive CPU totals or pure decoder execution times.

The original system trace shows renderer-main CPU saturation, substantial WebView I/O activity and kernel reclaim. It has no JavaScript sample stacks or useful GPU duration samples. It cannot identify the exact functions responsible on the phone, establish a leak, or show that the unchanged GPU workload is cheap. No new phone trace was supplied for 0.17.0. The new cloud CPU capture is exploratory, with controlled provider responses and software WebGL; it locates code to examine, not phone bottlenecks or presented FPS.

| Area examined in this pass | Finding / action |
| --- | --- |
| Native frustum traversal / LOD | Repeated integrals found and fixed. Same ordered requested tiles; no traversal throttling or detail change. |
| DEM bounds / parent resolution | Temporary parent-ID construction found and fixed. Existing conservative measured-peak bounds preserved. |
| Terrain texture preparation | Repeated tile-local matrices found and fixed. Existing coordinate prefilter, fingerprint checks, continuity holds and native camera matrices preserved. |
| Duplicate DEM source updates | The native render path can update the DEM source twice. Not skipped: the first update can restore/evict cached DEMs and affect later bounds. A safe future deduplication needs a complete invalidation contract. |
| Raster retention / ancestor fallback | 0.17.0's indexed descendants, failed-parent memo and allocation reductions remain. No further speculative cache expansion. |
| Direct drawing / overlays | Existing cached Direct plans, empty-overlay checks, native composition fallback and shader dispatch reviewed/tested. Shader compilation/driver calls appear in the cloud capture; removing validation or changing shaders without device evidence is not justified. |
| Image delivery / decoder admission | Native streaming, encoded caches, cancellation, decoded-resource ownership and independent stage timing remain. Queue/decode elapsed time includes scheduling; no unsupported claim of pure network or decoder cost. |
| Encoded body copies | Some response snapshots and subscriber copies remain for independent consumers and transferable worker ownership. Removing them indiscriminately could detach/corrupt cached data. No unproven zero-copy rewrite. |
| Regional imagery composition | Existing worker ownership/cancellation and full-quality blending retained. Original report showed no regional composition backlog at export. Worker/browser composition parity checks pass. |
| Native HTTP dispatcher / disk cache | Reviewed current gateway, shared streaming body, lookup/miss memo and completed-transfer fix. Existing code retained; no evidence supporting another increase in transfer concurrency. |
| Full overview reading | All six verified packs remain uncompressed for native range reads. No first-launch extraction, recompression or imagery substitution. |
| Memory pressure | No larger imagery/DEM/drape budgets. Temporary-ID and matrix-construction work reduced. OS reclaim may still limit the app; no device memory-pressure improvement is claimed. |
| UI, routes, diagnostics | Existing coalesced persistence and route behavior preserved; full browser workflow passes. Diagnostics remain opt-in. |

This is a substantial additional audit, not proof that all possible optimisations have been exhausted. The largest unresolved measurements are phone GPU/driver time, native/WebView delivery cost and current JavaScript stacks under the user's workload.

## Quality and regression verification

- New guarded patch scripts reproduce the final engine and provenance bytes exactly from the linked 0.17.0 source and can each be rerun without changes.
- New equivalence fixtures described above pass against the final generated engine.
- **55 mobile JavaScript tests** and **17 focused tests against source + 17 against generated modules** pass.
- Existing native/JVM suite: **90 tests, zero failures/errors**. Android assembly/lint pass; instrumentation APK compiles. Native classes are byte-identical to 0.17.0 Full. No device/emulator execution.
- Native Direct drawing, terrain residency/continuity, tile unloading, terrain composition and 72-scene phone-LOD policy checks pass. These CPU fixtures use controlled terrain/GPU objects where stated.
- Chromium application workflow: **25 actual bundled image-range reads**, **300 controlled provider requests**, zero page/console errors; route editing/merging, offline controls, Topo and Direct rendering pass. Software WebGL and mocked Android controls are not device performance tests.
- Real-worker browser transport, priority/cancellation, clones and **eight pixel-exact composition comparisons** pass.
- The only changed web assets versus the actual 0.17.0 Full APK are the engine, its provenance and build-info. All other web modules, workers, detail policies and controls are byte-identical. Seven checked native shader definitions are identical.
- **All 1,139 image/binary assets**, including all six packs, are byte-identical. All **5,344 original overview images** remain included at the same resolution. Overview coverage is South Island, Rakiura and nearby islands at zooms 0–12; elevation and finer imagery still require the relevant region downloads.
- Final APK assets match the synced application. APK v2 signing and ZIP alignment, including 16 KiB page alignment, verified. Outside web assets, only the Android manifest changed for versioning; native bytecode/resources are identical.

## Install and reproduce

**Ridgeline 3D Performance**, package `nz.ridgeline.mobile.performance`, version **0.18.0-performance**, code **21**. Full APK includes all prior 0.17.0 fixes and the three changes above. Same Performance signer; install over Performance 0.17.0 to keep its settings, routes, saved regions and cache. The normal Ridgeline app remains separate. Earlier cumulative fixes: [0.17.0 report](Ridgeline-3D-0.17.0-findings.md), [Full imagery verification](Ridgeline-3D-0.17.0-Full-findings.md).

Use the Performance app's existing inspection capability (`chrome://inspect/#devices`) for a 20–30 second Performance recording with JavaScript samples enabled. Match view/detail/layers and warm the same route before comparing. Measure loaded panning separately from entering new coverage; do not clear caches or change quality between matched runs. A current phone recording and exported report are needed to identify what remains slow and to establish whether this update helps.

```sh
# From repository root; baseline must be the published 0.17.0 Full APK.
RIDGE_PERFORMANCE_SOURCE=capacitor/www node checks/lod-invariants.mjs BASELINE.apk lod.json
RIDGE_PERFORMANCE_SOURCE=capacitor/www node checks/dem-parent-keys.mjs BASELINE.apk dem.json
RIDGE_PERFORMANCE_SOURCE=capacitor/www node checks/terrain-matrix-reuse.mjs BASELINE.apk matrices.json

# Exploratory CPU sampling, not an FPS benchmark. The optional baseline override
# changes only the engine; all other generated application modules remain fixed.
RIDGELINE_DIAGNOSTICS=/tmp/ridge-current node capacitor/scripts/profile-selection.mjs
RIDGELINE_PROFILE_BASELINE_APK=/path/to/BASELINE.apk RIDGELINE_DIAGNOSTICS=/tmp/ridge-baseline node capacitor/scripts/profile-selection.mjs
```

Build with the existing JDK 21 / SDK 36 / build-tools 35.0.0 / Gradle 8.13 environment: `RIDGELINE_BUNDLE_OVERVIEWS=1 npm run sync --prefix capacitor`, then the `assemblePerformance`, `testPerformanceUnitTest`, `lintPerformance` and `assemblePerformanceAndroidTest` tasks. Preserve the Performance signing key outside source control.
