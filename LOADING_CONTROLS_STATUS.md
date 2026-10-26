# Loading controls — 20 October 2026

Site appgprj_6acc83afb6588191aff43dc542d98b5f; source base eab3c393182563d692a6d7832cb83e0878aa4c57 (v50). Preserve Original default, owner-private audience, LIDAR storage, source imagery, measured elevation and current cache/detail preferences.

## UI and defaults

Map layers → Loading speed controls, a collapsible group restricted to Original:

| Setting | Range | Existing default |
| --- | --- | --- |
| Image jobs while moving | 1–96 | 8 |
| Image jobs while stationary | 1–96 | 12 (6 with data saver) |
| Off-screen new imagery requests | 0–24 | 4 (2 with data saver) |
| Off-screen new elevation requests | 0–12 | 2 (1 with data saver) |
| Predictive imagery requests | 0–12 | 2; 0 disables new predictions |

Correction to prior discussion: MapLibre's engine default stationary cap is 16, but Ridgeline already set it to 12 (or 6 with save-data). This change preserves the actual application defaults rather than silently increasing concurrency. Moving/stationary limits are independent. They limit concurrent image jobs, including fetch/decode, not tiles per animation frame. Raising them may increase contention/stutter; no speed improvement is claimed without device measurement.

## Implementation

`public/loading-controls.js` owns a clamped integer schema, localStorage key `ridgeline-loading-v1`, input/output updates, reset of only these five loading values, storage failure handling and listener cleanup. Actual pinned engine configuration uses `config.MAX_PARALLEL_IMAGE_REQUESTS_PER_FRAME` plus `setMaxParallelImageRequests`. Saved values are reapplied on map initialization and renderer return, so app initialization does not overwrite them. No vendor patch or map recreation/refresh/flush is used when sliders change.

Residency pools expose `setPendingLimit`, applied live by the production controller and on initialization. The limit governs future parking of new unfinished off-screen requests; already-running work is not cancelled by slider changes. Existing decoded tiles and reloads carrying good data retain the normal preservation path, avoiding a cache-loss regression. Memory budgets and native LOD selection remain unchanged. Limits are included in diagnostic pool statistics.

Predictive imagery reads the selected concurrency dynamically. Existing footprint (up to three planned coordinates per tick), per-gesture/per-minute caps, low-power/save-data opt-out, cache admission and foreground backlog gates remain. Zero prevents new predictions; active requests finish normally. Prepare ahead is still separately controlled by its existing checkbox/radius and is not enabled by these sliders. Diagnostics now include all five saved loading values in the existing report stats.

## Verification / recovery

`node checks/loading-controls.cjs` exercises the real 6.14 configuration export and actual app startup/UI fixture, saved values surviving initialization, live updates, reset, invalid/clamped inputs, blocked storage, renderer scope and teardown. Native raster lifecycle checks verify setting the off-screen pending limit to zero prevents parking future unfinished requests while existing retention/cache tests continue. Predictive tests cover zero disabling and dynamic limit reporting. `node checks/audit-fast.cjs` includes all 27 focused gates. The actual ESM bootstrap test is `TEST_BOOTSTRAP=1 node --experimental-vm-modules checks/base-viewer.cjs`. Build through Sites, run `checks/base-production.mjs`, then verify live modules/assets using scripts/imagery/south-island/check-publication.py.

Managed browser preview was attempted and failed on an existing vinext server lock (PID 14, /site). Supervisor status is stopped. No workaround server or engine modification was introduced. Browser layout, real GPU and Chromebook loading/FPS measurements remain unverified; DOM/configuration/lifecycle tests are the available evidence.

Reopen the latest source through the Sites helper and read this file plus ZOOM_CONTINUITY_STATUS.md and PERFORMANCE_RECOVERY_STATUS.md. Use supported helper Git commits and packaging. Do not restore the baseline over later work. Immutable delivery proofs in the existing R2 corpus identify the exact published commit and verified asset hashes. No credentials are stored. Roll back by redeploying saved v50, without changing access or deleting the imagery corpus.

Release checks: all 27 current-source gates, actual ESM bootstrap, production build and production artifact checks passed. Runtime fingerprint: 3023755633a044c90bdd89aeef02b06ddc22dbed0b9dacc2ac4d1b660a961834. Source code, settings schema, checks and recovery documentation are committed by the Sites source helper. The delivery checker records the exact final commit and asset verification externally after publication.

## Expanded ranges — v52 update

User requested tripling all five loading-slider maxima: moving/stationary 96, off-screen imagery 24, off-screen elevation 12, predictive imagery 12. Both UI/schema bounds and the downstream residency/prediction clamps are increased. Existing values, defaults, storage key, cache budgets and other admission/rate/backpressure checks remain intact; maxima do not force that many requests. Based on v51 source 7f7ac5ac45dcf3e5d65bcd3a94341e29c0b7d119. Use the latest runtime fingerprint and publication proof for this release; the earlier fingerprint above belongs to v51.

Expanded-range validation: all 27 regression gates, ESM bootstrap and production build/artifact checks pass. All five maxima were checked against the HTML range attributes, normalization, real MapLibre configuration, residency setter and predictive controller. Runtime fingerprint: 76c1fc9399f85926e6fda3be5b8a6d2762ffbc9eb512f760186b9f8b59a52dce. Browser layout remains the existing layout; visual/device performance QA is unverified because managed preview was unavailable in this environment.
