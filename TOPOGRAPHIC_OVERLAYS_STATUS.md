# Ridgeline topographic overlays — 2026-10-20

Implemented over the existing Original / MapLibre viewer, nationwide within existing NZ map bounds. Existing satellite imagery, elevation, route sketches/profiles, loading controls and Three.js viewer are retained.

## Architecture and UI

`public/topographic-overlays.js` uses one native vector source: LINZ `topographic-v2/3857/{z}/{x}/{y}.pbf`, max source zoom 15 (verified against live TileJSON). Uses existing session Basemaps key; no new key or DOC developer account. LINZ-hosted Open Sans glyphs; verified HTTP 200 and CORS. Native workers decode, native tile cache/viewport selection/overzoom handle streaming. No frame loop, extra DEM, nationwide GeoJSON download or custom pan fetcher.

Map layers → Topographic overlays: place/water names, walking tracks, huts/bivouacs default on; contours default off. Saved on device as `ridgeline-topography-v1`. Features appear progressively: water overview z7, places z9, peaks z10, huts/lakes z11, walking tracks/rivers/index contours z12, detailed contours/labels z14. Collision-managed labels use fixed legible screen sizes. Tracks use restrained gold dashes, mapped closed tracks muted red with explicit suffix. Overlays inserted below the user's sketch route. No aerial colour/geometry changes.

Official peak names replace the small curated DOM label set after vector content loads to avoid duplication. Existing Peak labels switch controls peak labels; the peak list/navigation remains available. Existing DOM labels provide initial/error fallback. Native topographic raster mode suspends overlays to avoid duplicate baked labels; aerial mode restores them. Three.js disables overlay controls with explanatory text. API key changes update vector tiles. Source failure reports status without changing imagery/terrain. Listener cleanup handles style rebuilds and renderer replacement.

These are LINZ mapped tracks, not a comprehensive technical mountaineering route database, current DOC closure feed or accessibility guarantee. UI links DOC alerts. Huts are mapped locations, not current booking/operational status. No old handwritten climbing routes restored.

## Validation and limits

- `node checks/topographic-overlays.cjs`: native style-spec validation, real-data production filters, mapped-closed material expression, independent toggles, persistence, API key changes, source error recovery, basemap transition, listener cleanup, imagery preservation.
- Four authentic, gzip-compressed LINZ PBF fixtures in `checks/fixtures/topography/` verify Aoraki/Mount Cook, Mount Dampier, Mount Vancouver, Hooker Track, Stocking Stream, Mueller Hut, Lake Pukaki and contours. Attribution/provenance in fixture README. `python checks/read-topographic-fixtures.py` regenerates property extraction.
- Expanded 29-gate fast audit, actual ESM bootstrap and worker-failure planner fixture pass.
- Browser visual QA blocked by managed preview infrastructure: existing Vinext lock at PID 14, `/site`; supervised preview reports stopped. Do not kill unrelated server or alter working app configuration. No device FPS or visual-quality claim measured this turn.
- Additional vector CPU/GPU memory uses native engine allocation; the existing imagery/DEM MiB slider does not account for this separate source. Dense contour work is optional and zoom-gated. No claim of zero performance cost or faster terrain.

## Recovery / publication

Use existing site `appgprj_6acc83afb6588191aff43dc542d98b5f`, checkout `/workspace/sites/ridgeline-base-viewer`. Source helper owns Git commits/push/package. Refresh and preserve the current audience (public as checked 2026-10-20); never change sharing to fit a deployment method. Generate runtime fingerprint after changes: `node checks/build-identity.cjs`; build via Sites build helper; run `node checks/base-production.mjs`. Module versions: viewer34, app33, topographic-overlays1, build-info6, style17. Source helper's pushed commit and matching archive identify publication. All source, fixtures and validation/status records committed together.


## Label hierarchy update — 2026-10-20

All existing vector label categories now have native zoom priorities. Place/peak rank uses LINZ `admin_level`, with a missing/non-numeric fallback to 13. This is a cartographic display rule, not an elevation/prominence/difficulty classification. Schemas for `pois` and `streets` have no corresponding rank; huts/tracks therefore use explicit display rules, not a fabricated API importance score.

- Peaks: ranks <=6 at z9; ranks >6 and <=8 at z12; others at z14. Disjoint filters keep exactly one label per feature. Major names 13 px, secondary 12 px, minor 11 px. Existing Peak labels checkbox controls every tier.
- Cities and major islands at z7; towns/other islands z9; localities/villages z12; suburbs z14.
- Lake overview names: ranks <=4 z7; <=8 z10; remaining z12. All overview tiers end at z13, when detailed named lake polygons begin, avoiding duplicate overview labels.
- Named huts at z12. Bivouacs/unnamed huts and their markers at z14. This says nothing about their current availability or safety.
- A small explicit list of main track names (including exact Hooker Track and common Great Walk spellings) receives labels at z12. Other named walking tracks at z14. This is an editorial display-priority list, not a completeness claim or an API trail ranking. Closed-track colour/text stays intact. Track lines and user route sketches retain their existing visibility.
- Broad river polygons z13; river-line names z14. Index contours remain z12; minor contours and height labels z14.

Native collision detection remains on. Native sort keys order features within a layer, with lower ranks preferred within place tiers. No claimed global cross-layer collision guarantee. No camera-move listener, per-frame scan, new fetch or extra source added; built layer specifications cached once per attachment. Symbol count may change CPU placement costs, but no device FPS benchmark is claimed. Line geometry and imagery/DEM selection/caches are unchanged. Optional contours remain off by default.

`checks/label-priority.cjs` exercises boundaries, exclusivity, missing ranks, authentic Aoraki/Dampier/Vancouver data, all label categories and collision rules. Existing overlay tests cover layer toggles, lifecycle and imagery preservation. Browser visual QA remains unverified because the managed preview was blocked by an unrelated existing Vinext server lock. Current module URLs: viewer35, topographic-overlays2, build-info7; app33 unchanged. Fast audit expanded to 30 gates.
