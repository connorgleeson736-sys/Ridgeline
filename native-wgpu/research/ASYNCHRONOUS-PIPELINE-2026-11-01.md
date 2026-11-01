# Native performance implementation — 0.1.7

**1 November 2026, Australia/Melbourne.** First implementation checkpoint: `3681a4ac86d955837c893ef42285b2ec9c0f3c32`.
Final application source is pinned in the accompanying release record. Builds on
native 0.1.6 and retains
the requested 0.1.1 checkpoint ancestry. Reference refreshed from
`android-preview-download`: Capacitor Performance 0.18.0, source
`d880143418ac46ece98adff0b3b2fbaedf044d46`.

This pass implements six improvements across file I/O, CPU admission, offline
loading, saved-map contention and GPU submission. Original imagery pixels,
terrain resolution, source ceilings, mip filters, skirts and renderer remain.
The 1–50 controls from 0.1.6 remain. Route-planning algorithms were not changed.

The user's imagery stalls have already improved. This work addresses broader
responsiveness and throughput; the measurements below do not identify a remaining
phone stall or establish a phone FPS improvement.

## Findings and changes

### 1. Original file reads no longer occupy image CPU workers

Attached ZIP ranges and original asset reads previously ran inside the same two
workers that decode and compose images. Two slow document-provider reads could
prevent an unrelated network-ready image from using either worker. More network
slots do not resolve that dependency.

`SharedLocalReads` now owns those reads on two independent priority workers.
Image tasks yield a future before decode. Independent HTTP/local dependencies
are admitted together, including boundary compositions needing both. Shared
encoded inputs have consumer ownership, priority promotion and cancellation.
Queued obsolete reads are removed. A canceled running read keeps its capacity
permit until the actual I/O exits; positional reads on a shared ZIP descriptor
are not interrupted, because interruption can close that descriptor for others.
At most 128 unfinished local reads are admitted. Saturation yields a capacity
future instead of occupying a CPU worker or inventing a cache miss.

Original metadata also initializes asynchronously before planning, rather than
performing the first JSON reads/parse inside selection or image decoding.
Known-absent local tiles go directly to the ordinary source path, so a ready
online tile does not queue behind unrelated local reads just to confirm absence.
Attachment membership uses the immutable published map instead of repeatedly
sorting the full attachment list; source-hash regex validation is reused.

**Controlled measurement:** with two original-file reads held for 250 ms, the
median queue wait for unrelated ready-image CPU work falls from **250.345 ms to
0.120 ms**, over three alternating pairs. Original byte results match. This is
an injected I/O dependency experiment, not a measured SD-card or phone speedup.

### 2. Offline downloads overlap independent sources

The offline downloader used one worker and fetched each image/DEM dependency
serially. Its transfer count was often one despite multiple background permits.
Raising the download slider alone therefore could not fill those permits.

`OfflinePrefetch` looks ahead across at most two tiles, discovering their
independent imagery and DEM-neighbour URLs. The current tile's shared lease is
also used when consuming the bytes. This matters when encoded RAM retention is
zero: completed prefetched bytes remain owned and are not downloaded a second
time. The current tile is promoted above lookahead. Actual HTTP admission still
uses the configured background cap and retains foreground priority.

Advancing releases obsolete leases; cancellation releases the whole window.
Local imagery preference, all previous decode/composition validation, missing-DEM
parent recovery, saved source bodies and durable membership writes remain.
This is bounded overlap, not an unbounded parallel region downloader. More than
two tiles are deliberately not staged, and the slider remains a ceiling rather
than a promise to create 50 useful transfers for every workload.

**Controlled measurement:** four tiles, three independent sources each, injected
25 ms response time, two background permits and zero encoded RAM retention.
The first recorded comparison is **309.519 → 154.395 ms**. The final regression
rerun is recorded in `offline-prefetch.json`; each run makes exactly 12 requests,
returns identical bytes and never exceeds two background transfers. These are
source-pipeline timings; real disk commits, phone bandwidth and provider behavior
can change the result.

### 3. Prepared buffers remain owned until GPU admission succeeds

The old frame loop removed an upload before calling JNI. A false result could
lose the pending entry and leave recovery to an unrelated future event.

Rust now returns a typed upload error, translated by new JNI methods into:

| Outcome | Action |
| --- | --- |
| Accepted or already resident | Acknowledge and release the pending entry |
| Residency/array capacity unavailable | Retain the same prepared payload and retry |
| Invalid payload | Remove it, record the failure and suppress repeated offers of that prepared buffer |
| Missing/lost GPU | Retain ownership and initiate the existing device recovery path |

Capacity retries start after 16 ms and cap at 512 ms. Deferred entries are skipped
when selecting other eligible uploads, so a blocked head cannot occupy every
attempt. Idle retries use a scheduled wake rather than continuous empty frames.
Demand changes prune obsolete ownership; a late acknowledgement cannot remove a
newer payload for the same source. Device recovery wakes the retained entries.
Legacy Boolean JNI methods remain available for existing controlled clients.

This closes an admission correctness gap. There is no invented latency percentage
for it: an actual rejection count on the user's phone is still unknown.

### 4. Vulkan mip uploads share the frame submission

Every GPU-generated image mip pyramid previously created and submitted its own
command encoder. Four admitted images plus a frame meant **five application
`Queue::submit` calls**. The mip work now shares one encoder and is submitted
immediately before drawing in the same queue submission: **five calls become
one**, an 80% reduction for that four-image case.

The original level-zero bytes, all nine mip levels and averaging algorithms are
unchanged. Shared scratch is consumed in command order before the next image
overwrites it. Queue texture writes precede the batch. Startup/readback probes
explicitly submit pending mip commands before reading. CPU mip fallback keeps
its existing behavior. Temporary surface failure stops further upload admission,
so pending commands stay bounded by the existing per-frame admission limit.

Three modes were compared: existing per-image submits, one separate mip-batch
submit plus the frame, and a merged mip/frame submit. The final fixture warms
the draw/mip pipelines, evicts warm sources, uploads four genuine LINZ image/
height payloads with distinct diagnostic tags, renders and drains the GPU.
Three alternating trials run sequentially for each driver/layout.

| Software-driver path | Existing CPU upload + render calls, median | Merged, median | Existing through GPU drain | Merged through GPU drain |
| --- | ---: | ---: | ---: | ---: |
| Vulkan texture arrays | 44.378 ms | 0.879 ms | 44.422 ms | 30.295 ms |
| Vulkan separate textures | 40.091 ms | 0.918 ms | 40.127 ms | 42.649 ms |
| GLES separate textures, candidate only | 12.673 ms | 11.917 ms | 29.936 ms | 25.310 ms |

These are host software-driver observations, **not phone frame times**. A short
CPU call can mean work moved into the asynchronous GPU queue. The Vulkan separate
case demonstrates the distinction: its CPU submission calls improve while total
completion is slightly slower. The split-batch mode is retained in the evidence
as an alternative, not silently discarded.

Earlier warmed GLES two-mode trials regressed from 29.908 ms to 39.851 ms through
GPU drain, while the later three-mode comparison improved. This variability does
not justify enabling a new default on GLES. **Android enables batching on Vulkan
only and preserves the GLES submission path.** The selected Vulkan policy targets
CPU/interaction latency and fewer driver submissions; it is not an established
phone FPS gain.

All frame bytes match between modes within each driver/layout. All nine checker
mip levels and both layers of the nine-level array probe pass. The same checks
exercise the exact mip generators, not a replacement shader or reduced image.

### 5. Saved readers avoid the writer's application monitor

`OfflineStore` enabled SQLite WAL, but even a known-resource read acquired the
same Java monitor used around background transactions. WAL cannot make a reader
concurrent if application code makes it wait before the query.

A published-state fast path now bypasses that monitor after initialization.
The unknown state still initializes under the lock. Resource availability is
published only after a successful durable transaction commit. SQLite still owns
database concurrency and snapshot consistency. This is a source-verified lock
removal, not a measured Android SQLite throughput claim; no device DB benchmark
was run for this change.

## A further change avoiding more than 200 ms in the fixture

The prepared-cache deadline previously resolved a lookup as a permanent miss
after 12 ms. A valid file arriving at 40 ms could be discarded while fallback
sources took another 350 ms. That is unnecessary work, not an intrinsic network
or image-quality requirement.

`DerivedReadGate.race` now exposes both the deadline opportunity and the actual
bounded read completion. The default deadline-only API remains compatible.
`PreparedShortcut` can select a late cache entry while the operation is waiting
on sources or at an existing CPU cancellation checkpoint during rebuilding.
Only a non-null hit wakes source waits; a late miss cannot create a busy loop.
Completed fresh fallback work is not subsequently replaced.

The cache returns its original dependency metadata alongside the unchanged
payload. At adoption the operation rechecks eligibility, current imagery
attachment generation where applicable, and every dependency fingerprint and
expiry. A rejected late entry cannot become eligible again in the same operation.
Adoption cancels only this task's unnecessary source ownership, preserving other
consumers of shared transfers. Queued/running reader limits remain 16/2; a canceled
running read retains its permit until actual I/O exit. No extra reader threads,
lossy cache representation or new file format is introduced.

**Measured:** actual lossless cache file, 40 ms controlled file readiness, the
existing 12 ms deadline and a 350 ms injected fallback-source wait. Median
**363.223 → 41.986 ms**, saving **321.237 ms**, with identical prepared bytes over
three alternating pairs. The number describes that dependency scenario, not a
321 ms saving on every tile or a phone measurement. Healthy fast hits still use
the first result; real cache misses still use normal sources. Loading diagnostics
now shows the number of late prepared-cache selections.

Evidence: [late-cache critical-path fixture](../validation/asynchronous-pipeline-2026-11-01/late-prepared.json).

## What remains worth pursuing

This pass builds on the [full audit](FULL-APP-PERFORMANCE-AUDIT-2026-11-01.md),
closing most of F01/F02, implementing revalidated late cache adoption from F06,
and removing the known-state monitor in F07. The next substantial
candidates remain:

| Candidate | Why it may matter | Evidence needed before enabling |
| --- | --- | --- |
| Correlated tile-to-draw traces, F00 | Aggregate stages cannot identify a particular late patch or input/presentation delay | Bounded event ring with generation, shared dependencies, upload outcome and actual presentation timing |
| Cache deadline tuning after F06 | The fixed 12 ms head start may not suit every storage device, even though late value is now preserved | Real hit/miss/late-hit distributions and avoided source work before changing the deadline |
| Conservative measured elevation bounds, F04 | Universal -100..4000 m prisms can retain irrelevant source requests | Complete hierarchy/halo coverage and no false-negative mountain culling |
| Active allocation accounting, F09 | Slider retention budgets do not include all in-flight bodies, GPU pages and staging | Peak bytes per stage and memory-pressure traces, especially with 50-job settings |
| Classified failure recovery, F03 | Generic 1/4/15 s delays remain for load errors | Provider retry metadata, corrupt disposable-cache handling and bounded transient recovery |
| Pipeline cache and startup probes, F13 | Shader/device startup work can affect launch and recovery latency | Driver-specific cache validation, bounded probe waits and cold-start device measurements |
| Frame timing/pacing, F17/F21 | Upload budgets use an assumed frame interval | Real display cadence and supported presentation/fence data; SurfaceView timings require care |
| Avoid offline decode/composition used only for validation | Offline downloads still compose and discard an image | Equivalent source validation and exact gap/fine-source behavior before removing work |

Network protocol replacements, sparse virtual texturing, aggressive culling,
lossy texture compression and more CPU threads were not added speculatively.
They require provider/device support, memory evidence or quality guarantees that
these host fixtures cannot establish. Source generation through every retained
CPU/GPU cache remains another correctness prerequisite for deeper racing work.

## Verification and research

Nine targeted JVM checks pass: local-I/O isolation/latency and ownership/capacity,
GPU acknowledgement/retry, two existing rank/coalescing regressions, and the
offline lookahead regression, plus three late-cache/compatibility checks. Three targeted graphics configurations exercise
the three submission modes. No full suite or lint was run. The optimized Android
libraries are rebuilt for ARM64, ARMv7 and x86_64; APK provenance, signature,
alignment and unchanged original-asset checks accompany the installer.

Evidence: [checks and scope](../validation/asynchronous-pipeline-2026-11-01/checks.json),
[local-I/O measurement](../validation/asynchronous-pipeline-2026-11-01/original-io.json),
[offline measurement](../validation/asynchronous-pipeline-2026-11-01/offline-prefetch.json),
[GPU summaries and raw alternatives](../validation/asynchronous-pipeline-2026-11-01/gpu-summary.json).

Current primary sources consulted:

- [wgpu best practices](https://wgpu.rs/doc/wgpu/documentation/best_practices/dos_and_donts/index.html): submission calls have visible CPU cost; reduce resource churn and unnecessary submits.
- [wgpu 30 Queue](https://docs.rs/wgpu/30.0.1/wgpu/struct.Queue.html): staging lifetime and queue-write/submission ordering.
- [SQLite WAL](https://www.sqlite.org/wal.html): concurrent reader/writer snapshots do not remove application-level mutexes.
- [Cesium Native selection/load/render algorithm](https://cesium.com/learn/cesium-native/ref-doc/selection-algorithm-details.html): keep selection, asynchronous preparation and renderability distinct.

No attached physical Android device was used. Original resolution and pixels are
preserved by the changed-path checks; full real-terrain appearance and interaction
acceptance remain open, as recorded in the migration status.
