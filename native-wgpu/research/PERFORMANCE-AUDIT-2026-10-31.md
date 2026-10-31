# Native terrain/loading performance audit — 31 October 2026

Source baseline: `native-rust-wgpu`, `3cc6f903670d6796f04adc5201c112b067469172`.
The remote Android download README was refreshed: its reference remains Capacitor
0.18.0, source `d880143418ac46ece98adff0b3b2fbaedf044d46`. This work changes the
Rust/wgpu prototype, not that APK. The initial MapLibre investigation was removed
from the working changes after the user identified the native branch.

## Measured changes

All numbers below are host measurements, not A20 measurements. Loading, mesh
preparation and rendering are different workloads; their gains cannot be added.
Raw samples, ablations and correctness results are in
[`../validation/skirt-performance/`](../validation/skirt-performance/).

| Change | Before → after | What this establishes | Loading-speed effect |
| --- | --- | --- | --- |
| DEM halo and axis lookup reuse | 1.600 → 0.583 ms median per complete mesh, 100 alternating samples | 2.74× decoded-memory mesh throughput; removes 66,564 temporary tile-ID constructions and associated hash lookups per mesh. Reuses axis wrapping, tile-column selection and interpolation weights. | Real local LINZ files + PNG decode + complete mesh: 13.579 → 13.009 ms, 40 alternating samples, **4.2% less time**. Excludes HTTP, adaptive profile and GPU upload; decoder is host ImageIO, not Android BitmapFactory. |
| Ordered shared-edge sweep | 4.550 → 0.593 ms median, 40 alternating samples, 512 edges | 7.67× in the controlled line-matching fixture. Each of 256 shared joins is sampled once instead of twice. Eliminates quadratic candidate matching and per-edge interval allocations/sorts. Actual DEM sampling remains exact. | Reduces CPU when new resident terrain changes coverage. No network/decode speedup or end-to-end loading measurement is claimed for this change. |
| Exact-rectangle fragment program | GLES shader-only drained-frame medians improved 14.9%, 8.1%, 10.0% at 0°, 60°, 75° | Removes fragment `discard` only when mesh geometry already clips exactly; permits, but does not force, early depth rejection of hidden textured terrain/skirt fragments. | No download/decode improvement. Extra pipeline construction is a startup cost that is not isolated in the frame measurements. |
| Compatible array-instance grouping | 16 → 12 draws; 22 → 4 texture binding changes at 0°; 15 → 11 draws at oblique angles | Fewer actual draw submissions, including different skirt masks arriving in interleaved texture pages. Separate textures skip sorting because their bindings cannot batch. | No measured loading-speed gain. Vulkan batching-only timing is mixed: 15.6% lower at 0°, **7.1% higher** at 60°, 0.8% higher at 75°. It is not established as a universal frame-time win. |

Combined GLES drained-frame medians improved 9.5%, 3.0%, 9.5%; combined software
Vulkan improved 15.8%, 1.7%, 2.1%. There were only three alternating trials per
configuration and substantial host variance. These are debug-build wall times for
eight frames including submission and GPU completion, not isolated GPU timestamps.
Triangle counts, photography resolution, terrain samples, skirt depth and
adaptive error thresholds remain unchanged. Batching-only hashes match the frozen
baseline for every tested angle/backend. Fewer draws alone do not prove faster FPS.

The shader path is not pixel-identical to the old discard path. On the 512² GLES
fixture, 0° changes six background pixels to terrain; 60° changes one non-background
pixel; 75° changes none. Floating interpolation at exact clip boundaries previously
discarded some edge fragments. The new mesh-bounded path removes that rejection.
No new background pixels occurred in that comparison. This is a small boundary
rasterization difference, not a claim of general visual parity. Sub-cell imagery
(photo region more than seven zoom levels below its source geometry) retains
the original fragment clipping; the GPU regression explicitly checks for spill.

## Audit coverage and findings

This pass inspected the native source chain and its tests, rather than treating
historical website optimizations as native fixes. The table records concrete
findings and dispositions. It does not claim every source line was exhaustively
verified or that all candidates below are implemented.

| Area / source | Finding, implication and disposition |
| --- | --- |
| `renderer/src/seams.rs` | Continuity work was already gated by cover changes/`seams_dirty`, not run unconditionally each frame. Same-source edges already bypass sampling. The quadratic line scan and double testing were real; replaced by a sweep over the disjoint cover. Missing coverage or any failed equality still retains skirts. Mixed-LOD, missing-neighbor and discontinuity cases match the frozen pairwise oracle. |
| `renderer/src/lib.rs` cover rebuild | Every geometry insertion marks seams dirty; an insertion can resample otherwise unchanged joins. **Candidate:** a bounded continuity cache keyed by immutable DEM generations and exact edge interval. Never cache by tile ID alone: eviction/reload can change data. Measure streaming cover-rebuild time before introducing additional retained state. |
| `core/src/surface.rs`, `core/src/adaptive.rs` | Photo ownership is independent of geometry; complete photo subdivision removes internal photographic walls. Rounded-out tiny photo patches really need fragment clipping. Removing all skirts, shortening them globally, or culling one face would not be an equivalent optimization. Existing full boundary/error policy retained. |
| `renderer/src/lib.rs` draw encoding | Native quadtree order only batched accidental adjacent matches. Grouped array page bindings, exact rectangle, skirt mask and adaptive step before building instances. Compatible pipeline layouts are explicit; otherwise wgpu's automatically inferred layouts are exclusive to each pipeline. |
| `renderer/src/terrain.wgsl` | All faces used the clip/discard program, even for exact grid boundaries. Added separate entry point sharing the same grading/sample code. No forced early-depth extension or depth-only pass. The shader still performs trilinear/anisotropic photographic sampling on visible vertical faces. |
| Index patterns / `index_atlas.rs` | Sixteen edge masks fragment patterns; full current-frame overflow still creates temporary index buffers. Existing allocator protects visible ranges and coalesces free spans. **Candidate:** share surface index ranges separately from edge ranges. This may reduce cache churn but adds draw calls; benchmark both sides rather than unconditionally splitting every patch. |
| Overdraw / depth | Two-sided textured skirts remain capable of high fragment cost at steep angles. **Candidate:** compare coarse front-to-back depth bins against binding-first order; add asynchronous GPU timestamps/fragment counters where supported. CPU submission timing is not overdraw measurement. A full depth prepass doubles geometry processing and may lose on a tile GPU; it needs A20 evidence. |
| `texture_arrays.rs`, `residency.rs` | Layer leases are generation-safe and pages are bounded. Payload budget and allocated page bytes differ: empty slots do not destroy an array page. **Candidate:** retire completely empty pages on explicit memory pressure, with lease/submission checks. Report page allocation separately from live payloads. This can save memory but may make the next load slower. |
| `renderer/src/adaptive.rs` | Error bounds, full boundaries, hysteresis and route-exact sources already reduce geometry without changing height textures. Selection still projects eight prism corners per distinct visible source. **Candidate:** cache only under an exact camera/DEM/profile/route revision; a zoom-only key would be incorrect. No detail reduction added. |
| `DemSampling.kt` | Four taps per 129² node repeatedly constructed IDs and searched a hash map. Replaced with small dense halo slots and precomputed x taps; preserves operation order, signed zero, real-parent fallback, dateline wrapping, polar clamping and cancellation per row. Tests compare all output bytes against the frozen branch implementation. |
| `TileLoader.kt` raw DEMs | Footprint admission, decoded-memory reuse and source freshness already exist. The follow-up shares overlapping CPU PNG decodes by source ID and content fingerprint (see measurements below). The new mesh work does not eliminate decoding. Real local measurements show decode/I/O dominates; do not advertise 2.74× as full loading speed. Overlapping decode sharing is now implemented; broader persistent decoded-image sharing remains a candidate. |
| `PreparedTerrainCache.kt` | Validated derived terrain/composition caches already bypass substantial preparation. Reads previously decompressed the entire payload before checking dependency freshness. Implemented header-first validation (measurements below); accepted payloads still validate the entire compressed stream and CRC, then copy into a direct buffer. **Candidate:** compare direct/chunked decode. Do not catch continuation/cancellation exceptions as corrupt-cache errors. Measure cold miss, fresh hit and stale hit separately. |
| `NativeImagery.kt`, `SourceGeometry.kt`, `PixelPacking.kt` | Inputs are discovered before worker suspension; saved overview ranges avoid full-pack extraction; masks/plans and bulk RGBA packing already exist. Boundary composition still decodes/crops/copies multiple full 256² images. **Candidate:** bounded decoded-source sharing or precomputed validated composition for repeated boundary requests; key by attachment/source generation and freshness. Avoid changing mask math or introducing lossy encoding. |
| `TileTransport.kt`, `HttpTileBackend.kt` | Local lookups use their own executor; shared futures, cancellation grace, freshness and foreground/background limits already exist. `pump()` still repeatedly filters/ranks all queued jobs while holding a monitor. **Candidate:** one allocation-free best-job scan before a heap rewrite; compare admission latency with 1/64/512 queued requests. Increasing concurrency can increase decode/GC pressure rather than loading throughput. |
| `TileTransport.kt` fingerprints | Full encoded bodies are hashed for provenance. Needed for exact derived-cache validity; removing hashes without a trusted content identity would risk stale terrain. Measure bytes hashed and repeated fingerprints before memoizing identity. |
| `GpuUploadQueue.kt`, `UploadAdmission.kt` | Uploads are bounded by count/bytes, measured CPU cost and backlog; parent restoration has priority. First upload can exceed the allowance, so a single slow mip operation can still stall a frame. **Candidate:** separate immutable staging/mip submission work and time actual completion before changing admission. Keeping every upload in one frame would improve apparent load time by sacrificing interaction. |
| `TerrainView.kt`, `renderer/src/android.rs` | Dirty/vsync scheduling, nonblocking GPU backlog polling and cached camera-safety revisions already exist. No perpetual idle drawing loop was introduced. Global JNI renderer locking and camera-safety queries deserve device trace attribution before parallelization: surface lifetime and GPU ownership must remain serialized. |
| `mipmaps.rs` | CPU/render/compute paths and startup readback validation already protect known driver failures; GLES scratch reuse exists. CPU fallback allocates each mip and issues multiple texture writes. **Candidate:** reusable CPU mip storage or packing multiple prepared mips into staged copies, with byte-identical ties-to-even/alpha results. Never force GPU mips on a driver that fails startup validation. |
| `camera_safety.rs`, picking, routes | Heightfield traversal avoids a GPU depth readback. Safety reuses unchanged poses; routes are redraped after relevant cover changes and camera uniforms are reused. Keep these protections. A spatial cover index may reduce repeated source scans for safety/profile queries; measure route-heavy workloads before adding one. |
| Kotlin UI / routes / profiles | Profile drawing still walks samples on redraw; hover uses a binary search. Offline region size queries aggregate stored resource lengths. Cache the rendered profile path by size/sample revision and region summaries by mutation revision if traces identify them as UI hotspots; they do not establish terrain-loading improvements by themselves. |
| Offline storage / overview attachments | Durable saved resources are separate from disposable caches, overview descriptors are reused and reads are positional. Preserve this ownership. No cache-size increase, region deletion or provider substitution is needed for the implemented changes. |

## Highest-value next experiments

1. Measure Android bitmap decode, boundary composition, DEM mesh/profile, JNI upload,
   mip generation and first-present latency on the same cold/warm camera replay.
   The host local-load result indicates decoder/preparation reuse has more remaining
   upside than arithmetic-only mesh tuning.
2. Test shared decoded halo/source preparation with immutable source fingerprints.
   Neighbor geometry requests reuse the same original DEMs; concurrent duplicate
   decode is a plausible large saving but is not established by this audit alone.
3. Compare source-generation continuity caching and mask-independent surface
   indices under rapid mixed-LOD streaming, including index overflow.
4. A/B depth-bin ordering, binding ordering and the unclipped program on the A20.
   Record GPU time, CPU submit time, loading completion, texture bytes and thermal
   state. Current host results do not establish a breakthrough in whole-app speed.

## Research used

Fetched/read during this pass:

- [Pinned MapLibre 6.14 terrain source](https://github.com/maplibre/maplibre-gl-js/blob/v6.14.0/src/render/terrain.ts): native skirts and the logical-zoom skirt-depth formula. Matching this formula does not make the custom renderer identical.
- [WGSL specification](https://www.w3.org/TR/WGSL/#discard-statement) and its [source](https://github.com/gpuweb/gpuweb/blob/main/wgsl/index.bs): discard semantics and derivative control flow. Removing discard permits an implementation optimization; the specification does not promise a timing gain.
- [wgpu 30.0.1 RenderPass](https://docs.rs/wgpu/30.0.1/wgpu/struct.RenderPass.html): pipeline/bind-group/instance draw contracts. Actual validation caught and corrected an attempted reuse of exclusive inferred layouts.
- [WebGL performance guidance](https://developer.mozilla.org/en-US/docs/Web/API/WebGL_API/WebGL_best_practices): batching, blocking operations, texture uploads and flush boundaries; relevant as background for the reference GLES renderer, not a substitute for native profiling.

## Reproduction and validation

`tools/benchmark-skirts.py --output <directory>` builds the frozen baseline,
shader-only, batching-only and combined programs before running alternating trials.
Use `VK_ICD_FILENAMES` to select software Vulkan if required. `skirt_bench` uses
synthetic full-detail terrain, interleaved array-page arrivals and 0/60/75° views.
Its times include GPU drain and are deliberately not called device FPS.

Run `cargo test --locked --manifest-path native-wgpu/Cargo.toml --workspace`.
Run the ignored `benchmark_sweep` test explicitly for edge-sweep timings.
`DemHaloPerformanceTest` includes a frozen baseline and full-byte comparisons;
set `RIDGELINE_PIPELINE_FIXTURE` to the genuine LINZ fixture directory and
`RIDGELINE_BENCH_OUTPUT` to retain its local-loading and CPU samples.

For Android tests generate Base assets with `RIDGELINE_BUNDLE_OVERVIEWS=0 node
native-wgpu/tools/package-assets.mjs` and source goldens with
`node native-wgpu/tools/source-goldens.mjs` first. Generated assets were missing
in this checkout initially; that caused one full-suite failure, then was repaired.
Android unit tests and lint pass. ARM64 and ARMv7 Rust library compile-checks pass.
GPU regression outputs and final test counts are retained with the raw results.
No A20 run, device thermal measurement, installed APK validation or published APK
is claimed by this source-level change set.


## Follow-up: deeper loading-path changes

The user requested continued investigation and tangible measurements for each
additional change. These are independent A/B fixtures, not additive speedups.
The final recorded run differs from earlier runs reported during progress:
local loading improved 4.2% here versus 6.6% in an earlier run. Sharing reduced
pair completion time 1.2% here versus 3.5% earlier. This variance is retained
rather than selecting the most flattering result.

| Additional implemented change | Recorded before → after | Evidence and limits |
| --- | --- | --- |
| Leading-edge demand planning (`PlanCadence.kt`, `TileLoader.cameraChanged`) | **64.396 → 14.070 ms**, 78.2% lower first/idle-demand fixture time | The previous scheduler always imposed 50 ms, even after idle. First/idle demand now has zero intentional delay; movement still starts at most one plan every 50 ms, with latest-camera coalescing. Forty alternating host scheduled requests read genuine LINZ PNG files, decode and build a complete mesh. The same optimized sampler runs in both arms. This excludes native viewport planning, HTTP, adaptive profiling, GPU upload and first presentation. It is not a 78% whole-app loading claim. |
| Validate prepared-cache header before decompression (`PreparedTerrainCache.kt`) | Rejected stale RGBA entry **1.811 → 0.0225 ms**, about **80×** faster; accepted fresh entry 2.635 → 2.643 ms | One hundred alternating reads of a genuine 256² image-derived payload. False validation skips payload decompression and allocation. Fresh hit time is effectively unchanged (+0.3% in this run). Cancellation/continuation exceptions propagate without deleting good entries; accepted payloads still check size, full data and CRC. Invalid gzip construction now closes its underlying file descriptor. No gain applies when there is no stale derived entry to reject. |
| Share overlapping original DEM PNG decodes (`SharedDecode.kt`, `TileLoader.rawRecord`) | **120 → 60 decodes**; two-reader completion **3.598 → 3.553 ms**, 1.2% lower | Sixty alternating pairs of local source reads. Matching source/fingerprint readers share one immutable CPU result; network ownership/freshness stay separate. Finite rasters can share their arrays; missing-pixel repair makes a private copy. In-flight entries disappear on success/failure and do not add a persistent decoded cache. The follower briefly waits for a CPU-only decode; producers must never do I/O or wait for other preparation. This halves duplicate decode work in the overlap fixture, but does not establish a large wall-time win or halve all app decoding. |

Tests for these changes cover initial/idle/moving cadence, matching versus changed
source identities, shared-result ownership, error recovery, lack of persistent
retention, validator suspension, stale rejection, fresh restoration and CRC failure.
`PlanCadenceTest`, `SharedDecodeTest`, and `PreparedCacheReadTest` save their raw
measurements when `RIDGELINE_BENCH_OUTPUT` is set. Their source fixtures use the
same `RIDGELINE_PIPELINE_FIXTURE` directory as the DEM benchmark.

The most promising demonstrated latency reduction is removing the idle 50 ms
planning delay. Stale derived-cache rejection has a large conditional saving.
Neither finding proves the cause of the A20's drawing slowdown. Device decode,
mipmap and overdraw attribution remains the next critical measurement.


## Checkpoint follow-up: exact CPU mipmap fallback

Continued investigation found repeated UNORM byte-to-float division in every
channel of the CPU mip fallback. `core/src/mipmap.rs` now uses an exact 256-entry
normalization table and adjacent row slices; `renderer/src/mipmaps.rs` calls that
kernel. Addition order, float scaling, ties-to-even rounding, alpha treatment,
all eight lower mip levels and original level-zero pixels are preserved. This
is deliberately not an integer averaging approximation.

The genuine LINZ 256² RGBA release-host fixture measures **0.427 → 0.332 ms**
median per complete chain: **22.3% less CPU time**, 1.29× throughput, 100
alternating samples of ten chains each. Both arms use the same allocations.
See `cpu-mip-chain.json`. This excludes PNG decode and GPU texture submission;
it only helps loads using the CPU fallback, not drivers that use validated GPU
mips. No whole-app or A20 loading gain is inferred from it.

Validation compares all mip bytes for genuine photography and 32 complete
random/extreme/gradient/alpha chains. Each normalization entry is checked against
the former arithmetic. Actual Vulkan, GLES and GLES-array render regressions
also pass with CPU mip generation forced. Startup GPU/array mip validation and
its driver fallback policy are unchanged. Reproduce the timing with release
`ridgeline-core`'s ignored `benchmark_genuine_chain`, `RIDGELINE_MIP_FIXTURE` set
to `tile0.rgba` and `RIDGELINE_MIP_BENCH_OUTPUT` set to an output JSON path.

New checkpoint packaging uses version code 2 / `0.1.1-performance-checkpoint`,
the same `nz.ridgeline.nativeapp` identity and existing debug signer. The Base
build retains all 67 coarse imagery files and supports the original six attached
ZIPs; it does not bundle those large packs or a managed-session CA. Final package
hash, exact source commit, ABI/signature/alignment checks and test results are
recorded separately with the published checkpoint.


## Continued search: replace the quadratic drawable-cover join

A further critical-path audit found `surface_cover` comparing every resident
terrain region to every resident photographic region. A small source count can
still expand into thousands of disjoint cover regions when distant parents fill
siblings around scattered fine descendants. This made the cross-product costly
on new-tile arrivals, even though the plan cache already avoids unchanged work.

The implementation now merge-joins **exact integer Morton intervals** in the
existing quadtree order. Disjoint regions are skipped once, overlapping ancestor
photos remain available for subsequent terrain regions, and missing photographic
coverage still keeps its original perimeter skirts. No spatial approximation,
cache retention, texture budget or resolution policy is introduced. Ordered
`SurfacePatch` values match the frozen nested-loop implementation, including
sparse photos, mixed depths, fallback ownership, input reversal and the world root.

Release host CPU, complete cover construction including both resident-cover
builds, 60 alternating samples per case (`surface-join.json`):

| Sources per pool | Terrain/photo regions | Former scan | Merge join | Speedup |
| --- | --- | --- | --- | --- |
| 17 | 304 / 328 | 0.237 ms | 0.087 ms | 2.72× |
| 65 | 1,048 / 1,048 | 2.124 ms | 0.302 ms | 7.04× |
| 161 | 2,356 / 2,185 | **9.398 ms** | **0.705 ms** | **13.34×** |

This is a substantial reduction in the CPU work that prepares new drawable
terrain/imagery patches. It is a synthetic fragmented-source workload, not a
13× network-loading, frame-rate or A20 claim. Reproduce with the ignored release
core test `benchmark_surface_join` and `RIDGELINE_SURFACE_JOIN_OUTPUT`. The
unchanged source/plan cache still matters: no saving is expected for work that
was already skipped on a stable frame.

## Exact tile bounds; rejected culling experiment

`TileId.bounds` now constructs the exact inverse power of two from IEEE-754 bits
instead of repeated exponentiation and four divisions. All 256 zoom values and
262,144 coordinate/extreme cases preserve all result bits. Across 1,200 complete
ordered terrain/image plans the selected tiles are identical. The release
changed-camera fixture measures **74.530 → 58.036 microseconds** for both plans,
22.1% lower CPU time (`selection-paths.json`). This is a small absolute host
saving, not a large loading claim.

An experimental AABB support-plane culler also matched the correctness fixtures,
but took 61.661 microseconds versus 58.677 for the bounds-only variant in its
three-arm run. It was removed. `selection-frustum-rejected.json` records that
experiment; only the simpler exact bounds calculation ships. Changing culling
semantics or adding complexity was not justified by the timings.
