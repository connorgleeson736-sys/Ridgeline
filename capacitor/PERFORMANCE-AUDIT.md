# Android performance audit — 0.14.1

The original request is broader than the 0.14.0 change set. That release fixed selected cache/bridge/UI issues; it did not establish an exhaustive review or isolate the reported device bottleneck. This follow-up records the reviewed paths, additional fixes, rejected work and remaining measurement gaps. No phone speedup or parity with Chrome has been established.

## Coverage and disposition

| Area reviewed | Finding and disposition |
| --- | --- |
| Native online admission / HTTP cache | 0.13.1 removed the extra foreground cap. Fresh cache hits do not wait for native network slots. Background work remains bounded; provider freshness, 256 MB cache, validation and cancellation retained. |
| Encoded imagery and DEM memory caches | 0.14.0 removes expired entries before cache-admission/prefetch decisions. Subscriber buffer copies in the custom tile protocol remain necessary because buffers transfer to workers. |
| Shared response input cache | Found an unnecessary explicit ArrayBuffer slice before the Response constructor, which already snapshots its buffer input. Removed it in 0.14.1; tests verify that changing one consumer's bytes cannot affect another or the retained cache. This removes one explicit full-body allocation/copy per response from this cache path. |
| Raster/source retention and eviction | Native GPU-source reuse, byte budgets, pending preservation and parent fallback already exist in website code and are retained. 0.14.0 eliminates repeated coarse-byte recounts. Long-distance eviction alone is expected, not proof of a cache bug. |
| Terrain coverage/readiness | Found repeated reconstruction of an identical quadtree coverage index. 0.14.1 memoises it per manager when the ordered ready-coordinate keys match. Texture readiness is still checked every time. Holes, changed coordinates and restored readiness rebuild it. Old coverage closures stay immutable for completed image snapshots. A 100-repeat fixture builds once instead of 101 times. |
| Terrain RTT invalidation and refresh | Inspected native-residency's DEM-only event suppression, saved-image ownership, zoom-independent stacks, quality holds, refresh budgeting and transition coverage. Existing mechanisms protect imagery/DEM continuity. No broad invalidation suppression was added: doing so could display stale pixels after real style/source changes. |
| Terrain update / visibility / LOD | Existing wrappers perform selection, coverage transitions and focus priority work. Persistent per-tile hooks or camera-keyed result reuse might save allocations, but require stronger evidence and invalidation design. No geometry, frustum, LOD, source zoom or screen-error change made. |
| Adaptive terrain | Already analyses cooperatively while idle, stops analysis on movement, validates DEM revisions and retains full-resolution boundaries. No simplification increase or quality reduction made. |
| Original + Direct drawing | Existing direct plans, shader sampling and source-texture reuse inspected and retained. No shader rewrite, detail reduction or forced GPU synchronisation introduced. Native upload/driver costs still require device traces. |
| Boundary imagery composition / decoding | Existing worker scheduling and transfer ownership retained. 0.14.0 removed per-input SHA256 decode-cache overhead. Repeated canvas PNG composition/decoding can remain expensive; worker lifetime and cancellation are tested. No lossy encoding or altered blend/mask introduced. |
| Predictive loading / preparation | Existing website prediction, source caches, backpressure, source prioritisation and expanded preparation retained. 0.13 fixes obsolete prediction cancellation. Increasing preparation could worsen CPU/decode demand, so it was not expanded speculatively. |
| Offline storage / migration | Indexed shared payload storage, bounded migration, region ownership and snapshot PDFs retained. 0.14.0 adds bounded negative lookups with epoch invalidation after writes/migration/registration changes. No automatic eviction of saved offline regions. |
| JavaScript ↔ native controls | Redundant begin calls removed, priorities/cancellations coalesced into bounded batches. Both compatibility and production batch paths exercised with real browser workers and mocked Java controls. Java token handling tested separately. |
| App UI / routes / persistence | The component capture showed selection work for empty route sources. 0.14.1 creates passive-route sources only when needed and hides empty waypoint layers, restoring visibility whenever points exist. Existing keyed route cards and hidden-sidebar deferral retained. Region-list DOM/polling and gesture-end localStorage work reduced in 0.14.0. View persistence is flushed on lifecycle events. |
| Diagnostic attribution | Previously reported a broad main-thread diagnosis. 0.14.1 diagnostic exports add opt-in per-component synchronous timings for source selection, terrain selection, texture preparation and Direct planning, plus native loading/cache counters. Android JSON reports use the native system save picker rather than relying on a WebView blob download. Timings are inclusive and overlap; do not add them together or interpret them as GPU execution time. |

## Rejected experiment

A numeric replacement for string-based coverage indexing passed equivalence tests but showed no meaningful improvement in a controlled workload (approximately 84 ms for both paths over 30 index builds of 1,024 tiles and 1,026 queries per build). It was removed, including its unused implementation. The safer unchanged-input memoisation above was kept instead. These Node measurements are not phone FPS.

## How to collect the missing phone evidence

Enable the existing performance diagnostics, pan through new imagery, revisit it and use the report's export button. `mainThreadBreakdown.components` shows synchronous component calls, inclusive totals and recent p95 timings; `nativeLoading` includes transfer/cache counters. Targets are captured when diagnostics are enabled: reset/re-enable after changing the map style or terrain mode. Diagnostics themselves add measurement overhead and are disabled with the panel.

A native tile cache can improve tile readiness without improving already-loaded drawing FPS. Conversely, a source-selection or terrain-prepare hotspot can stall the main thread even when all bytes are cached. The device report and, if necessary, Chrome remote profiling/Android system tracing are needed to determine which dominates on this phone. A controlled browser/SwiftShader fixture verifies behavior and attribution, not phone GPU or WebView performance.

## Preserved quality and limits

Original + Direct; original terrain/image detail policies; unchanged mesh-selection and shaders; Topo source zoom 18 with dense-screen sizing; unchanged image bytes and offline packs; 256 MB online cache; 0.13.2 removal of frame-rate-triggered drawing-resolution drops. No increase to cache budgets and no eviction of saved user regions.

## References used

- Existing pinned website renderer, source residency, terrain streaming/composition, motion/detail, prediction and diagnostics implementations.
- [Cesium Globe cache and screen-space controls](https://github.com/CesiumGS/cesium/blob/main/packages/engine/Source/Scene/Globe.js): budgeted tile retention is separate from screen-space quality.
- [OkHttp cache implementation](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/Cache.kt): native cached-response handling and freshness.
- [Fetch body extraction specification](https://fetch.spec.whatwg.org/#concept-bodyinit-extract): buffer-source Response bodies snapshot the input; retained buffers still require independent ownership at transferable protocol boundaries.

## Follow-up implementation: 0.15.0

[MAIN-THREAD-OPTIMISATIONS.md](MAIN-THREAD-OPTIMISATIONS.md) records the selected larger CPU changes: reusable geographic indexes, release-wrapper reuse and a stable-membership transition fast path, with before/after fixtures and explicit preservation of native invalidation and matrix calculation. Device-specific bottlenecks still require a phone trace.

## Follow-up implementation: 0.16.0

[CACHE-INVALIDATION-OPTIMISATIONS.md](CACHE-INVALIDATION-OPTIMISATIONS.md) describes removal of duplicate automatic WebView elevation persistence and the reduced allocation/texture-size work on tile arrivals, with before/after correctness and CPU fixtures. Saved regions and explicitly saved overview data are preserved.
