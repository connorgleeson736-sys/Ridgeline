# Removing native loading barriers

Baseline: `49d74fc2d05e19ce26da65e0d93f78105c6c0da3`, the preceding foreground-priority
checkpoint. The investigation continues on `native-renderer-performance`.

This pass removes two serial waits in the Kotlin loading pipeline that feeds the
Rust renderer. It changes when valid source work starts, while retaining the
same imagery/terrain data, provider selection, geometry, mip levels and renderer.
No route-planning code changes.

## Results

Final alternating host measurements and complete samples are in
`../validation/latency-barriers-2026-11-01/`.

| Controlled workload | Before | After | Interpretation |
| --- | ---: | ---: | --- |
| Real OkHttp disk-cache monitor locked for 400 ms; genuine 114,118-byte LINZ PNG over loopback HTTP | 404.369 ms | **43.377 ms** | Acquire to validated body, 12 alternating trials. 89.3% less latency, 9.3× faster in this cache-stall fixture. The new request completes while the actual cache monitor remains locked. |
| Four original DEM inputs; first source needs a real parent; 10/180 ms controlled source arrivals | 375.917 ms | **195.666 ms** | Complete 129×129 terrain mesh, 12 alternating trials. 48.0% less latency, 1.92× faster. Both paths make exactly five source requests and produce identical mesh bytes. |
| Four original DEM inputs, no parent fallback | 198.579 ms | **186.256 ms** | Same 180 ms slowest input. Overlapping actual PNG decode hides approximately 12 ms of work. Both paths make four requests and produce identical mesh bytes. |
| Healthy actual HTTP disk-cache hits, RAM retention disabled | 0.536 ms | 0.610 ms | 40 alternating samples after warmup. Approximately 0.074 ms extra admission overhead in this run; only the single cache-warming HTTP request occurred. This is a small cost, not an improvement claim. |

These gains are conditional and independent. They are not additive, a phone FPS
measurement, or a claim that ordinary uncached HTTP latency falls by 9.3×.
The cache-lock delay, source arrival times and first-source 404 are injected
replay controls; PNG payloads and the coarse parent are genuine LINZ inputs.
The mesh fixture runs production transport, input staging and sampling kernels
with host ImageIO decoding and one CPU worker; JNI terrain profiling, GPU upload,
Android BitmapFactory and first display are excluded.

## 1. A disk lookup can no longer indefinitely block online progress

Before this change every cache miss had to pass through the two local lookup
workers before becoming eligible for HTTP. A blocked cache journal, saved-region
lookup or body read could occupy both workers. Priority ordering helps queued
work, but cannot move an already blocked read.

Online foreground requests now get a 40 ms disk-first opportunity. If a local
lookup has not finished, the same shared job becomes eligible for a cache-free
HTTP request under the existing network limits. There is still one shared result
per source. A local hit can win and cancel the HTTP operation; a network winner
cannot be overwritten by a late saved/disk body or its old fingerprint.

Crucially, OkHttp 4.12's `FORCE_NETWORK` policy is insufficient: its cache
interceptor calls `cache.get(request)` before evaluating the request policy.
`HttpTileBackend` therefore uses a sibling `OkHttpClient` with `cache(null)` for
this escape path. It shares the existing dispatcher, connection pool, proxy,
TLS configuration and cancellation lifecycle. There is no new download pool,
provider, transport security policy or concurrency increase.

A timeout/storage error temporarily bypasses local probing for subsequent online
foreground work. Successful local progress, including a late losing read, reopens
the local path immediately; otherwise a monotonic 30-second cooldown permits a
later probe. Queued probes are removed when another source wins, and there are
still at most two running local operations. Offline-only requests and unpromoted
speculative requests never start a hedge. A failed HTTP hedge keeps waiting for
its possible valid local result instead of turning an available offline hit into
an error.

The intentional tradeoff is data and persistence during storage stalls. A late
cache hit can cause some unnecessary network traffic, and cache-free winners do
not populate OkHttp's disk cache. Encoded RAM reuse and validated derived caches
still apply. The healthy disk-first path continues to use ordinary HTTP caching;
its test performs 100 disk returns with no extra HTTP request. Saved regions and
existing cache files are neither deleted nor modified by this escape path.

## 2. Decode ready DEMs and discover parent dependencies immediately

Previously `loadGeometry` admitted all encoded inputs and then suspended on
`CompletableFuture.allOf`. No source could be decoded until the slowest input
arrived. This also postponed discovery of a missing/transparent source's parent
until after that barrier, adding a second sequential network wait.

`GeometryInputPipeline` starts all independent source requests first, consumes
already ready inputs, and suspends on the next outstanding arrival. Source
processing can now request a real missing-data parent while unrelated child
bodies are still in flight. Completed rasters are reused through the mesh build;
geometry remains unpublished until every required sample is available.

The replay's timing illustrates the mechanism. The first source's injected 404
arrives at approximately 12 ms, and the unrelated last child at 180 ms. The old
path discovers the parent after 180 ms, then waits another 180 ms. The new path
starts that same parent at approximately 12 ms, overlapping its wait with the
remaining children. Request count, final parent fallback and all 66,564 mesh
bytes match. The normal no-parent case also benefits from overlapping real PNG
decode with source arrival, but has a much smaller gain.

Early processing is limited to the focus backdrop and first two detail leaves.
Each operation holds at most eight raster references, often already present in
the existing decoded cache: at most 24 pinned 256² float rasters, or 6 MiB of
sample payloads across those three operations. This is bounded temporary working
memory, not another persistent tile cache. Completion, cancellation, parking and
promotion out of the focus group release pins. Android memory pressure clears
pins and pauses early processing for 30 seconds; the old all-input path remains
available with unchanged output. Retention guards prevent an in-progress callback
from repopulating cleared pins after cancellation or memory pressure.

Other visible and speculative jobs retain ordinary admission. Existing worker
counts, network limits, original source stamps/deadlines, missing-pixel parent
repair, shared CPU decode ownership and prepared-cache validation remain in use.

## Verification

- **57 focused Kotlin/JVM tests pass**, including both new fixtures and changed-path
  regressions. All application and existing JVM test sources type-check with
  Kotlin 2.2.21, Java target 21 and Android API 36 classes.
- Tests cover fast cached hits, fully blocked local workers, the actual OkHttp disk
  monitor, cooldown recovery, strict offline behavior, non-hedged speculation,
  foreground promotion, shared cancellation, the eight-slot moving limit, failed
  HTTP with a successful disk result, both winner orders, late-version rejection,
  storage exceptions, incremental parent discovery, bounded/cleared operation
  memory, and full mesh equality with original DEM and parent pixels.
- The frozen `BeforeDeadlineTransport.kt` is test-only source from `49d74fc`.
  The mesh replay uses identical transport/sampling code in both arms, changing
  only the old all-input barrier versus production incremental input staging.
- No Rust kernel/shader, route-planning, website, Capacitor, provider asset, Android
  package/version or published APK changes. Unchanged Rust/GPU suites were not
  rerun for this JVM pipeline change. No Android device execution, new APK or
  physical-phone performance claim.

The host compiler setup and API-jar limitations are the same as the preceding
[foreground audit](VIEWPORT-LOADING-AUDIT-2026-10-31.md). Reproduce on a configured
Android development machine:

```sh
python native-wgpu/tools/fetch-live-proof.py --parent-dem /absolute/fixtures/live-proof
# The optional parent is original elevation tile 10/995/649; its captured
# input hash is recorded in parent-provenance.json.
RIDGELINE_PIPELINE_FIXTURE=/absolute/fixtures/live-proof \
RIDGELINE_BENCH_OUTPUT=/absolute/writable/results \
  native-wgpu/android/gradlew -p native-wgpu/android :app:testDebugUnitTest \
  --tests '*.CacheDeadlineTest' --tests '*.GeometryInputPipelineTest' \
  --tests '*.TileTransportTest' --tests '*.PriorityPipelineTest' \
  --tests '*.GpuUploadQueueTest' --tests '*.SourceDemandTest' \
  --tests '*.SharedDecodeTest' --tests '*.DemSamplingTest' \
  --tests '*.DemandPlanCacheTest' --tests '*.DetailPolicyTest' \
  --tests '*.ResidentCoverageTest' --tests '*.PreparedCacheReadTest' \
  --tests '*.UploadAdmissionTest'
```

Future device measurements should separate cache-wait time, ordinary/escape HTTP,
first ready source, parent discovery, completed mesh and first presentation.
Derived-cache decompression, bundled/attached overview reads and GPU uploads can
still create other waits; this change does not claim a universal latency bound
on the whole loader.

## Research

- [OkHttp 4.12 CacheInterceptor](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/cache/CacheInterceptor.kt): cache lookup precedes policy selection, and ordinary network reads also perform disk-cache writes. The real-monitor fixture verifies that the separate client avoids that lock.
- [Dean and Barroso, The Tail at Scale](https://research.google/pubs/the-tail-at-scale/): background for delayed alternate work and managing long waits. Here the alternate path is the same source's HTTP read, not a second provider or a duplicate data source.
