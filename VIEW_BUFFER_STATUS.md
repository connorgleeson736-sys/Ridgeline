# Outside-view buffer — 20 October 2026

Site appgprj_6acc83afb6588191aff43dc542d98b5f. Baseline v52 source 2e501b949f1b5ed290d33a67139c0eaab985a484. Preserve Original default, owner-private access, LIDAR binding, current cache preferences, genuine imagery/elevation and foreground LOD selection.

## Behaviour

Map layers → Detail & caching → Outside-view buffer: integer 0–10 extra tile rings beyond native visible tile footprints. Default zero preserves the previous preparation area. Requires the existing Prepare ahead checkbox; it pauses during navigation/low-power/data saver and when visible work or memory pressure needs priority. Existing camera/viewing-point radius remains independent. Off means no additional view-edge buffer, not disabling the older anchor preparation.

Source preparation derives a mixed-LOD footprint from native visible imagery/DEM IDs, generally one level coarser, capped at source z15. It proposes neighbouring cells outside that footprint, skips any containing/contained visible cell, deduplicates neighbours, prioritises inner rings and the viewed surface, and clips to NZ. Finished terrain composition uses actual visible render patches at their existing levels, with the same ring control and native compositor. It does not draw hidden terrain to the canvas or mutate requested native LOD.

Planning is bounded: up to 512 input IDs, 20,000 perimeter candidates examined and 3,362 retained candidates per plan. Source candidate lists are capped at 3,362 per source and composition retains its bounded list. Large/dense footprints can stop before the entire selected outer ring; the slider is a search distance, not guaranteed coverage or a distance in kilometres. Physical tile size varies by local LOD. Existing source readiness, ownership, deduplication, cancellation, cache headroom and GPU composition gates remain authoritative.

Preparation work allowances use max(existing anchor radius, new view buffer) so the old one-ring small-batch cap does not silently prevent wider searches. This reuses the existing bounded rate/memory fractions; one native source job at a time and one idle composition submission per step are retained. It does not raise the selected cache budget or change concurrent normal foreground loading.

`detailSettings.viewBuffer` is persisted in the existing ridgeline-detail-v1 preferences, independently clamped 0–10 and rounded. Checkbox enable/disable gates both preparation-area controls. Changing the buffer cancels stale unused work, clears the old candidate area and replans through normal idle scheduling. Report settings and preparation statistics include the selected buffer.

## Validation / recovery

All 28 current-source gates pass, including checks/view-buffer.cjs: exact exterior rings around a 2×2 footprint (12 cells for one ring, 32 for two), default zero, mixed-LOD overlap exclusion, deduplication, bounds, persistence, clamping and actual app callback/checkbox wiring. The native source-preparation fixture exercises nonzero view buffer with real pinned source/load/cache paths and controlled network/GPU. Existing composition tests use real native coordinate/fingerprint/raster shader dispatch. These are CPU/configuration/lifecycle tests, not measured browser pixels or Chromebook FPS.

Managed preview was attempted and failed on the existing vinext server lock (PID 14, /site); supervisor status is stopped. No alternate server or application configuration workaround was introduced. Browser visual and device performance QA remain unverified.

Reopen the latest project source using the Sites helper. Read this file, LOADING_CONTROLS_STATUS.md and ZOOM_CONTINUITY_STATUS.md; never restore the baseline over newer work. Run checks/audit-fast.cjs; regenerate identity with checks/build-identity.cjs after runtime edits; build through Sites and run checks/base-production.mjs before packaging. Runtime identity for this implementation: a1590d6f4ddb438ca7096c312c996b867b6619cacb14f6f0d0b64f8b638b0f7c. Supported source-helper commits and saved deployment provenance identify the exact release. No credentials stored. Rollback: redeploy saved v52; do not alter audience or delete R2 imagery.
