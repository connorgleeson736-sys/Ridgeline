# Aoraki route atlas — 18 October 2026

## Delivered

- Eight documented mountaineering climbs in the existing viewer, alongside the user route sketch: Linda Glacier, Zurbriggen Ridge, Grand Traverse, Middle Peak East Ridge, Silberhorn Arête, Tasman North Shoulder, Sefton West Ridge, and Hicks–Dampier Traverse.
- Eighteen summit destinations/labels (14 in Aoraki Tai Poutini, four retained national destinations). Thirteen additional regional labels; the Aoraki position now uses the source coordinate rather than the previous rounded point.
- Compact route directory with source links, commitment / technical grades, editorial summaries, omitted-section notes and conditions context. Search includes climbs and summits. Map line selection opens its entry without moving the camera. Atlas geometry never becomes an editable or exported sketch implicitly.
- Collision-aware label spacing, zoom thresholds, selected-summit priority and native terrain occlusion. Native label decisions throttled to 120 ms while moving; no glyph service, thumbnail downloads, added framework or continuous native animation loop.
- Simplified planner typography, controls, label styling and muted surfaces; map remains full-screen and drawer closed at startup. Original renderer remains default; Three.js retains route atlas and labels, with bounded dashed segment geometry and disposal.
- Small LINZ shader change: contrast .26 → .28, brightness minimum .07 → .075. See ALPINE_ATLAS_COLOUR_VALIDATION.json. Esri keeps its previous reference appearance through derived compensation. Raw mode remains unchanged. All 381 existing source snapshots reused; 98 low-zoom coordinate pairs rebuilt/validated as 130 deduplicated WebP files (10,180,424 bytes).

## Source and accuracy contract

Sources reviewed through public NZAC / ClimbNZ pages on 18 October 2026, with DOC Aoraki / Mount Cook park and Ball Pass guidance as supporting context. Exact links and source grades live in public/alpine-data.js. Additional location references:

- https://climbnz.org.nz/nz/si/aoraki-tai-poutini/minor-grand-plateau-peaks/cinerama-col
- https://climbnz.org.nz/nz/si/aoraki-tai-poutini/sierra-range/welcome-pass
- https://www.doc.govt.nz/parks-and-recreation/places-to-go/canterbury/places/aoraki-mount-cook-national-park/
- https://www.doc.govt.nz/parks-and-recreation/places-to-go/canterbury/places/aoraki-mount-cook-national-park/things-to-do/tracks/ball-pass-crossing/

Route descriptions do not provide surveyed GPS tracks. The ClimbNZ API/schema and direct HTML requests were unavailable (HTTP 403); public indexed route pages were reviewed. **Every drawn corridor is an editorial, manually placed schematic adaptation** of named terrain and written descriptions. Some endpoints use published peak/pass coordinates; intermediate vertices are approximate, unverified and have no measured positional accuracy. No digitisation from a georeferenced route topo, recorded track validation or field validation is claimed. Glacier lines and bypasses vary with conditions. They are expressly labelled “Indicative corridors · not GPS tracks” in the directory and persistent map legend; approach/descent omissions are stated per entry. Source grades are historical descriptions, not current condition assessments. No timing, safe-line, current-access or complete-trip claim is made. Corridor GPX export is intentionally absent; the existing independent user sketch export remains.

Published summit coordinates and heights follow the linked ClimbNZ records. These can differ from older maps, notably Lendenfeld (3149 m on the source) and Aoraki Middle Peak (3706 m on the cited Otago survey). The existing four national destinations are retained, not newly surveyed/researched.

Adapted route summaries, sourced location dataset and editorial corridor adaptations: NZAC / ClimbNZ, Aoraki Tai Poutini (Rob Frost, 2018), CC BY-NC-SA 3.0 New Zealand. Source and licence attribution appears in the directory and native map credits. The map is a planning visualisation; current forecasts and local reports remain external linked sources.

## Architecture and recovery

- Data: public/alpine-data.js (UMD); stable original peak indices 0–4 preserved.
- Screen-space label selection: public/alpine-labels.js, shared by both renderers.
- Directory / native overlays / shared selection: public/app.js. One small GeoJSON source and two native line layers. Drawing sources remain separate. The atlas restores on style load.
- Optional GPU viewer: public/three-renderer.mjs; setAlpineRoutes(), terrain-sampled discontinuous segments, hidden below zoom 10, resource cleanup on teardown. No terrain or point-cloud asset changes.
- Cache contract: natural tiles use ridge-grade=snow-v2 throughout app, regional dispatcher and worker. The worker imports imagery.js?v=8. Source routing, valid-WebP MIME fallback, West Coast footprint and retention budgets are retained.
- Overview source snapshots remain in qa/alpine-overviews-2026-10-18/source. The durable ALPINE_OVERVIEW_PROGRESS.json includes the intentional grade-only fingerprint migration with the previous fingerprint and all source checksums. Resume: python scripts/imagery/precompute-overviews.py --stage build. Do not change colourCode fingerprint without an intentional reviewed rebuild.
- Opening bootstrap: viewer.mjs?v=18; app.js?v=22; style.css?v=14. Overviews and colour imports versioned to prevent old cache reuse.

## Validation

Passed: checks/alpine-atlas.cjs, base-viewer.cjs (inline, successful worker, failed worker fallback, actual ESM bootstrap), three-planner.cjs (renderer adapter mocked), three-alpine.mjs (actual shipped Three.js CPU geometry/material classes: 352 dashed-line vertices, finite geometry, missing-height skipping, disposal), overview-routing.cjs (all 130 real WebP containers plus generic production MIME and cache/failure paths), overview-assets.py (381 source and output checksums, independent area averages/edge preservation), regional-imagery.cjs, regional-worker.cjs, snow-grade.cjs, and git diff --check. Native style schema validates every added overlay/expression. CPU inverse-grade error below 0.64/255 for neutral snow/rock/ice sample gamut.

Managed Sites browser-control capability is unavailable. No live browser screenshot, real device interaction, terrain occlusion visual test, Three.js GPU draw, frame-rate benchmark or field accuracy validation has been performed. Publication is verified by the Sites deployment response; build checks validate packaged assets and imports. Do not describe this as a navigation-verified route database.
