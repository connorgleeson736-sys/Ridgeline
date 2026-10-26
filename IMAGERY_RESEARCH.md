# Ridgeline imagery investigation — 2026-10-16 UTC

## Current follow-up — 18 October 2026 Melbourne

The global Esri startup choice below is superseded. LINZ is now the default;
Esri is confined to authenticated fine-survey coverage gaps in the reviewed Cook
alpine area. See `REGIONAL_STREAMING_STATUS.md` and the geographic validation
reports for exact scope, provenance, costs and limits.

Primary sources reviewed for this change:

- https://basemaps.linz.govt.nz/docs/configuration/ — basemap configuration and
  individual dataset layers. We screened actual layer alpha coverage rather than
  interpreting regional names as proof of coverage.
- https://basemaps.linz.govt.nz/docs/user-guide/use-in-esri-software/how-to-add-individual-linz-basemaps-layers-to-arcgis/
  — accessing individual LINZ imagery layers for source-footprint inspection.
- https://maplibre.org/maplibre-gl-js/docs/API/classes/Map/ —
  `setSourceTileLodParams`: increasing levels and reducing ratio makes high-pitch
  distant detail decay more aggressively. We also tested the exact pinned 6.14.0
  source/render hierarchy, preserving surface/pivot detail locally.
- https://maplibre.org/maplibre-gl-js/docs/examples/level-of-detail-control/
  — the native source LOD controls; no renderer/engine replacement is needed.

Direct source evidence: Esri returns HTTP 200 with a grey no-data placeholder at
z19 in both inspected gap locations. Valid z18 parent imagery is therefore cropped
geographically at higher requested levels. HTTP success and successful decoding
alone are not evidence of a detailed aerial image. See
`REGIONAL_ESRI_ZOOM_VALIDATION.json`.

Native `_updateRetainedTiles` first enqueues the ideal set, then derives loaded
child/parent fallbacks. Sorting a copy of the same ideal set changes queue order
without replacing that fallback algorithm. Warm parent drapes can bridge a child
image-coverage gap briefly without GPU copies or simultaneous overlapping draws.
Controlled native tests, not a browser replay, establish those lifecycle results.

## Findings, with confidence

The previous publications did not fix the user's reported West Coast patch or
imagery unloading. Successful HTTP requests and constant-camera cache fixtures
were not sufficient evidence of a working live viewer. Do not repeat that claim.

1. **Confirmed local regression: unsafe failed-raster retries.** The pinned
   MapLibre 6.14.0 `Map.refreshTiles` -> `TileManager.refreshTiles` path reloads
   an errored tile as `expired`. `Tile.hasData()` / `isRenderable()` then return
   true even though the failed tile has no GPU texture. A raster draw can call
   `texture.bind` on undefined until the request completes. We introduced this
   path in automatic retries. `checks/imagery-recovery.cjs` reproduces the state
   window using the actual bundled Mercator transform, source manager and tiles
   at Fox coordinates. The replacement removes only the errored tile and marks
   sources dirty so normal selection creates a fresh `loading` tile. Parent
   fallback stays available until upload. Two retries max, existing backoff and
   auth/abort/404 exclusions remain. This confirms a regression, **not that it
   was the sole cause of the exact geographic patch**.
2. **Strong cause class: terrain LOD / drape selection rather than download.**
   Upstream #3983 and #5313 document lower or uneven raster detail with terrain,
   including near mountains and the assumption of candidate elevations relative
   to the navigation centre. #1515 records blurred rasters at a DEM coverage
   edge. Those reports concern older versions; they are leads, not proof of the
   same defect in 6.14.0. Camera-specific source/drape data is still required.
3. **Real invalidation distinct from eviction.** Terrain source data/style events
   can invalidate rendered drapes even when compressed bytes and source textures
   remain resident. Camera/LOD changes can request different terrain tile IDs,
   also producing drape misses without evicting previously seen source data.
   Therefore increasing caches cannot by itself prevent every apparent reload.
4. **Shared terrain/hillshade source:** the active style used the same DEM source
   for both. The pinned engine warns against this, and its official 6.14.0
   example uses separate sources. They now have independent managers but identical
   URLs, allowing the existing byte cache to deduplicate matching downloads.
5. **Potential regressions removed, not established root causes:** the previous
   custom fine-child promotion mixed native parent/child selection and scanned
   all cached imagery on each update. It is removed. Automatic same-XYZ LINZ to
   Esri fallback could introduce different survey colours/dates at hard tile
   boundaries; it is removed pending evidence of actual gaps. Source basemap
   switching remains explicitly available.
6. **Provider coverage/tint remains possible.** Previous authentic LINZ samples
   around Franz/Fox decode at z18, but sample success does not demonstrate uniform
   native resolution, imagery dates, or coverage across the entire region. Do not
   invent cliff imagery or replace successful LINZ tiles to hide a boundary.

## Changes and cost controls

| Change | Purpose | Cost control |
| --- | --- | --- |
| Colour/light rollback | Undo rejected cinematic grade, warm light and haze | Exact pre-cinematic `imagery.js`; no new shader/postprocess |
| Safe retry | Avoid treating a failed image as drawable | Only errored tile; ≤2 retries with backoff; no loaded-tile refresh |
| Separate hillshade manager | Remove terrain/hillshade source coupling | Same downloaded DEM bytes reused; visual-only maxzoom 16; 32 cached tiles / two viewport levels; geometry remains maxzoom 18 |
| Smaller hidden-request allowance | Stop hidden requests competing excessively with the new view | 24 -> 4 normally, 6 -> 2 with Save-Data; no new prefetch requests |
| Native LOD selection restored | Remove speculative child promotion / overlap behaviour | Removes O(cached tiles) scan and bounding-volume tests on every source update |
| Passive issue capture | Record the user's actual camera and source/drape state | 256-entry ring; source rows capped at 160; scans only on user export; no polling, GPU readback, image copies or extra requests |
| Retain bounded source/drape caches | Keep useful recent views | No memory-budget increase; existing expiry, source-change and lifecycle invalidation remain |

No device FPS, total GPU memory or exact West Coast visual improvement is claimed.
The separate hillshade source does introduce extra decoding / GPU resources when
its zooms differ from terrain: this is bounded and limited to visual shading.

## Exact evidence capture

In Original, open **Map layers -> Detail & loaded views -> Save imagery report**
while the artefact is visible. The JSON records camera, active/cached tile XYZ,
state, texture/DEM availability, byte-budget statistics, drape invalidations and
recent source events. It excludes source URLs, error messages, API keys and routes.
A screenshot plus this report is needed to distinguish the user's remaining
regional artefact from a survey boundary, bad DEM coverage, LOD selection, eviction,
or a rendering exception. Browser visual inspection was unavailable in this run.

## Primary sources reviewed

- https://github.com/maplibre/maplibre-gl-js/issues/3983 — terrain raster LOD and
  the performance rationale; discussion of shared hillshade/terrain sources.
- https://github.com/maplibre/maplibre-gl-js/discussions/5313 — uneven mountain
  texture LOD, elevation assumptions and source tint across zoom levels.
- https://github.com/maplibre/maplibre-gl-js/issues/1515 — historical DEM-edge blur.
- https://github.com/maplibre/maplibre-gl-js/issues/7775 — related failed-raster
  promotion to renderable state during source reload; fixed upstream for setTiles.
- https://github.com/maplibre/maplibre-gl-js/issues/8612 — current issue index
  explicitly lists the refreshTiles/errored-raster bind failure; issue body was
  unavailable through web retrieval. The local native reproduction is stronger
  evidence for this project's pinned build than assuming the setTiles fix applies.
- https://maplibre.org/maplibre-gl-js/docs/examples/3d-terrain/ — separate
  `terrainSource` and `hillshadeSource`, official example pinned to 6.14.0.
- https://github.com/maplibre/maplibre-gl-js/issues/8377 — historical unbounded RTT
  recycle pool, fixed by #8400. Do not assume this older bug remains in 6.14.0.
- Local npm MapLibre 6.14.0 source and exact bundled public engine: checked
  `TileManager._reloadTile`, `refreshTiles`, `Tile.hasData`, TerrainTileManager,
  RenderToTexture fingerprints and Map's terrain data invalidation handler.

## Validation / recovery

Run `node checks/imagery-recovery.cjs`, `node checks/imagery-diagnostics.cjs`,
`node checks/native-residency.cjs`, `node checks/raster-residency.cjs`,
`node checks/imagery-retention.cjs` and planner/bootstrap/production checks.
`IMAGERY_RECOVERY_VALIDATION.json` records the native failed-tile reproduction.
`RASTER_RESIDENCY_VALIDATION.json` now tests native LOD selection unchanged rather
than claiming the removed fine-child promotion remains enabled. Historical
cinematic reports are historical, not the active appearance. Use the Sites helper
for build/push/archive, preserve owner-private access and vendor hashes.


## Follow-up: why looking away can lose a finished view (2026-10-16 UTC)

Reviewed the shipped 6.14.0 source directly in the extracted npm package:
`src/ui/map.ts` `_handleTerrainDataEvent`, `src/tile/tile_manager.ts`
`_removeTile`/`updateCacheSize`, `src/tile/terrain_tile_manager.ts` `releaseRTT`,
`src/source/raster_dem_tile_source.ts`, and `src/data/dem_data.ts`.
The official [MapOptions documentation](https://maplibre.org/maplibre-gl-js/docs/API/type-aliases/MapOptions/)
also explains viewport-dependent cache sizing and the difference between
retaining pending zoom requests and cancelling them. Keeping pending zoom
requests alone does not change off-screen removal or rendered-texture lifetime.

Reproduced two more independent causes with the exact bundled native classes:
1. The additional residency adapter accepted raster imagery only. Decoded DEMs
   still followed native viewport-based eviction, and loading DEMs could still
   be aborted when removed. Added a separate byte-bounded decoded DEM pool,
   shared with hillshade/fallback and accounting for CPU/worker/GPU resources.
2. Native terrain data events release imagery drapes even for a geometry-only
   DEM arrival. Since that source supplies displacement, not colour, this need
   not invalidate an existing colour RTT. Keep native terrain bookkeeping and
   suppress just that release. This shortcut is disabled if the DEM is referenced
   by any style layer or there is a custom layer. Style and real colour-source
   invalidations remain unchanged.

This is not a reason to disable frustum culling: off-screen tiles should stop
being drawn, while reusable data/textures stay cached within memory limits.
Tests and limits are recorded in `TILE_UNLOADING_VALIDATION.json` and the latest
`BASE_VIEWER_STATUS.md` section. No browser visual confirmation is available.


## Recording-led evidence (17 October 2026 Melbourne)

Matched source inspection confirms the low-detail blue/green West Coast cutline
is in LINZ delivery itself, not failed downloads. The initial view now uses the
existing Esri source, keeping LINZ aerial selectable and all LINZ elevations.
See IMAGERY_SOURCE_BOUNDARY_VALIDATION.json and RECORDING_FIX_STATUS.md.

Authentic DEM maxima are 3056 m at z5 versus 3717.3 m at z16 over the summit.
The exact native frustum traversal falsely rejects measured mountain sections
with coarse extrema. An ancestor min/max hierarchy and bounded culling margins
correct 10 such camera configurations in the regression check. Elevations and
meshes are unchanged; max candidate count rises from 51 to 56 in the fixture.
See DEM_CULLING_SOURCE.json and TERRAIN_VISIBILITY_VALIDATION.json. Browser pixels
and device FPS remain unverified.
