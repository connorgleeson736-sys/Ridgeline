# Cached terrain return fixes — 19 October 2026

Site appgprj_6acc83afb6588191aff43dc542d98b5f. Baseline version45/source f294e4da00898c050d096fee6eae939e6a187cb1. User authorizes investigating slow cached tiles returning to view, researching causes and implementing fixes. Preserve owner-private audience, R2 LIDAR, all South Island imagery/grade, measured DEM, original frustum/LOD/quality policy, routes/profiles, optional Three,1–20 radius and96–700 MiB aggregate cache. No cache increase or new imagery.

## Reproduced causes and fixes

1. Any intersecting raster tile arrival previously dirtied finished drapes, including a coarser tile not used in their pixels or a hidden provider. Returning could immediately discard/recompose a valid finer cached texture. The synchronous native terrain-data event context now carries sourceId/tile through its existing adapter. Only a source/tile key actually present in the saved native RTT fingerprint marks pixel content dirty. Changes to actual source coverage/LOD remain handled by native fingerprints; participating same-key reloads still dirty. Unknown event/fingerprint shapes retain conservative invalidation. Cached dependency sets are weakly keyed by native fingerprint objects.

2. Native RTT fingerprints include exact floating zoom. Even tiny camera zoom changes can invalidate otherwise identical source imagery, and native preparation ordinarily releases at most one stale source/zoom texture per frame. Cache a zoom-independence classification when pixels are actually composed. For zoom-only differences, unchanged source keys/revisions/visible layer order, unchanged RTT ownership and no dirty pixels, accept the new native fingerprints and reuse the exact texture. This avoids all rebaking for that safe case. Constant raster/background paint only; patterns, paint expressions/objects, transitions, custom layers, shading/color-relief and actual vector geometry retain native behaviour. Empty route buckets do not prevent photo reuse; populated constant-width vector lines MUST rebake at new zoom. Classification uses real current zoom (not an assumed0). Full style events clear caches normally.

Dirty content markers are cleared only after successful new composition, not while inspecting fingerprints: native one-per-frame throttling may defer some tiles. This prevents dirty pixel updates disappearing during simultaneous zoom/reload. Partial coverage still retains a valid last complete drape until imagery/elevation replacement readiness allows refinement. Quality, pixels, geometry and authentic sources unchanged.

3. Warm cache eviction was plain recency across actual viewed patches and unused prepared patches. An unused Prepare ahead patch could outlive a recently viewed one under later pressure. Entries now distinguish unused speculative compositions from previously viewed drapes. Evict unused preparation first for both warm-view and coupled DEM relief; normal viewed entries retain LRU. Adoption promotes a prepared patch through native selection; once viewed/parked it is no longer speculative. No separate pool/extra allowance or visible-resource eviction.

4. A further source-selection test showed a finished fine atlas could be discarded on return when only a coarser parent was active, despite the fine pixels already being cached. Record compact per-raster-source resolution requirements clipped to the terrain patch, alongside the finished atlas. For unchanged source revision/style and zoom-independent imagery, retain the complete sharper atlas while active replacement coverage is lower resolution or partially fine. Native source/DEM selection continues normally. Equal/finer complete source coverage can refine; actual dirty pixel updates and revision/style changes retain their refresh paths. The guard does not accept the coarse fingerprint or request follow-up frames: new data/events wake normal rendering. Prepared atlas metadata carries the same clipped requirements. No added image copy or source pinning. The quality retention is limited to existing valid cached pixels, not terrain measurements.

This extension follows the first saved/published milestone982ba7f0b1c8becbc97f9d76120158d6970f4206 (version46), whose live source/assets were externally verified before continuing.

## Diagnostics

Save imagery report already includes residency stats. New cacheReturns counters track restored, native draw-submitted, repainted, zoomReuse, qualityHolds, ignoredImageryEvents, speculativeEvictions, pending and fingerprint-reason checks while a returned patch awaits drawing. Last64 restore-to-first-native-draw-submission CPU durations yield p50/p95/max; bounded pending map256, cleared on drop/flush/teardown, cleans orphaned tiles. This is NOT GPU completion, screen presentation or physical cache-paging latency. Counters are passive; no new timer, render loop, requests, GPU readback/query or camera history/credentials. Existing source/byte-cache reuse/eviction counters remain. Diagnostic build label cache-return.

## Validation evidence

checks/cache-return.cjs reads the exact Git baseline above (must not silently change) and compares production adapters with actual shipped MapLibre6.14.0 Tile, TerrainTileManager covering/update, RTT source transforms/fingerprints and native StyleLayer objects. GPU painting is controlled, frame clock synthetic. Before/after:

- Plain same-zoom return already reuses texture:0→0 recompositions.
- Unused coarse imagery arrival:1→0 unnecessary recompositions on return.
- Tiny zoom-only change with constant imagery:1→0 unnecessary recompositions.
-40 alternating cached returns with changing fractional zoom/coarse background arrivals:40→0 recompositions; initial2 textures reused throughout after the fix.
- Cache-pressure sequence with unused prepared texture: recent-view recomposition1→0; unused speculation evicted instead, same byte limit.
- Coarse-only returning source selection: unnecessary composition1→0, same sharp texture retained; partially fine replacement also remains held. Full finer coverage refines normally, source revision overrides retention and no follow-up render spin is introduced.
- Actual participating pixel reload, source revision, zoom-dependent raster paint, populated constant-width route geometry all still refresh. Empty route, transitions, hidden provider and four simultaneous pixel reloads with zoom/native throttling covered.

CACHE_RETURN_VALIDATION.json stores the evidence. Synthetic16ms latencies in the report are fixture clock values, not measured device performance. Never advertise those as Chromebook timings or a measured whole-viewer FPS gain.

checks/terrain-composition.cjs additionally verifies a real native shader-dispatch prepared composition carries the safe zoom policy into native cache adoption. Existing coverage/partial atlas/zero-DEM protection, native/source residency, cache budget/radius/source prepare scheduling, bootstrap/planner/worker failure and optional Three cleanup regressions pass. Source/engine/imagery hashes remain intact. Managed Sites has no control-browser skill; no browser was improvised. User-device GPU/presentation behaviour remains unverified; diagnostics enable the next observation if a delay persists.

## Research and limits

Official MapLibre issue8251 documents why zoom-dependent style pixels must be rebaked; deliberately do not globally disable zoom invalidation: https://github.com/maplibre/maplibre-gl-js/issues/8251 . Issue8377/8400 explains RTT pool lifetime/memory tradeoffs: https://github.com/maplibre/maplibre-gl-js/issues/8377 . These are upstream history, not proof the same bugs remain in6.14.0. Our actual shipped code and deterministic baseline reproduce the adapter-specific problems. Life-of-a-tile: https://github.com/maplibre/maplibre-gl-js/blob/main/developer-guides/life-of-a-tile.md . No engine upgrade/vendor mutation was needed.

Remaining cache misses from genuine eviction/new LOD, valid style/source updates or GPU-driver memory pressure still take work. The native one-per-frame policy for legitimately stale drapes is preserved; eliminating unnecessary invalidation is the safer measured improvement before altering its GPU scheduling.

## Recovery / publication

Restore this Site through Sites source helper into an empty checkout if local files are pruned. Read this status, PREPARE_RADIUS_STATUS.md and CACHE_SLIDER_STATUS.md. All code/reports/status are Git-backed; full South Island imagery remains Git/R2. Run cache-return.cjs, terrain-composition.cjs, native-residency.cjs, loading-continuity.cjs, hierarchical-streaming.cjs, prepare-ahead.cjs, composition-scheduler.cjs and relevant regressions. Install dependencies through Sites helper if missing. Source helper owns final build-site.mjs, checks/base-production.mjs, Git commit/push and archive. Publish exact returned source/archive owner-private, keeping LIDAR storage and audience.

Fresh URLs: viewer27/native15/terrain-composition3/diagnostics17. app28/prepare-ahead3/tile-cache8 unchanged. Production guard requires return report plus built-source hashes. After publication run scripts/imagery/south-island/check-publication.py via hidden PTY with fresh Site origin/token. It checks module hashes, static imagery and immutable Cook range, and saves an external immutable source-commit publication proof. Do not rebuild imagery or depend on transient caches. If real-device delay persists, compare cacheReturns with native/source/byte evictions, waitingCovers, visible DEMs and captured frame timings from the report.
