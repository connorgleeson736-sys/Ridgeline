# Ridgeline performance trace findings

> Update: the reconciled fixes and further improvements are now packaged as the user-selected **0.17.0 side-by-side Performance APK**. See [the release findings](capacitor/PERFORMANCE-0.17.0.md) for final tests, measured subsystem costs and the conditional speed estimate. Earlier statements below about APKs not being built describe the previous verification stage.


## Latest-preview verification

The original analysis and first fixes below used a 0.14.0 checkout. The fixes have since been reconciled and tested against **0.16.0 source `c0b3941`**, as identified by `android-preview-download`. See [PREVIEW_VERIFICATION.md](PREVIEW_VERIFICATION.md) for the exact APK checksums, overlaps, test results and reconciled checkout. The response-buffer copy removal was already present in 0.16.0 and is excluded as a new Android fix. Historical fingerprints and test counts below describe the earlier work.


Reviewed 28 October 2026 (Melbourne). Original trace analysis, followed by implementation and verification below.

## Further major optimisations — second pass

The first set of fixes did not exhaust the opportunities. A deeper inspection found an expensive native fallback-child search directly in the measured source-selection path, plus repeated fallback and request-priority work. These additional changes are now implemented.

### Native fallback-child search: remove a quadratic scan

MapLibre's `_getLoadedDescendents` originally compared every loaded in-view tile with every missing ideal tile. With N loaded tiles and M missing tiles, that performs N × M native ancestry checks. Even an empty missing set first allocated and scanned the loaded-tile array.

The guarded `scripts/patch-loaded-descendants.py` patch indexes missing tiles by canonical zoom, wrap and position. Each loaded tile probes only the relevant ancestor positions. Native `isChildOf` still decides inclusion, so overscaled and wrapped-tile semantics stay authoritative. Matching loaded tiles retain their order within each parent's result. The existing child-completeness test, fallback zoom limits and selection consumer are unchanged. Empty sets skip the scan; sets of up to four missing tiles use a simple loop to avoid index overhead. No results persist across source updates.

Validation compares the actual bundled native method against the original algorithm over **200 deterministic randomized cases**, including mixed readiness, root tiles, wrapped worlds, identical canonical positions, exact-equal identities and overscaling. A 1,024-loaded / 512-missing fixture produces identical results with **1,024 instead of 524,288 ancestry checks**, a **99.8% reduction in those checks**. Index construction and lookups also cost time: this is an algorithmic operation-count result, not a 99.8% CPU/FPS improvement or a claim that the phone had exactly that workload.

This is the strongest additional candidate because it removes a multiplicative term from native source selection, the largest scope in the supplied JSON. An on-device JS profile is still needed to establish what fraction of the recorded LINZ selection time it explains.

### Raster fallback probes: share sibling misses and skip unnecessary scans

`public/raster-residency.js` previously checked the same missing parent and packaged-overview availability repeatedly for adjacent missing children. Misses now share a per-update memo. Residency revision changes and explicit overview additions clear that memo; future updates always start fresh. Restoring an available native parent follows the existing path.

The retained-loaded list is now built lazily, only when a missing tile actually needs a coverage check. An entirely ready 512-tile fixture makes **512 instead of 1,024 tile lookups** in this adapter. A 512-missing sibling fixture probes **521 distinct parents instead of 6,144 repeated ancestors**, a **91.5% reduction in parent probes**. Tests also verify nearest cached-parent restoration and discovery of a newly available parent on the next update. This memo stores neither imagery bytes nor stale cross-frame readiness.

### Request priority: stop scanning unrelated sources and reparsing URLs

`public/navigation-policy.js` formerly built visibility sets for every source when ranking the first request, even when only LINZ imagery was being scheduled. It now materialises a visibility set only for the requested source. Parsed immutable addresses use a bounded 2,048-entry memo across movement; visibility and focal priority are still recomputed after the existing invalidation events. Teardown clears both caches.

A controlled ten-movement test parses a recurring address once instead of ten times and performs no unrelated route/terrain scans. Additional cases check foreground/background scores, custom satellite URLs, source-content invalidation and cleanup. This reduces scheduling overhead without changing source resolution, demand priority rules or default concurrency.

### Additional verification and remaining work

All **15 focused JavaScript tests** (the earlier nine plus six new tests) pass. The **42 mobile tests** and Capacitor base web packaging pass after these changes. Direct rendering, native residency, regional routing, loading continuity and native tile-unloading checks also pass. Existing Java changes are unchanged from the previous nine passing JUnit cases. Build identity and engine provenance were regenerated.

These changes preserve the current renderer, full imagery, source zooms, terrain detail and drawing resolution. They do not increase concurrency or memory budgets. They also do not prove that every useful optimisation has been found. GPU execution, actual decoding work, covering-frustum traversal and device-wide memory pressure remain candidates requiring another device profile before larger architectural changes are justified.

## Implemented fixes and verification

The implementation preserves full source imagery resolution, source zooms, DEM values, terrain detail, native selection policy, current renderer, shaders and mobile drawing-resolution behaviour. No cache budgets or default request limits were increased. The original capture fingerprint below describes the pre-change baseline; the new web fingerprint is `881e58894f9af19061884c2a17291276df722d084c3c74d2412e79d70b13ba0c`.

### Changes made

1. **Redundant imagery selection and fallback checks:** `terrain-streaming.js` builds one readiness hierarchy per visible imagery source, even when several layers reference it. Hidden layers do not suppress a subsequent visible layer. `gap-imagery.js` passes established catalogue readiness to the nested regional route, eliminating the second catalogue selection/ensure on pass-through tiles. `native-residency.js` shares raster paint fallback classification within one synchronous preparation only; the next preparation recomputes it, and per-tile vector bucket checks remain authoritative. This avoids unsafe cross-frame selection caching. It does not claim to remove all cost from MapLibre's covering-tile traversal.
2. **Allocation and image ownership:** `response-cache.js` removes a redundant explicit ArrayBuffer copy before the Response constructor's own copy; consumers remain independent. `SharedTileBody.read()` no longer allocates a one-byte array per call. Failed compositions release completed image decodes immediately and close later results when they arrive. Cancelled renderer image jobs close abandoned bitmaps before rejecting. Worker mask eviction is reference counted so an active composition never uses a prematurely closed mask.
3. **Native dispatcher contention:** scheduling scans queued jobs only, evaluates each candidate priority once per pass and avoids stream allocations. Cancellation, body-reader closers, listener removal and future completion run outside the dispatcher monitor. Demand listeners are registered outside that monitor too. Per-chunk transfer cancellation checks read volatile terminal flags instead of acquiring the global dispatcher lock. Sharing, priority ordering, bounded background admission, the outstanding-request guard and per-consumer cancellation remain intact.
4. **Delivery and decode separation:** opt-in diagnostics now export `imageDelivery`, `networkHeaders`, `networkBody`, `decodeAdmission` and `decode` separately. The image scheduler reports `activeDecodes`, `queuedDecodes` and `decodeLimit`. `setImageDecodeLimit(n)` controls preparation independently of the existing image-job limit; `setImageDecodeLimit(null)` restores the existing limit-following behaviour. Cancellation removes queued decodes promptly. Admitted image jobs retain their overall bound, so this does not create an unbounded encoded-body queue. The immediate decode path avoids constructing queue entries when capacity is available. Defaults remain unchanged because these captures cannot establish an optimal numerical decode limit.

For a device comparison, keep existing moving/stationary image limits and call the engine's `setImageDecodeLimit(2)`, then `4`, then `null` on otherwise identical runs. Compare visible completion, frame intervals and the new stage metrics. These are controlled experiment settings, not verified faster defaults. GPU uploads and source/detail selection are unaffected.

The guarded scheduler patch script regenerates the bundled queue and records its new provenance hash. Capacitor's web integration was updated for the removed pre-copy, retaining its expiry and priority handling. The build identity was regenerated.

### Verification

- New focused regressions: **9 tests**, covering readiness deduplication and fresh snapshots, one catalogue selection, per-preparation fallback classification, independent response bytes, failed/late decode cleanup, borrowed-mask eviction, cancelled renderer decodes, independent fetch/decode limits and separate timing stages.
- Capacitor base web packaging succeeds; **42 mobile tests pass**, including quality/renderer identity, high-density Topo, cache expiry, native bridge cancellation and priority behaviour.
- Actual Java 21 compilation and JUnit execution of `TileDispatcher`, `TileDemand`, `SharedTileBody` and their tests: **9 tests pass**. The added contention test blocks an HTTP cancellation callback while an unrelated request still acquires and completes. Existing cases cover shared subscribers, priority promotion, queued cancellation, validated EOF and blocked-reader cancellation.
- Existing Direct-renderer, native residency, tile-unloading, loading-continuity, image recovery, imagery retention, regional-routing, loading-controls and base-performance checks pass with their controlled fixtures. These are correctness checks, not measured phone FPS.
- Some older checks remain unavailable or fail for existing fixture assumptions: CPU/cache-return/stability audits reference Git history absent from this checkout; the expanded-gap audit requires absent `qa/gap-expansion/audit-targets.geojson`; the diagnostics bootstrap fixture lacks `map.removeLayer`; the raster-residency fixture expects an unwrapped retention method already wrapped in the baseline implementation. These are reported rather than treated as passing.

No physical device, Android SDK or emulator is available here. A full Gradle APK/lint run, real GPU pixel comparison and measured phone speedup remain unverified. Java core tests and Capacitor packaging do not substitute for them. No APK was installed or published.

## What is slowing the app down?

The strongest evidence is **heavy main-thread tile selection, compounded by slow image preparation and admission**. The Android recording also shows substantial WebView I/O CPU overhead and device-wide memory pressure. A faster connection or a larger cache alone will not address these problems.

The supplied app snapshot reports about **9.9 fps during sampled movement**, with a **183.8 ms p95 frame interval**. LINZ source selection alone averages about **22 ms per measured call**, already longer than a 60 fps frame budget of 16.7 ms. The system trace independently confirms that the WebView renderer main thread is busy for most of the recording. Optimise selection and reduce repeated work before increasing concurrency or changing imagery quality.

## Inputs, app architecture and scope

- `trace-exynos7884B-RP1A.zip`: contains a 134,134,207-byte Android Perfetto recording, lasting **44.425 seconds**. Parsed with Perfetto trace processor v59.1. No positive `error` or `data_loss` entries appeared in its `stats` table.
- `ridgeline-performance.json`: a rolling five-second diagnostics snapshot, plus counters and inclusive component timings with potentially different lifetimes.
- Repository inspected at `f53616f40d1f3fbe6e09e7b03eff80e9621fe46a`.
- Snapshot build fingerprint: `a21b411a2ff90f5617211086c34d7dbf6cfa80b6f5e47ee48b4c57f6ce5c0699`. This matched the pre-change `public/build-info.js`. That identifies the web baseline; it does not independently identify the installed APK, generated mobile patches, settings or instrumentation.

The trace contains `nz.ridgeline.mobile` and its sandboxed Android WebView renderer. This is the **Capacitor 3D app**, not the separate Kotlin/Compose map renderer in `android/`. Its packaging in `capacitor/scripts/build-web.mjs` enables Original MapLibre + Direct sampling. The web renderer uses patched MapLibre 6.14.0, native terrain selection, raster imagery, vector overlays, bounded source/texture caches and worker-based regional imagery preparation. Android adds offline/archive lookup, intercepted HTTP, persistent caching and request controls.

Relevant source: `public/app.js`, `public/assets/maplibre-gl.mjs`, `public/maplibre-direct-sampling.js`, `public/native-residency.js`, `public/raster-residency.js`, `scripts/maplibre-image-queue.js`, and `capacitor/mobile/` plus the Java tile transport under `capacitor/android/app/src/main/java/nz/ridgeline/mobile/`.

### Interpretation limits

The JSON and system recording have no shared marker establishing which five seconds overlap. Their totals must not be combined. The snapshot says `moving:false` because the map had stopped when exported; its frame series retains recent movement samples.

Component timings are explicitly **synchronous and inclusive**: nested scopes overlap. Do not sum selection, terrain and texture totals or divide them by five to obtain CPU utilisation. The counters for policy evaluations, queue rebuilds and native requests are not documented as five-second deltas.

The `mainThreadBreakdown` labels are present in the supplied JSON, but their literal instrumentation is absent from the checked-in source searched here. Treat them as useful broad scopes, not proof of a particular inner function. Add finer scopes before rewriting the selection algorithm.

## 1. Main-thread source and terrain selection — highest priority

| Measured component | Calls | Inclusive total | Mean per call | p95 per call |
| --- | ---: | ---: | ---: | ---: |
| LINZ source selection | 143 | 3,142.5 ms | 21.98 ms | 50.5 ms |
| Terrain selection | 179 | 2,030.1 ms | 11.34 ms | 26.3 ms |
| Terrain source selection | 310 | 1,737.0 ms | 5.60 ms | 10.6 ms |
| Texture preparation | 179 | 1,148.5 ms | 6.42 ms | 15.0 ms |
| Topography source selection | 143 | 556.4 ms | 3.89 ms | 8.9 ms |
| Imagery-boundary source selection | 143 | 412.8 ms | 2.89 ms | 6.0 ms |
| Direct planning | 179 | 81.8 ms | 0.46 ms | 0.8 ms |

Satellite selection totals only 13.3 ms over 143 calls. LINZ's much larger scope is the first place to investigate. This comparison does not establish that LINZ's server is slower: these are synchronous selection timings, and active coverage/workload can differ.

The five-second window also contains **33 long tasks and 1,326 ms of blocking beyond the first 50 ms of each task**. Synchronous drawing submission averages 11.2 ms, p95 18.3 ms, while movement frame intervals average 100.7 ms. Drawing submission captures only part of the frame's work; selection, callbacks, preparation, scheduling and graphics waits can account for the rest.

### Proposed fixes

1. Instrument MapLibre TileManager updates and terrain updates separately: covering-tile traversal, retention/parent-child lookup, source-to-terrain coordinate mapping, and residency bookkeeping. Count visited tiles, selected tiles and cache hits alongside timings. This distinguishes expensive individual operations from unnecessary repetition.
2. Cache selection/mapping results by relevant camera, viewport, source-content, DEM-bound and style generations. Stable frames should reuse results. Invalidate for arriving imagery, elevation changes, source/style changes and context restoration; do not freeze refinement or reuse a stale terrain bound.
3. Trial the existing `public/selection-interval.js` control at **50 ms**, then **100 ms**, against zero. Drawing must keep the live camera. Its current `sourcedata` handler forces selection on each content/metadata event; coalesce bursts into one pending update while preserving prompt readiness. A nominal interval may provide little benefit if every tile arrival bypasses it.
4. Build expensive coverage/readiness snapshots once per generation and share them across consumers. Avoid scanning every source tile for every terrain patch. Some such caching already exists; measure misses and invalidation frequency before adding another cache.

Check `checks/audit-cpu.cjs`, `checks/cache-loading-performance.cjs`, and the source/residency checks when implementing. Preserve parent coverage, native tile ordering, terrain culling, route picking and content arrival behaviour.

**Confidence:** high that selection is expensive; the exact inner algorithm responsible remains unprofiled.

## 2. Image preparation is congested; admission is the visible queue

| Snapshot measure | Result |
| --- | ---: |
| Decode/preparation stage | 35 samples; mean 314.0 ms; p95 442.2 ms |
| Image admission wait | 68 samples; mean 603.1 ms; p95 3,162 ms |
| Image jobs | 15 active, 33 queued, configured limit 16 |
| Active ready-byte jobs | 0 |
| Ready-byte reserve | 1 slot |
| Regional compositions | 0 active / 0 queued |
| Terrain refresh backlog | 0 |

In `scripts/maplibre-image-queue.js`, an admitted job retains its slot across response acquisition and asynchronous image decoding. A backed-up decoder or delayed main-thread continuation can therefore keep later tiles outside admission for seconds.

The measured decode span surrounds an awaited decode operation. **442 ms elapsed does not mean 442 ms of synchronous decoding on the main thread**: it can include decoder queueing, worker scheduling and delayed promise continuation. The native trace's `decodeBitmap` slices cover a different path and cannot be substituted for these browser timings.

### Proposed fixes

- First compare moving/stationary limits of **4/6 and 6/8** with the current captured limit of 16, keeping the camera path and detail unchanged. These are experiment values, not proven optimal defaults. Track visible-area completion as well as fps: reducing concurrency too far can slow refinement.
- If transport and preparation have different capacity, separate their admission budgets, with a **bounded encoded-byte handoff**. Allow useful delivery without admitting an unbounded decode backlog. Preserve cancellation and visible-tile priorities. The existing single cap makes the slowest stage occupy the whole job slot.
- Avoid decoding the same live tile repeatedly when an existing valid source texture or prepared result can serve it. Reuse ownership already provided by residency/composition systems; measure repeated tile IDs and decode counts before introducing another decoded-image pool.
- The scheduler protects a ready-byte slot while this snapshot has zero active cached jobs. Its reserve is enabled by any ready bytes, not necessarily a ready job in the queue. Trial borrowing that slot when no eligible ready job exists, returning capacity as jobs finish. This may improve utilisation slightly; it cannot explain the whole slowdown.

**Confidence:** high for admission congestion and slow elapsed preparation; medium for how much is decoder work versus scheduling delays.

## 3. Android WebView transport and system CPU contention also matter

CPU execution measured from scheduler slices over the 44.425-second recording:

| Thread/process | CPU seconds | Interpretation |
| --- | ---: | --- |
| WebView `CrRendererMain` | 30.901 | About 69.6% of one core over the recording |
| App `Chrome_IOThread` | 15.088 | Substantial browser I/O/interception overhead |
| App `Chrome_InProcGp` | 8.028 | CPU work in graphics submission; not GPU execution time |
| Renderer compositor | 5.358 | Additional composition/scheduling work |
| Android app main thread | 5.113 | Distinct from WebView's JavaScript main thread |
| Android `RenderThread` | 3.886 | Distinct from both threads above |
| App process, all threads | 62.382 | Parallel CPU time, not elapsed time |
| WebView renderer process, all threads | 71.804 | Parallel CPU time, not elapsed time |

`CrRendererMain` additionally spends **3.513 seconds runnable but waiting for CPU**, and **1.483 seconds in D/DK uninterruptible states**. Those waits do not all identify a single cause. About 26.0 of its 30.9 running seconds are on CPUs 6/7; this is not evidence that it simply remained on one slow core.

Native counters show **2,737 HTTP cache hits / 3,535 cache requests = 77.4%**, plus 798 network requests. These are cumulative observations, not a transfer rate for this recording. A native disk hit still passes through interception, streaming/copying, browser delivery, decoding and GPU upload. It does not mean the tile is already render-ready.

### Proposed fixes

- Measure archive lookup, HTTP-cache lookup, first byte, body completion, bridge batches and decode handoff separately for a tile attempt. Use counters and aggregated durations without recording provider credentials.
- Optimise warm-cache delivery and request/control volume before increasing foreground transfer concurrency. The current native implementation already removed an extra foreground admission cap; reinstating a blind network cap can strand cache hits behind slow transfers.
- Profile allocation and copying in `TileHttpClient`, its shared body/readers and archive delivery. Replace duplicate buffers only where independent readers, validation and cancellation remain correct.
- Retain existing batched controls, negative saved-tile lookup memo, shared requests and streaming. They are already implemented in this checkout; reimplementing them is not a new fix.

The trace establishes I/O CPU expense, but lacks call-stack samples to assign those 15.1 seconds to a particular Java or Chromium method. The 521 cumulative cancellations are not, by themselves, evidence of a defect: cancelling obsolete map demand is expected.

## 4. Device-wide memory pressure amplifies the slowdown

The kernel reclaim thread **`kswapd0` consumes 19.923 CPU seconds** during the recording, about 44.8% of one core. Other applications are active too. Ridgeline has 59 `mm_vmscan_direct_reclaim` slices totalling about **345 ms elapsed** across its threads; five occur on `CrRendererMain`, totalling about **51 ms**, with a 36.3 ms maximum.

The app's recorded Java heap counter ranges from **11,921 to 42,340 KB**. This is only the managed app heap, excluding WebView JavaScript, native buffers, GPU textures and other processes. Seven app background GC slices total about 1.35 seconds elapsed, with the longest approximately 416 ms. Background GC elapsed time is not equivalent to a main-thread pause.

This supports memory pressure as a contributor. It does **not** establish a Ridgeline memory leak, total app memory, swap bandwidth, or that Ridgeline caused all system reclaim.

### Proposed fixes

- Audit combined budgets across encoded caches, source textures, retained terrain drapes, DEMs, regional input bitmaps and in-flight bodies. A bounded cache at every layer can still produce a large combined working set.
- Connect Android memory-trim signals to safe eviction of unused resources and suspension of speculative preparation. Protect current visible coverage and downloaded maps. Measure whether existing pressure handling actually engages on this WebView/device.
- Bound preparation by estimated bytes as well as job count; promptly close unused ImageBitmaps and release abandoned buffers according to ownership rules.
- Compare foreground-only runs with the current device state. Collect app + renderer PSS/RSS, JavaScript heap and native/graphics memory. Do not enlarge caches based on this trace.

## 5. Graphics and terrain remain secondary candidates

Adaptive terrain is supported but **disabled** in the snapshot. Twelve 128-grid patches account for **405,504 triangles**, equal to the reported native count. This means adaptive simplification is off; it does not mean terrain rendering is off.

The system recording contains 996 `WebViewFunctor::drawGl` slices totalling 9.807 seconds inclusive, with a 412.2 ms maximum, and 956 `DrawFrame` slices totalling 19.192 seconds inclusive. Fence waits (`waitForever`) occur on graphics threads. These spans can include blocking and nested work; neither their sums nor their counts are map fps or GPU utilisation.

The JSON has **zero GPU timer samples**, and the system trace has no Android FrameTimeline records. GPU frequency and composition counters exist, but do not isolate Ridgeline's GPU execution. Therefore GPU saturation is **unresolved**, not ruled out.

### Proposed fixes and experiments

- Keep Direct sampling; its planning p95 is only **0.8 ms** in this capture. Replacing the renderer is not the first supported action.
- Measure texture uploads, mipmap generation, terrain texture preparation and graphics waits separately. Spread nonessential preparation across frames with a bounded time/byte budget, retaining valid previous imagery until replacements are ready.
- Compare the existing adaptive terrain option at the same source detail, then inspect silhouettes, seams and route/terrain alignment. Its analysis itself has a CPU cost; this capture does not prove a net improvement.
- Use a temporary lower-resolution or lower-detail run to distinguish graphics sensitivity from selection cost. The mobile build deliberately keeps drawing resolution stable during gestures; silently restoring automatic resolution changes would alter existing behaviour.

## What these captures do not blame

- **Network bandwidth alone:** no delivery-stage samples or per-request network waterfall are supplied.
- **Regional image composition as the current queue:** its snapshot backlog is zero and it has no transform samples. This does not exclude earlier work elsewhere in the recording.
- **Terrain refresh starvation at export:** reported backlog is zero.
- **Direct-plan construction:** small compared with the selection scopes.
- **A WebGL context loss:** explicitly false in the snapshot.
- **Retired custom mesh/LiDAR viewers:** they are outside this app's active render path.

## Recommended implementation order

| Priority | Change | Success measure |
| --- | --- | --- |
| 1 | Add inner selection timings; coalesce repeated source/terrain updates and preserve generation-scoped results | Lower LINZ selection cost/count, fewer long tasks, correct refinement |
| 2 | Tune image concurrency; separate bounded delivery/preparation capacity if needed | Lower decode/admission p95 and faster visible completion without worse frames |
| 3 | Profile warm native delivery, bridge/control volume and body allocations | Lower `Chrome_IOThread` CPU and fewer bytes copied per tile |
| 4 | Apply combined memory budgets and trim speculative/unused resources | Less reclaim, bounded app + renderer memory, acceptable revisit behaviour |
| 5 | Budget texture preparation; evaluate adaptive terrain and graphics controls | Lower preparation/frame p95 with verified visual quality |

Scheduler counters show **42,758 policy evaluations and 1,325 queue rebuilds**. Their lifetime is unknown, so they cannot be called per-frame rates. Instrument deltas and reasons; `navigation-policy.js` marks the queue dirty during movement and readiness events can trigger rebuilding. If profiling confirms meaningful CPU expense, batch invalidation or update affected keys rather than repeatedly rebuilding the entire queue. The existing scheduler already uses heaps: replacing a linear queue with a heap would duplicate completed work.

## Device validation plan

1. Record exact APK/version, WebView version, settings, camera route and build fingerprint. Use a release-equivalent build. The trace includes 251 Java `Compiling` slices totalling 6.096 seconds inclusive, so compare first-run and warmed runs separately; this is not proof of shader compilation or steady-state cost.
2. Repeat the same pan/zoom/return route at least three times for each experiment. Separate cold area, warm revisit and stationary refinement. Hold imagery, detail, drawing resolution and terrain settings fixed except for the one variable being tested.
3. Capture movement fps/frame p95, long-task blocking, source/terrain selection timings and counts, decode/admission p95, visible-area completion time, cache/decode reuse, native I/O CPU and combined process memory. Mark the same interval in the JSON and Perfetto capture.
4. Add a Chrome/WebView performance recording or JavaScript CPU profile. This system trace has **zero `perf_sample` rows** and no detailed JavaScript function slices on the renderer main thread, so it cannot identify a particular hot JS function. Obtain graphics/frame timing separately where supported.
5. Check visual continuity, correct source revisions, cancellation, offline fallback, parent coverage, DEM accuracy and context restoration. Existing fixture checks establish correctness, not physical-phone speed.
6. Choose changes that improve frame pacing **and** useful visible loading. An empty queue achieved by cancelling useful work, or higher fps achieved by silently reducing detail, is not an equivalent performance improvement.

## Reproducing the system measurements

Unzip the supplied archive and open the `.bin` with Perfetto v59.1. These queries reproduce the principal scheduler evidence; process and thread names should be resolved per recording rather than assuming the same IDs in a new capture.

```sql
SELECT (end_ts - start_ts) / 1e9 AS duration_seconds FROM trace_bounds;

SELECT p.pid, p.name AS process, t.tid, t.name AS thread,
       ROUND(SUM(s.dur) / 1e9, 3) AS cpu_seconds
FROM sched s
JOIN thread t USING (utid)
JOIN process p USING (upid)
WHERE s.dur > 0
GROUP BY t.utid
ORDER BY cpu_seconds DESC;

SELECT t.tid, t.name, ts.state,
       ROUND(SUM(ts.dur) / 1e9, 3) AS seconds
FROM thread_state ts
JOIN thread t USING (utid)
WHERE t.name = 'CrRendererMain' AND ts.dur > 0
GROUP BY t.utid, ts.state;

SELECT p.name AS process, t.name AS thread, s.name AS stage,
       COUNT(*) AS samples,
       ROUND(SUM(s.dur) / 1e9, 3) AS inclusive_seconds,
       ROUND(MAX(s.dur) / 1e6, 3) AS maximum_ms
FROM slice s
JOIN thread_track tt ON tt.id = s.track_id
JOIN thread t USING (utid)
LEFT JOIN process p USING (upid)
WHERE s.dur > 0 AND s.name = 'mm_vmscan_direct_reclaim'
GROUP BY t.utid, s.name;

SELECT name, value FROM stats
WHERE value > 0 AND severity IN ('error', 'data_loss');
```

The input trace and attachments remain outside the source repository. This report records observed bottlenecks and testable fixes; no on-device speedup is claimed until the changes are measured on the phone.
