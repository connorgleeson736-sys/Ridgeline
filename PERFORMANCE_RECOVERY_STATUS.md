# Performance recovery implementation — 20 October 2026

Site appgprj_6acc83afb6588191aff43dc542d98b5f. Baseline f44153a2a551955524f229ed83801d310ebb33e7 (v48). Preserve owner-private audience, LIDAR R2, source imagery/colour references, measured DEMs, Original default, current HLOD and 96–700 MiB user cache preference.

## Milestone A: cancellation and retry recovery

Implemented bounded regional composition scheduling (32 queued jobs / 16 MiB downloaded inputs, existing two active slots), immediate queued cancellation, active cancellation with slot retention until acknowledgement, dynamic demand promotion for shared prefetches, speculative-first admission, paired response-body cleanup and worker cancellation between asynchronous stages. Synchronous pixel loops remain non-interruptible. Worker failure restarts use bounded backoff; fallback permits one small job at a time. Byte-cache subscriber ownership and grace remain intact.

Retry records now expire per time window, remove abandoned requests, tolerate source replacement, reset on context loss/restoration and retain auth/missing/abort exclusions. Two retries per key per 30-second window, jittered backoff, bounded records. A later cooldown permits recovery without a page lifetime lockout. Repaired the stale slider test by moving routing/worker/visibility expectations to their focused suites; immutable photo/mask contracts remain pinned.

`checks/audit-recovery.cjs`, native-residency, regional worker/blend/routing, slider and cache-return checks cover the changed paths. Existing finished-texture return fixes remain intact. Actual browser GPU/device latency is not inferred from deterministic clocks.

## Next milestones

- Resource ownership ledger and a byte-bound native source texture recycler, within the existing spare-target allowance.
- Bounded per-stage return tracing, source/upload submission and RTT adoption/invalidation evidence.
- Revision-driven DEM extrema indexing; profile native RTT preparation and retain correctness.
- Full regression/build, browser QA if supported, private publication and authenticated delivery checks.

## Recovery

Reopen this Site through the Sites source helper; use its latest source result, not the baseline commit, for subsequent saves. Read this file and the audit attachment. Run focused checks before continuing. Use the helper for all Git commits/pushes and package each tested milestone. Do not publish an intermediate milestone as completed work. No secret is stored. Final source, assets and reports are Git-backed; original South Island imagery remains in existing immutable R2 packs.

## Milestone B: resources, passive tracing and DEM revisions

Source texture recycling now has a byte ceiling inside the existing spare allowance (default split: 4 MiB source recycler / 12 MiB RTT recycler; combined remains 16 MiB). Reports enumerate unique active/off-screen source textures and RTTs, compressed bytes, decoded main-thread DEM, recyclers, framebuffers and pending composition. Unknown driver/worker/vector/optional-renderer memory is explicitly unmeasured. Estimated pressure pauses preparation; visible coverage and selected cache preference remain intact. No GL readback or polling is introduced.

The passive tile trace distinguishes compressed, source and finished-RTT adoption, regional queue/work stages, native load completion/upload submission and RTT invalidation/draw submission. A 384-event ring and 512 identity limit bound retention; no authenticated URLs are logged. Native decode/upload spans remain combined, not misrepresented as independent GPU timings. Reports include stage-measurement notes.

Source DEM data/eviction/reset/cached-insertion and in-place extrema mutations now invalidate a content revision. One thousand unchanged terrain updates produce zero extra enumerations; existing native summit frustum tests still recover all ten false-culling cases. Cleanup restores min/max descriptors and native hooks.

CPU profile evidence: the dominant cost is native matrix allocation in `_getTerrainCoordsForRegularTile`, which allocates before rejecting unrelated source/render patches. The before profile and alternated baseline report are retained in qa/performance-recovery. Next: use the same native coordinate implementation with a prefiltered related-patch list, then compare exact matrices, lifecycle regressions and warmed CPU distributions. This is not a frame-rate result.

## Final implementation and evidence

All six requested priorities are implemented. Additional helper work is limited to fixing the stale slider assertion and maintaining current checks/build identity. Native regular terrain-coordinate generation is called with an indexed related-patch list during synchronous RTT preparation. It still performs the actual matrix arithmetic. The index is rebuilt each prepare, custom-layer preparation opts out, tile-range paths remain native, and method restoration is guarded. No invalidation is globally disabled. Float32 output matches in 168 parent/child/overscale/wrap cases. The audited 64-tile CPU fixture's median changed from 6.419 ms to 0.821 ms (87.2% less preparation CPU time); this is not overall viewer speed or device FPS.

`node checks/audit-fast.cjs` runs 24 current regression gates and writes a fresh manifest. `node checks/audit-cpu.cjs` repeats the alternated warmed CPU comparison. Full CPU profiles can be regenerated with Node `--cpu-prof`; the tracked reports retain timing distributions, kernel fingerprint and profile summaries. `node checks/build-identity.cjs` emits a deterministic runtime-module fingerprint; diagnostics report it plus the audited source baseline and current detail/cache controls. The exact final publication commit is recorded in the authenticated external delivery proof, rather than mislabelling a pre-build Git HEAD as the published commit.

Retry recovery also handles bounded online/reconnect attempts, source metadata replacement and Retry-After when propagated by the loader. Cancellation tests exercise the shipped worker/compositor and real scheduler through an async worker bridge, confirming immediate subscriber rejection, retained active slot until acknowledgement, bitmap cleanup, skipped encoding and no abort-as-failure count.

## Limits and rollback

Supported browser QA was attempted through `sites-preview` and the cloud browser. The supervisor reported a running preview, but navigation returned ERR_CONNECTION_REFUSED. No browser frame-rate, driver context-loss, real GPU timing or Chromebook latency claim is made. Native CPU/frustum/coordinate tests, actual worker source, pixel fixtures, bootstrap, allocation ownership and lifecycle tests remain the verification evidence. Runtime diagnostics deliberately combine native decode and upload submission, and label fetch spans that include regional routing; neither is called presentation time.

The memory ledger is an estimate, not total process memory or available VRAM. Driver allocations, worker duplicates, transient decode, vector resources, route workers and overlap with the optional Three renderer remain explicitly unmeasured. Preparation stops under estimated pressure; existing foreground quality and complete coverage are retained. The selected 352 MiB default and 96–700 MiB slider remain unchanged.

For rollback, redeploy the previous saved v48 through Sites, or recover its source f44153a2a551955524f229ed83801d310ebb33e7 and package through the supported workflow. Do not delete R2 imagery or change access. Recovery milestones: A 90046a35cde9e70664885bc5a0d6b3b5c2af1501; B 6ec8dbbc1124935e43be0e79b431197dc3c7464d. Final delivery uses the same owner-private audience and LIDAR binding. Later agents should retrieve the latest source rather than overwrite it with a milestone.
