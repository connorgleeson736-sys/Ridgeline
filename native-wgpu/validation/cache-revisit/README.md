# 0.18 cache behavior adaptation and explicit skirt removal

All six requested changes are connected to production paths. This is not a claim
of full MapLibre equivalence, finished migration or measured Samsung A20 performance.

| Requested behavior | Native implementation |
|---|---|
| Foreground cache hits independent of slow downloads | Separate two-thread local/offline/HTTP-cache-only lookup executor. Network admission is separate (8moving/12idle foreground,2background). CPU preparation remains bounded to2geometry/2imagery workers; a missing input suspends by future continuation rather than occupying those workers. Independent known dependencies are started together, not serially discovered one round trip at a time. |
| Fresh compressed RAM with expiry metadata | Bounded8MiB low-RAM/16MiB otherwise, shared encoded bodies; absolute upstream expiry and source fingerprints. Hash each arriving body once, not every fresh prepared hit. Metadata permits derived-cache reuse without fetching original bodies again while fresh. Source changes/expiry reject derived entries; expired entries are pruned with an earliest-expiry gate. |
| Subscriber-aware cancellation and reuse grace | Independent consumer futures share one transfer; one reader can cancel without canceling others. Last-reader cancellation reaches OkHttp Call.cancel. Dynamic promotion moves a shared background job into foreground admission. Normal quick returns get600ms grace; at most8started offscreen preparation jobs may finish during that grace. Speculative cancellation skips grace. Download cancellation releases its own lease without poisoning map/profile consumers. |
| Cached ancestors/sharper cover/coarse reserve | Existing resident fallback retained. Fully cached sharper children now win over an available requested parent; incomplete children never pretend to cover a parent. Exact/sharper resident coverage suppresses redundant downloads; existing ancestors suppress unnecessary fallback-parent requests. Residency keeps a coarse reserve up to20%within count/payload policy when other eviction candidates exist, preserving current coverage. |
| Finished imagery composition and source-mapping reuse | Versioned lossless premultiplied RGBA cache for boundary/cropped compositions, with all network input versions/expiry and attachment-generation namespace. Provider geometry plans are cached up to512tiles; native exact geometry/photo mapping plans up to16memberships. No camera-dependent matrices or stale texture handles are cached. Background compression/file I/O never own the LRU monitor. |
| Small predictions and optional expanded GPU preparation | Predict using a cloned future camera and the same native planner, including zoom-out ancestor warming. At most3actual provider inputs per prediction,2background network transfers,24inputs/gesture,60/minute. Foreground pressure/new gesture/visibility cancels obsolete predictions. Optional **Offline / imagery > Prepare nearby terrain while idle**, defaultoff, expands visibility by1.3without changing camera/LOD. After650msidle and visible readiness, one bounded batch/view (up to2geometry+2images) prepares GPU resources. Speculative ownership never enters draw demand, never displaces visible queued uploads, and cancels on camera change/pressure. |

The existing automatic disk budget remains adaptive up to1GB. Derived terrain
and composition share its previous derived allocation (one eighth, capped128MiB);
HTTP persistence gets the remainder. Saved regions and original6ZIPs remain separate.
Encoded RAM is a separate bounded memory tier, not an extra disk cache. Ordinary
imagery is not recompressed merely to create another representation; the finished
RGBA cache targets expensive boundary/crop results.

## Skirts

Removed all drawn skirt indices, duplicate skirt vertices, skirt-lowering shader
code and CPU seam-continuity analysis. Shared grid payload266256->133128bytes.
Full original surface heights, boundary samples, source zoom ceilings, filtering,
9imagery mip levels, adaptive error policy and route handling remain.

This was an explicit user decision. Missing or mismatched height/LOD edges can
expose cracks; there is no substitute wall or fabricated height fill. The old
skirt-gap fixture now asserts the old skirt-depth setting changes zero pixels
and drawn triangles equal surface triangles. Historical validation folders refer
to earlier skirted builds. Do not claim cross-LOD crack acceptance.

## Evidence

-18focused JVM tests pass: actual HTTP cache admission, independent reader abort,
last-consumer cancellation, quick-return sharing, priority promotion, RAM expiry,
absolute HTTP freshness, derived-cache version/corruption/bounds, exact RGBA
round-trip, complete/partial resident coverage, viewport demand and speculative
GPU upload ownership.
- Real local HTTP server keeps8foreground requests blocked by a latch. A tile
previously stored in the actual OkHttp disk cache returns in1.529095ms before that
latch opens. Encoded RAM is disabled in this fixture, so it proves the independent
disk lookup lane, not merely a RAM hit. Body bytes match. This is one controlled
host measurement, not an A20 latency guarantee.
-26Rust core tests pass, including complete cached children preferred over a ready
parent while partial children retain the parent fallback.
- Vulkan/GLES GPU fixtures pass: existing grade, imagery mapping, mips, coverage,
adaptive triangle and bounded cache checks, plus no-skirt assertions. Their log
wording predates the final descriptive caption cleanup; repaired pixels are0.
- All3optimized Android JNI ABIs, Kotlin compilation and APK assembly pass.
Signature/artifact information is in the adjacent apk.json once frozen.

No emulator or physical phone execution in this checkpoint. Policy/concurrency
fixtures do not validate an Android file provider or every live-provider failure.
The next useful acceptance is the same A20 peak revisit/tilt workload, with cache
transport counters and device logs if a presentation/source problem persists.
