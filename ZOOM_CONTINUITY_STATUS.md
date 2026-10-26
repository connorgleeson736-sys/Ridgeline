# Zoom-out continuity — 20 October 2026

Site appgprj_6acc83afb6588191aff43dc542d98b5f. Base source 6488d2136407ee7e5738c9bc0d88ee85167d2c9a (published v49). Keep Original default, original imagery and colour references, authentic DEMs, existing LOD policy, owner-private access and LIDAR binding. Cache preference remains 352 MiB default / 96–700 MiB slider.

## Reproduced cause

Native-lifecycle fixtures showed four finished children are retained while a coarser parent is unready, but three or one were no longer drawn despite remaining cached. There was no cache eviction. The complete-cover-only fallback rejected partial coverage at view boundaries. Compressed-cache hits alone do not avoid decode/upload/composition. Predictive warmup skipped zoom-out, and idle preparation skipped coarse data covered by fine source tiles.

## Implementation

- `terrain-streaming.partitionCover` generates an exact, non-overlapping quadtree partition containing finished cached descendants and ordinary native patches for uncovered siblings. No elevation is fabricated. Maximum 64 partition leaves per update across partial transitions, depth eight; if work exceeds the cap, the existing native fallback remains authoritative. Complete covers retain their existing fast path.
- `native-residency` adopts the original finished RTT objects for partial coarsening, reuses cached incomplete targets and temporarily removed empty patches, and supplies native tile matrices for newly required complement patches. Parent and children never overlap. When requested imagery and DEM are ready, selection returns to the requested native parent. New counters: partialCoverHolds / transitionPatches. Cache accounting and ownership stay intact; no additional persistent cache pool.
- `imagery-preload` warms at most two source ancestors at the actual zoom focus while zooming out. Existing two-request concurrency, per-gesture/per-minute limits, native-load backpressure, low-power/save-data opt-out and byte admission remain in place. This is compressed-data warmup, not a promise that GPU composition is complete.
- Optional `prepare-ahead` prioritises two nearby source ancestors (imagery and DEM) even when fine tiles cover them. Finished-composition planning likewise considers the two parent levels first. Existing idle-only, visible-first, rate, memory, cancellation and composition budgets apply. Checkbox remains optional and off by default.

## Validation and recovery

Run `node checks/audit-fast.cjs` (now 26 focused gates), `node checks/build-identity.cjs` after changing runtime files, build using the Sites helper, then `node checks/base-production.mjs`. `checks/zoom-continuity.cjs` tests 1/2/3 finished children, zero and normal offscreen budgets, cached incomplete siblings, 50 stable updates per scene, native RTT preparation, reversal before readiness, exact texture identity, byte accounting, replacement readiness, mixed-depth/wrapped partitions, hard work limits and zoom-out parent planning. GPU handles/loading are controlled; these are not real driver/FPS measurements. Normal source preparation and composition tests still exercise actual pinned native loaders, coordinate/fingerprint code and raster shader dispatch.

Managed preview was attempted; it exited because another vinext server lock was reported (PID 14, /site). `sites-preview status` reports stopped. Do not kill arbitrary processes or modify working application configuration to compensate. Browser visual/Chromebook frame-time QA is unverified in this environment.

Recover from the latest source through the Sites source helper. Read this document and PERFORMANCE_RECOVERY_STATUS.md; never overwrite current work from the baseline. Package and publish only after checks pass. The live-delivery checker in scripts/imagery/south-island/check-publication.py verifies module hashes and unchanged imagery bytes and records an immutable proof in the existing R2 corpus. Keep credentials only in memory/stdin. For rollback redeploy saved v49 through Sites; do not delete the South Island corpus.

## Trade-offs / remaining work

Partial transitions can temporarily draw extra native patches for uncovered sibling regions; bounded work and prompt return to the native parent limit the cost. This fixes a reproducible retention gap, not all possible cache misses or context loss. Newly revealed terrain still needs actual imagery/DEM. Large transitions beyond the partition cap use the existing fallback. No engine change, global cache increase, GPU occlusion queries, upload scheduling rewrite, or generic colour/texture modification was included. Device traces should guide any later frame-budget or LOD-hysteresis work.

## Release checks

All 26 current-source gates passed, including the seven native partial-cover scenes and imagery warmup checks. Production build and `checks/base-production.mjs` passed; pinned engine assets and the 5,313-coordinate / 94-pack South Island corpus remain intact. Runtime identity: 5272e91d840846b0c8de82be97cb925e13f2368339232ac5976ee7fe7aef3c1f. The final pushed commit and authenticated delivery result are recorded by the source workflow and publication proof; these identify the release without a self-referential commit field.
