# Regional imagery and streaming — 18 October 2026 Melbourne

Project: `appgprj_6acc83afb6588191aff43dc542d98b5f`.
Production: https://ridgeline-nz.connorgleeson736.chatgpt.site . Preserve owner-private access.
Original MapLibre 6.14.0 remains the startup renderer. Three.js is optional.

Later precomputed imagery overviews are documented in `ALPINE_OVERVIEW_STATUS.md`.
They replace only the low-zoom source switch around Cook, preserving this
regional gap mask and streaming policy.

The later requested snow/rock colour adjustment is documented in
`SNOW_COLOUR_STATUS.md`. It supersedes original colour-setting descriptions below;
the original photographs, regional footprint and streaming policy remain.

## Current result

LINZ aerial is restored as the default and startup-visible layer. Its original
colour/shading settings, elevation measurements, routes and profiles remain.
Esri replaces only the reviewed alpine survey gaps west of Cook, not all West
Coast tiles and not the whole map. The full 1.7 km Cook model, Tasman and the
sampled good Fox/Franz Josef survey areas retain LINZ. The explicit Satellite
layer remains available as a user-selected alternate basemap.

## Geographic evidence and recovery

The review rectangle is `[169.96, -43.665, 170.245, -43.44]`. It restricts the
investigation; it is **not** the replacement shape. Actual alpha footprints from
LINZ's fine survey layers determine that shape. Of 16 regional layer candidates,
three intersect the review area: Canterbury rural 2015–2016 0.3 m, West Coast
rural 2016–2017 0.3 m and West Coast 2024–2026 0.2 m. All 144 screening and 192
footprint requests succeeded. No unknown/failed survey is treated as absent.

`WEST_COAST_SURVEY_FOOTPRINTS.json` records names, XYZ, alpha coverage, byte sizes
and hashes. `WEST_COAST_REGIONAL_VALIDATION.json` records the final mask and point
checks. The mask uses about 13.85 m ground pixels, a two-pixel inward guard and
four-pixel inward feather. It has zero positive opacity in observed fine LINZ
coverage. This is a conservative sampled boundary, not a centimetre-exact survey
polygon. No aerial photograph is generated, sharpened or AI enhanced.

Rebuild with Python, Pillow, NumPy, SciPy and Matplotlib:

```bash
python scripts/imagery/coverage.py
python scripts/imagery/build-mask.py
```

The scripts resolve the repository themselves. `RIDGELINE_QA_ROOT` optionally
selects a reusable download cache; the default is ignored `source-cache/regional-imagery`.
An optional `LINZ_API_KEY` overrides the existing public viewer key. Keys/URLs
are not logged. Each tile is written through a temporary file and reused on
restart; progress snapshots are atomic. Screening/footprint failures stop mask
generation. The saved regional configuration snapshot fixes the candidate list;
review newer LINZ configuration before deliberately updating that list.

`qa/regional-2026-10-18/` contains the labelled footprint and authentic-source
reference mosaics. They are review evidence only and must not ship as site assets.
Reference geospatial compositing left all outside-mask RGB pixels and the Cook
square unchanged. Browser canvas/GPU pixels have not been compared.

## Runtime and cost limits

`regional-imagery.js` intercepts only the LINZ aerial protocol:

- Unaffected tiles return the original LINZ response with no additional fetch or
  image processing. Fully inside-gap tiles through z18 use one Esri request.
- Boundary tiles fetch both providers, blend under the geographic opacity mask
  in a lazy worker and encode lossless PNG. Completed results use the existing
  bounded compressed-image cache, so revisits do not repeat that composition.
- Two composition jobs run at most. One decoded 2048² alpha atlas costs about
  16 MiB only after a boundary job needs it. Worker failures/timeouts reject jobs;
  subsequent work can use OffscreenCanvas on the main thread. Failed mask loads
  can retry. Page teardown rejects pending work and terminates the worker.
- Esri z19 returned a grey “Map data not yet available” image with HTTP 200 at
  both inspected gap locations. Esri requests stop at verified z18 and higher
  LINZ-grid requests crop the correct z18 quadrant. LINZ elsewhere retains z19.
  This prevents those confirmed placeholders, not every possible provider outage.
- Cache TTL respects upstream max-age, Age and no-store/no-cache. The original
  LINZ/Esri attribution is displayed. Provider date/colour differences can remain
  at transitions despite the inward feather; do not promise identical surveys.

The optional Three.js renderer shares the same LINZ imagery cache/routing.

## Hierarchy and streaming

Native frustum and source tile selection stay authoritative. Source LOD decay
is stronger: normal DEM 8/3.5 and imagery 6/3.5 (levels/ratio); low-power DEM 9/3
and imagery 7/3. Native rendered-terrain decay is 10.3/2.75. Fine detail is
protected around the first measured centre-ray surface and a smaller area around
the navigation pivot. Imagery detail bias is local rather than applied globally.

Requests for the viewed face and pivot precede distant fine requests. Very coarse
DEM fallback is requested first. Sorting changes request order, not the ideal
tile set; no speculative fine prefetch or new draw pass is added.

When an already-rendered parent is available but its new child group lacks image
coverage, keep that parent's existing drape for at most 1.8 seconds per active
transition. Remove its descendant render entries while held: no parent/child
overlap and no extra GPU allocation for the hold. Then use the normal native
renderer. This reduces one refinement gap; it cannot guarantee invisible LOD
changes or retain unlimited views. Existing independent byte-bounded imagery,
DEM and warm-drape pools, safe failed-raster retries, geometry-only invalidation
shortcut and measured-extrema culling guard remain in place.

The existing imagery report includes regional routing/composition counters,
queue depth, cache statistics and `continuityHolds`; it contains no source key.

## Validation and remaining limits

The actual native CPU covering fixture selected 139 DEM tiles versus 162 across
four camera scenes (14.2% fewer), preserving viewed-face and pivot zoom levels.
Close-scene distant tiles dropped one zoom level. This is a tile-selection result,
not a measured browser FPS or download-time improvement.

New checks: `regional-imagery.cjs`, `regional-blend.cjs`, `regional-worker.cjs`,
`hierarchical-streaming.cjs`. They cover geographic routing, unchanged responses,
z18 parent cropping, compositing orientation/alpha, cache reuse/TTL, worker import
and bitmap cleanup, teardown, request ordering, bounded parent holds and no
overlap/extra allocation. Corresponding JSON reports sit in the repository root.

Regression checks passed: base viewer without workers, worker success and failure;
actual ESM bootstrap; base elevation/performance; native/raster residency; imagery
retention, recovery and diagnostics; original streaming; surface focus; terrain
visibility; tile unloading; optional Three.js planner/data. Build and production
asset verification run through the Sites workflow before publication.

No live browser or GPU/FPS measurement is available in this environment. Remaining
visual loading errors require an on-device replay/imagery report. Do not describe
CPU fixtures as a replay of the user's recording or claim every error is solved.

For the next agent: read this file and `BASE_VIEWER_STATUS.md`, inspect the pushed
HEAD/current Sites deployment, run the relevant checks, and use the Sites helper
for exact-source build/push/archive and private publication. Do not hand-edit the
vendor engine or commit obsolete custom meshes. All current code, mask, evidence,
tests and recovery scripts belong in this site's source repository.

## 18 October — South Island extension

See SOUTH_ISLAND_IMAGERY_STATUS.md for the replacement overview delivery and durable recovery. Fine LINZ/Esri gap routing and all existing LOD/residency policies are unchanged. Directional preloading is bounded byte-cache warmup only. Saved tile failures retain native parent coverage instead of silently substituting the old differently coloured 10 m mosaic. Static coarse copies fall back to exactly the same R2 image bytes. Device GPU/FPS verification remains unavailable.

## 18 October — loading continuity / higher tilt

The later LOADING_CONTINUITY_STATUS.md documents selective geometry/imagery disappearance fixes based on the user's 21:32 recording. Residency lifecycle adapters now protect DEMs needed by visible/warm terrain, keep complete old coverage until replacements are ready and defer incomplete colour refreshes. Max pitch is85deg. Source imagery, geographic measurements and existing LOD selection are unchanged. The old six-module byte-identical residency statement applies to the earlier South Island release; current LOD selection modules remain identical, while residency has intentionally changed. See LOADING_CONTINUITY_VALIDATION.json and updated native/hierarchy reports.
