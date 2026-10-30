# Prepared terrain, coordinated residency, cancellation and upload admission

These are **controlled host measurements**, using genuine LINZ Aoraki payloads.
They are not Samsung A20 timings, Android FPS measurements, or a whole-APK A/B.
The same production cache/sampler/admission classes and native renderer run with
old versus new policies. Raw samples, fixture hashes and host metadata accompany
this report; nothing below is a predicted phone speedup.

| Workload | Previous path | New path | Interpretation |
|---|---:|---:|---|
| Read/decode four DEM PNGs and build terrain, median of40 |12.485ms|1.138ms validated prepared hit|90.9% less time on this host; exact restored bytes|
| Already-decoded DEMs, build terrain, median of40 |1.116ms|1.138ms prepared hit|No measured gain over warm decoded RAM; ready buffers still bypass both paths|
| Terrain preparation becomes obsolete after7rows |1.116ms full mesh|0.109ms cancellation|90.2% less measured time;122of129rows not built|
|36-photo reverse revisit, six alternating trials/backend|4revisit uploads,40total|0revisit uploads,36total|10% fewer total uploads and mip-generation levels|
| Vulkan revisit workload median, including GPU drain |36.979ms|35.995ms|Small timing benefit; noisy, work counts are stronger evidence|
| GLES revisit workload median, including GPU drain |40.113ms|36.826ms|Same exact upload reduction|
| Accelerated12-frame pressure fixture, Vulkan peak outstanding|12|2|Bounded submissions, identical final pixel hash|
| Accelerated12-frame pressure fixture, GLES peak outstanding|6|2|Bounded submissions, identical final pixel hash|

Costs and limits matter:

- A prepared entry takes44,326bytes versus66,580uncompressed, with lossless fast
  gzip. Median write1.334ms; first cold write5.274ms. Production uses one background
  priority worker, at most8queued writes, dropping obsolete queued cache writes
  rather than blocking render/prepare workers. First-visit CPU cost increases;
  repeated cold preparation is what this cache avoids.
- Prepared validation re-reads and SHA-checks all source dependencies. Production
  uses normal OkHttp freshness/conditional requests and the selected offline
  policy. The host test uses local source files; HTTP lookup and native adaptive
  profiling costs are excluded. ImageIO is the host PNG decoder, not Android's.
- GPU revisit payload rises from11,717,280to13,115,376bytes because more photographs
  stay warm. Both fit the previous combined target14,379,840bytes. Stress keeps
  all32demanded photos and8terrain fields, plus7warm photos, at14,163,948bytes.
  These are texture payloads, not total driver allocations. Protection of active
  coverage can temporarily exceed the target within explicit source-count caps.
- Upload-cost replay of the measured light workload shows **no change**:20frames,
  4.380ms total upload CPU,0.303ms maximum/frame for both policies. The cost cap
  was not reached; do not advertise a CPU speedup for this case. Focused tests
  separately exercise expensive image uploads, cheap terrain batches, byte/count
  limits, render pressure and expensive-first-tile forward progress.
- Pressure fixture submits12frames as quickly as possible, with a blocking drain
  at the two-submission limit for reproducibility. Android instead polls without
  waiting and retries on the next vsync, retaining the latest camera. This
  fixture proves the backlog limit, not actual input latency or device FPS.
  Total drain times: Vulkan537.078->522.887ms; GLES111.541->130.246ms. The GLES
  case trades throughput for a smaller backlog; it is not a universal speedup.
- Cancellation does not abandon shared source downloads or poison DEM futures.
  It checks before/after preparation stages and each mesh row; one in-progress
  shared DEM decode may finish for other consumers. Rapidly returning demand
  reschedules an intentionally aborted tile without failure backoff.

## Implementation

Prepared cache: up to one eighth of the automatic disk budget, capped128MiB;
HTTP cache receives the remainder of the existing1GBadaptive total. Explicit
saved regions and the six original ZIPs remain separate. Files include every
halo/parent dependency, including404fallback decisions, a processing-version and
provider-namespace key, original f32 heights and adaptive profile bytes. A changed
source rejects the entry. Atomic replacement, bounded LRU, gzip integrity checks,
corruption recovery and interrupted-write cleanup are implemented. Bump the
processing namespace when terrain generation/profile semantics change.

Coordinated GPU cache retains up to25%extra photos by borrowing unused terrain
space under the previous combined payload target. Current demand/fallback cover
is protected; off-demand recency and restore cost guide eviction. Existing
memory-pressure cleanup and count caps remain. No source resolution, mips,
filtering, terrain samples, adaptive error policy, skirts or routes are reduced.

Upload admission learns measured JNI cost by kind, respects render CPU cost,
keeps the650KiBcap, and allows at most4cheap uploads. A single expensive tile
still progresses. Queue-completion callbacks plus nonblocking polling prevent
submission of additional frames/uploads with two outstanding frames. Device loss
still reaches recovery rather than remaining behind a stuck backlog gate.

16focused tests passed. The existing Vulkan terrain fixture also passes. Both
Vulkan and GLES residency/backpressure fixtures execute GPU work; final image
hashes match within each backend. Android ABI builds, assembly and signature pass.
Phone interaction and A20 performance remain unverified.

## Reproduce

Use the SDK/JDK/Rust instructions in ../../README.md. Extract inputs.tar.gz into
an external directory; verify against inputs.json. No provider key is needed.
Set RIDGELINE_BENCH_FIXTURE to that directory and RIDGELINE_BENCH_OUTPUT to an
existing external results directory. Run:

```sh
cargo run --release --locked --manifest-path native-wgpu/Cargo.toml \
  -p ridgeline-renderer --example cache_revisit_bench -- "$RIDGELINE_BENCH_FIXTURE" \
  > "$RIDGELINE_BENCH_OUTPUT/vulkan.jsonl"
EGL_PLATFORM=surfaceless cargo run --release --locked --manifest-path native-wgpu/Cargo.toml \
  -p ridgeline-renderer --example cache_revisit_bench -- "$RIDGELINE_BENCH_FIXTURE" --gles \
  > "$RIDGELINE_BENCH_OUTPUT/gles.jsonl"
export RIDGELINE_UPLOAD_TRACE="$RIDGELINE_BENCH_OUTPUT/vulkan.jsonl"
native-wgpu/android/gradlew --max-workers=2 -p native-wgpu/android \
  :app:testDebugUnitTest --rerun \
  --tests nz.ridgeline.nativeapp.PreparedTerrainCacheTest \
  --tests nz.ridgeline.nativeapp.UploadAdmissionTest \
  --tests nz.ridgeline.nativeapp.GpuUploadQueueTest \
  --tests nz.ridgeline.nativeapp.DemSamplingTest \
  --tests nz.ridgeline.nativeapp.PipelineBenchmarkTest
```

Select the desired Vulkan ICD when multiple drivers exist. Recorded Vulkan runs
use SwiftShader; GLES uses the available host EGL driver. The fixture repeats
real payloads at controlled tile IDs to isolate resource locality, not to
represent an authentic panoramic map or validate geographic appearance.
