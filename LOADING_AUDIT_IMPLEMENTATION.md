# Loading audit implementation

Baseline: published v56, source 9fc9663089b4ed3a83dc33b8a5710457504990a6.

## Changes by finding

| Finding | Implemented change | Evidence / limit |
|---|---|---|
| A01 | Pinned native image queue admits visible ready compressed bytes through up to two reserved slots within the selected moving/stationary total job limit (1–96), ranks queued work using a cached current-view index, preserves regular-work fairness, settles canceled queued promises and pumps at movement end. Saved 8/12 defaults remain user preferences. | New native scheduler fixture: six ready returns complete while six slow jobs remain active at the moving limit of eight; additional tests exercise limits above 10 through 96, runtime slider changes and queue draining. Not a device latency benchmark. |
| A02 | Zoom-dependent RTTs, including vector-bearing drapes, can refresh during continuous movement. Correct fingerprint invalidation stays enabled. | Native fixture services 12 stale patches across six moving frames. Existing exact photographic reuse and populated-line invalidation checks pass. |
| A03 | Adaptive estimated submission allowance: at most two stale patches moving/four stationary, reduced toward one for expensive submissions. Focal priority retained. | Native limit/cost feedback fixture; complete-cover regressions pass. This is a conservative count derived from CPU submission time, not a hard GPU time guarantee. |
| A04 | Native enqueue/admit and decode stages, stable source attempt spans, source generations, moving state, bounded timing aggregates surviving event-ring rollover, RTT contributors and queue/backlog statistics. | Correlation and ring-rollover fixture; no URLs or keys recorded. Submission remains distinct from GPU completion. |
| A05 | Immutable descriptor LRU, 32 entries/512 KiB estimate, five-minute expiry, pending-read deduplication, per-bucket identity; no cached response bodies or access changes. | 12 concurrent byte ranges share one descriptor read; original hash/range/origin/storage checks pass. No claim about changed real-user RTT. |
| A06 | Prediction pauses behind actual native foreground queue pressure. Queued transforms use current view rank; pressure reduces effective cold retention without overwriting saved budgets. | Admission and hysteresis fixtures plus original cancellation/prediction suites. Shared-subscriber ownership preserved. |
| A07 | Bounded encoded input reuse for regional/interior/overzoom requests, independent subscriber cancellation and safe copies; transform priorities can follow visible work. | Input-sharing/cancellation regression, regional routing/worker tests and unchanged imagery/mask hash gates. Runtime composition remains necessary on uncached boundaries; no new invented image pixels or precomputed replacements. |
| A08 | Distinct recent visible failures and success resets; bounded backoff; recovery pauses during motion/offline/hidden states. Restore LINZ only after a complete native measured DEM cover is ready and still matches the current fallback cover. Canceled/failed warmups dispose owned resources. | Provider-health and atomic warm-cover fixtures; original imagery retry tests. Recovery conservatively waits if cover exceeds its bounded 32-tile warmup. |
| A09 | Periodic estimated pressure sampling with three-sample admission/eight-sample recovery hysteresis; smaller effective cold pools and drawing buffer under sustained pressure; regional input ownership included in ledger. | Pressure-controller fixture; existing active-resource and protected-DEM tests. Ledger is an estimate, not total tab/VRAM; no visible resources are deleted to satisfy a nominal slider. |
| A10 | Preparation now uses an adjustable 30°–70° wider native viewing frustum with the closest angular tiles first after visible rendered idle, within remaining finished-view/source capacity. The old radius and ring planners are retired; navigation cancels speculative work. | Expanded-view, preparation lifecycle, composition scheduler and continuity suites. Counts are session work, not a guarantee that the entire expanded view is resident. |
| A11 | Original terrain responses populate the bounded persistent response namespace also read by route/Three workers, respecting source cache policy. Final focused route keys are bounded after exact waypoint inclusion. | 2000-point broad focused route fixture uses 57 final keys, below 64. Existing profile interpolation/cancellation/seam tests pass. Decoded memory remains separate between workers. |
| A12 | Large geographic catalogs dynamically load when imagery routing needs them rather than before map construction; authoritative routing still waits for complete metadata. All changed entry/module/worker URLs are versioned. | Production import/asset checks. This reduces the eager startup graph; no fabricated cold-start timing claim. |
| A13 | Three imagery changes retain elevation/normal resources; generation checks discard stale responses during rapid basemap switches; route updates coalesce during tile arrivals; full selection/upload/render CPU span and queued bytes are reported. | Real Three math/worker tests with mocked GPU, imagery-switch elevation-reuse assertion and repeated teardown checks. Shaders/source pixels are unchanged. |

## Verification

- `node checks/audit-fast.cjs`: 31 focused suites pass.
- `node checks/navigation-pipeline.cjs`: queue, moving refresh, timings, shared input cancellation, provider cover/recovery, memory hysteresis and final profile budget pass.
- `node checks/engine-patch.cjs`: recorded patch exactly reproduces the vendored bytes from the pinned upstream baseline.
- `node checks/lidar-storage.mjs`: immutable storage, ranges, descriptor deduplication and origin checks pass.
- `node checks/base-elevation.cjs`, `node checks/three-data.cjs`, `node checks/three-terrain.cjs`: pass.
- `node --experimental-vm-modules checks/three-renderer.cjs` and `checks/three-planner.cjs`: pass, including elevation reuse on imagery changes and teardown.
- `node checks/audit-cpu.cjs`: current controlled preparation medians approximately 0.824, 0.833 and 0.928 ms; no browser FPS claim.
- Production build passes. `node checks/base-production.mjs` verifies packaged current code, entry URLs, immutable imagery/mask/reference bytes, engine provenance and build fingerprint.
- `git diff --check`: passes.

## Verification limits

The managed browser preview could not start because its environment reported an existing vinext server at `/site` with PID 14. Supervisor status was stopped. No unrelated process was killed and no alternate browser infrastructure was installed. An optional older native shader-graph harness also referenced a missing Dawn installation in a retired scratch directory; it could not run. The GPU shader code was not changed. Browser pixels, GPU driver behavior and Chromebook frame-time/latency improvements therefore remain unmeasured.

The patch preserves actual source imagery, hard masks, measured DEMs, overlay invalidation, route geometry and existing audience. Further precomputed boundary imagery, source/overlay pipeline separation or a global GPU scheduler should be justified by the new stage traces, not claimed as required unverified rewrites. The known stop-dependent scheduler behavior has a direct regression test now.

## Rollback and maintenance

The Site version made from this source can be rolled back to v56. Its engine patch is localized and reproducible through `scripts/patch-image-scheduler.py`; provenance retains the upstream hashes as well as the current hash. Do not apply this patch blindly to a new MapLibre version. Keep the old-pixel/DEM continuity tests when changing refresh policy. Every future runtime file update must receive a fresh import URL and build fingerprint.
