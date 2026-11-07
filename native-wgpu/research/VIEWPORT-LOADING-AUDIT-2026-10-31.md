# Foreground tile loading and cache audit

Native baseline: `93be86cbfa54f80333850c85d99f7134b2b79308`.
Website reference: refreshed `main`, `c0b394125bc35f6236c645c68a4a3bb94d88ce55`.
Refreshed Android download reference: `39431dad230945919a7b7d9d0ef2e86db8492ead`,
which identifies Capacitor 0.18 source `d880143418ac46ece98adff0b3b2fbaedf044d46`.
This pass changes the separate native Kotlin/Rust/wgpu application.

## Causes and implementation

| Stage | Observed problem | Implemented change |
| --- | --- | --- |
| Rust HLOD request order | Refinement protected the surface focus, but final requests were ordered by tile-centre distance. A large tile covering the focus could rank behind a smaller neighbouring tile. | Preserve the selected quadtree cover; rank the focus and its neighbourhood first, then distance to tile bounds and projected size. |
| Fallback admission | Every distant fallback could precede every fine tile except the first. Thread pools start their first jobs immediately, before the priority queue can see later close requests. | Admit the focus backdrop, four nearby leaves, distant backdrops, then remaining refinements. Submit tasks in rank order, including initial worker admission. |
| GPU uploads | `peek()` always chose any ancestor ahead of fine uploads. Queue overflow evicted by arrival order, potentially discarding a close tile. | Carry the source rank through to GPU admission and inherit useful cached ancestors' closest dependent rank. Under pressure retain the highest-priority uploads. Reordering unchanged demand updates priorities without changing geography. |
| Disk-cache reads | Two cache workers used an executor FIFO; new foreground hits could wait behind all earlier speculative reads. | Priority-ordered local reads with stable ties and queued promotion. Local hits remain independent of network permits. Running storage reads are allowed to finish. |
| Obsolete transfers | Up to eight obsolete source jobs retained foreground priority for 600 ms. A single terrain job can own several DEM downloads. | Keep at most two returning jobs, demote their CPU work, and atomically allow parking only when their **actual active dependency transfers** fit the two background slots. Otherwise cancel the obsolete consumer immediately. Shared current consumers retain ownership. |
| Network dispatch | Each admitted transfer allocated/filter-ranked multiple copies of the pending job list under its monitor. | One allocation-free candidate scan per admission, stable rank ties, and an explicit total cap. Skip unchanged movement updates and redundant pumps on RAM hits. Foreground limits remain 8 moving / 12 idle, with two background slots. |
| Encoded retention | A completed prediction could evict useful foreground bytes. | Speculation retains bytes only below the existing cache's 90% fill threshold; foreground insertion retains normal LRU eviction. No cache budget increase. |
| Decoded DEM reuse | `loadGeometry` requested **every encoded halo input** before `rawRecord` could reuse fresh decoded data. An encoded LRU eviction could cause redundant disk/network work despite valid decoded heights. | Admit only missing/stale decoded inputs. Retain existing source fingerprints, dependency deadlines, parent-hole repair, and the later freshness recheck. |
| Readiness checks | Every query filtered source lists and allocated recursive child lists. Image arrivals also recreated terrain readiness state. | Per-pool immutable residency indexes; exact/ancestor queries use membership probes. Complete-child folding is lazy, and unchanged terrain/photo pools share their previous index independently. |
| Rust ownership | Every ideal request recursively filtered and allocated descendant vectors to prove complete coverage, including incomplete subtrees. | Fold complete child coverage once per ownership calculation. Emit the same finest full cover or partial descendants plus nearest real parent. Frozen reference comparisons cover partial/full, nested, reversed, duplicate and root inputs. |
| Cover construction | Every source rebuilt the already-known path from its ancestors to the root. Finest requested leaves were recalculated during every ownership/trim call. | Stop ancestor construction at an existing path. Cache finest ideal leaves per demand membership and canonicalize request membership before invalidating geographic covers. |
| Terrain seams | A newly loaded field caused unchanged neighbouring edges to be sampled again. | Cache continuity results by both immutable terrain generations, exact interval, axis and line. At most 8,192 proofs, no retained GPU resources. Changed generations or intervals revalidate; unknown/discontinuous boundaries retain skirts. |
| Repeated updates | Arrival notifications rebuilt unchanged upload ancestry/rank maps and requeued unchanged CPU priorities. | Reuse identical rank maps and task ranks; stop shared ancestor walks once an equal/better priority already covers their path. |

The intentional tradeoff is a smaller obsolete-work grace window under contention:
a rapid return may restart an uncached obsolete request. This frees foreground
capacity for the current view. Completed valid caches and shared live consumers
remain reusable.

## Detail sliders

**Offline / imagery** now starts with independent **Imagery detail** and **Terrain
detail** sliders. Both use the website/Capacitor seven positions from 0 to 1.5
zoom levels in 0.25 increments, labelled 1.0×–2.8×. They persist separately and
apply to visible and idle-preparation plans immediately.

Defaults remain 0.25: imagery target `256 / 2^0.25`, terrain target 431 logical
pixels. Terrain follows the reference's rounded `512 / 2^bias` target. Settings
are part of the immutable plan key; changing one cannot reuse a stale plan.
Existing source zoom ceilings, original image pixels, height samples, mip levels,
skirt policy, surface resolution and cache budgets are preserved. Higher settings
request finer tiles within the existing residency budgets; they cannot force more
source detail than the provider or tile budget can supply.

## Measurements

Raw alternating samples are in `../validation/viewport-priority-2026-10-31/`.
These are independent subsystem fixtures; their gains must not be added together
or presented as phone FPS, HTTP throughput, or end-to-end first presentation.

| Fixture | Baseline median | Changed median | Scope |
| --- | ---: | ---: | --- |
| New foreground disk-cache hit behind 64 low-priority hits | 70.171 ms | 2.289 ms | Host JVM, two initially busy readers, controlled 2 ms storage service time; 16 alternating trials. Demonstrates queue delay removal, not physical Android disk speed. |
| Rust source owners, 161 cached sources | 28.886 µs | 15.047 µs | Release CPU, complete owner selection including fallback and full-child proof; 80 alternating samples, 20 calculations/sample. |
| Exact resident cover, 161 sources | 142.069 µs | 117.720 µs | Release CPU, complete quadtree partition. Smaller 17/65 source cases also improve. |
| Repeat immutable 129-sample continuity check | 2.950 µs | 0.060 µs | Release CPU, cached repeat only. First-time joins still require real validation; full seam construction is not timed here. |
| 96 ancestor-readiness queries, 65 sources | 8.606 µs | 4.192 µs | Host JVM. Lightweight lazy-index construction 0.318 µs in this fixture; child-coverage folding is unused in this ancestor-hit case. |

The queued-cache fixture also verifies actual OkHttp disk reuse while all eight
foreground network slots remain blocked. This is separate from the controlled
service-time benchmark.

Rejected experiment: a hashed ancestor index for finding finest ideal leaves was
slower than the contiguous pairwise scan on the native 17/65/161-source sets
(3.639/9.392/21.869 µs versus 0.248/2.627/15.760 µs). The simple scan remains,
cached once per demand membership. Initial raw measurements, including that
regression, are retained in `coverage-index-rejected-finest.json`.

An eagerly built JVM child-coverage index also had poor construction cost in the
first experiment. The shipped implementation uses exact/ancestor probes first
and builds child coverage only when needed. Benchmark claims above describe the
final lazy version, not the rejected eager construction.

## Validation and practical limits

- Rust workspace: 39 passing tests; six explicit opt-in benchmarks ignored.
  Includes 500 mixed source-tree comparisons against the frozen native baseline,
  complete cover comparisons, exact default selection, camera projections and
  generation/interval-sensitive seam-cache tests.
- 34 focused Kotlin/JVM tests pass, covering local priority promotion, actual
  HTTP disk-hit admission, cancellation/shared ownership, DEM dependency parking,
  speculative retention, upload pressure/order, child coverage, settings/defaults,
  plan invalidation, shared decode and prepared-cache suspension/corruption.
- All production Kotlin and existing JVM test sources compile with Kotlin 2.2.21,
  JDK 21 runtime and Android API 36 classes. This session used the Maven
  `org.robolectric:android-all:16-robolectric-13921718` API jar to type-check the
  application without installing an Android SDK. This is not a Gradle/lint/APK build.
- Native GPU smoke passes with software Vulkan: exact grade, fine/coarse ownership,
  independent terrain/photo detail, parent backdrop with no coverage holes,
  mixed-LOD skirts, mip filtering and bounded residency. Synthetic terrain only.
- Website, Capacitor, earlier Android app, route planning and provider assets have
  no changes. No new APK, installed-device run, physical-phone timing or visual
  parity claim is made by this source checkpoint.

Reproduce focused Kotlin tests on a normal Android development setup:

```sh
RIDGELINE_PRIORITY_BENCH=1 RIDGELINE_BENCH_OUTPUT=/absolute/writable/results \
  native-wgpu/android/gradlew -p native-wgpu/android :app:testDebugUnitTest \
  --tests '*.PriorityPipelineTest' --tests '*.TileTransportTest' \
  --tests '*.GpuUploadQueueTest' --tests '*.SourceDemandTest' \
  --tests '*.ResidentCoverageTest' --tests '*.DetailPolicyTest' \
  --tests '*.DemandPlanCacheTest' --tests '*.PreparedCacheReadTest' \
  --tests '*.SharedDecodeTest'
cargo test --locked --manifest-path native-wgpu/Cargo.toml --workspace
RIDGELINE_COVERAGE_OUTPUT=/absolute/writable/results/coverage-index.json \
  cargo test --release --locked --manifest-path native-wgpu/Cargo.toml \
  -p ridgeline-core benchmark_coverage_index -- --ignored --nocapture
RIDGELINE_CONTINUITY_OUTPUT=/absolute/writable/results/continuity-cache.json \
  cargo test --release --locked --manifest-path native-wgpu/Cargo.toml \
  -p ridgeline-renderer benchmark_reused_continuity -- --ignored --nocapture
```

## Reference ideas and remaining experiments

The website's `terrain-streaming.js`, `surface-focus.js`, `tile-cache.js`,
`native-residency.js`, `raster-residency.js` and pinned MapLibre image admission
were inspected on `main`. The useful design is consistent foreground demand,
ready-resource reuse, cancellation of empty obsolete work, bounded speculative
retention and persistent real fallback ownership. This pass applies those ideas
to the native pipeline rather than adding a second renderer.

Primary MapLibre sources read during this investigation:

- [Tile lifecycle](https://github.com/maplibre/maplibre-gl-js/blob/main/developer-guides/life-of-a-tile.md)
- [Pinned 6.14 tile manager](https://github.com/maplibre/maplibre-gl-js/blob/v6.14.0/src/tile/tile_manager.ts)
- [Pinned 6.14 covering-tile policy](https://github.com/maplibre/maplibre-gl-js/blob/v6.14.0/src/geo/projection/covering_tiles.ts)

The next useful measurements need a matched device navigation replay: first
visible coverage and first four sharp tiles, time waiting in each stage, decoded
DEM hits versus HTTP requests, per-upload mip cost, GPU execution and thermal
state. More speculative options include dedicated disk-cache admission separate
from slower offline queries, streaming source/body decode, and measurement-driven
mip staging. Those need evidence before adding threads, memory or GPU submissions.

Native planning still uses conservative global elevation boxes for unknown
terrain. Replacing those with coarse sampled extrema without an uncertainty
bound could hide higher-resolution peaks; no such quality shortcut is included.
This pass changes request ordering and work reuse while preserving the default
selected cover.
