# Authoritative 0.18 audit and migration ledger

Audit date: 2026-10-29. Artifact branch independently rechecked with `git ls-remote
origin refs/heads/android-preview-download`: 39431dad230945919a7b7d9d0ef2e86db8492ead.
Source: d880143418ac46ece98adff0b3b2fbaedf044d46. No 0.19 experiment is included.

## Application and visual reference

The Capacitor app under `capacitor/` packages the patched `public/` application.
Its default renderer is patched MapLibre GL JS6.14.0, with optional Direct imagery
sampling on that engine's own terrain. The older `android/` app uses Compose and
MapLibre Native13.7.1 flat imagery/hillshade; it is not the terrain reference.
Existing Three/Luma/LiDAR viewers and historical optimization reports do not
supersede the active 0.18 app. The native target contains no JavaScript runtime.

Initial view (`public/alpine-data.js`, `public/app.js`): Aoraki at
170.14219572,-43.5955671; camera zoom11.9, bearing-24°, pitch58° (45° low-power),
center elevation3724m, exaggeration1. Zoom limits4–19, max pitch85°, geographic
bounds165°..180° / -49°..-33°. FOV36.86989764584402°, camera-to-center distance
1.5×CSS viewport height. Device pixels must scale the entire camera consistently;
native Surface dimensions cannot be used with unadjusted CSS zoom. Native app
now scales render zoom by log2(display density); route/UI pixels still need parity.

MapLibre altitude uses mean Earth radius6371008.8m; do not use the EPSG:3857 radius
for altitude. Native screen-position fixtures test 1,712 camera cases; padding,
roll, fitBounds easing, terrain collision and gesture dynamics remain to migrate.

Terrain defaults to LINZ elevation PNG with explicit `pipeline=terrain-rgb`,
tileSize256, original source zooms and heights. Coarse Mapzen Terrarium source at
s3.amazonaws.com/elevation-tiles-prod/terrarium (maxzoom12) is a visible provider
failure fallback, not the normal terrain. Nodatas must not turn into zero heights.
Native data path now uses actual same-source parents on 404/nodata and real
neighbor halos. Synthetic geometry exists only in labeled tests.

Default mesh granularity128; reference mesh adds lowered boundary skirts through
`u_ele_delta`. The native prototype retains the128-cell heightfield and now adds lowered
boundary skirts using the exact mean-Earth-circumference / 2^logicalZoom /5 rule.
Physical framebuffer zoom is passed separately so density does not shorten skirts.
A controlled mixed-LOD GPU fixture closes36092exposed background pixels; original
surface vertices/triangles and full photo pixels are preserved. Geometry vertex
storage doubles to399384bytes/source, within the existing hard source-count cap;
combined peak memory and device impact remain unmeasured. Shared same-LOD endpoints
also have deterministic halo tests. This does not establish real-source cross-LOD
visual acceptance, morphing/fades or terrain collision.

Default Mist sky/background is #bdcbd0. All sky/horizon/fog colors use the selected
preset. Sky paint: fog-ground-blend1, horizon-fog-blend0, sky-horizon-blend0,
atmosphere-blend0. Terrain fragment applies gamma2.2 fog blending when enabled;
actual default opacity/transition must be captured. Hillshade is optional, off by
default, Igor method,315° direction/45° altitude/exaggeration.16. No invented
Lambert lighting may replace the reference. Native prototype grades imagery but
has no hillshade or accepted horizon/fog transition yet.

## Imagery routing and original bytes

`public/imagery.js` defines LINZ natural grade: saturation-.25, contrast.26,
brightness minimum.058/maximum1. Satellite differs: saturation-.12, contrast.015,
brightness maximum.99. Natural off must restore raw shader values and raw cached
variants. Gap wrapper receives raw Esri for interiors and composites; preserve its
actual grading chain, not the intent inferred from comments about compensation.

The outer `gap-imagery.js` wrapper chooses LINZ, Esri interiors, coarse overlays,
or boundary composites from 630 official z13 atlas entries. Boundary pixels use
pixel-centre even/odd vector masks (including holes); fine survey PNG alpha vetoes
Esri along seams. Esri source ceiling is18; z19 crops the correct parent. Fine
supplement failure retains original imagery in the packaged preview patch.
Native Kotlin selection/masks match 2,520/360 original algorithm cases exactly.
Bitmap interpolation, alpha rounding and photographic colors still need captures.

For LINZ base responses, the packaged Capacitor build checks bundled South Island
overview ranges/coarse files, then upstream LINZ. It explicitly strips the website
Cook reference selector and excludes all Cook substitution assets. `RidgeGapAtlas` + wrapper suppresses the legacy West Coast
fallback path. Do not accidentally apply both geographic compositors.

Six overview ZIPs contain six immutable .bin payloads (364,762,502 original bytes).
Full APK bundles all six original ZIPs uncompressed for direct payload range access, plus67 coarse
images, coarse gap overlays. 5,255 range coordinates include
raw/natural variants. They are imagery coverage, not offline DEM or all-NZ data.
Native range/bitmap proof runs on Android as well as host hash verification.
Earlier prototype Cook fixtures were website-only and prove GPU mechanics, not
Android reference appearance; the current fixture uses original bundled pack tiles.

## Routes and interaction contracts to migrate

- Saved route store `ridgeline-routes-v1`; valid finite coordinates and ≤2,000
  points. Preserve autosave failures as visible errors. No silent original-store
  migration: native app has separate private storage and explicit GPX interchange.
- Naming autosave400ms/on blur; geometry edits autosave immediately. Empty/one-point
  routes have deliberate history/save/profile behavior; use the existing tests.
- Drawing toggles map double-click zoom; append requires NZ bounds, ≤2,000 points,
  at least5m from previous point, six decimal coordinates. Peak navigation is
  disabled during drawing. Point selection enters editing; selected delete and
  undo restore selection/geometry as the reference does.
- `route-interactions.js`: waypoint selection/drag; projected nearest segment
  insertion; touch slop, pointer capture/cancellation; one undo snapshot per drag;
  coalesced geometry preview and canceled profile sampling during movement.
- Active route white6px/.85 halo + orange #e87944 line3px; round caps/joins.
  Unselected waypoint4px, selected6.5px; selected fill#d87549; offwhite#fafaf5
  otherwise; outline#d87549. Native density and draping/occlusion need comparison.
- `capacitor/mobile/routes.js`: saved cards with miniature geography, rename,
  delete, open/close multiple routes; passive routes#518999/3px/.9; combine in
  selection order with per-route reversal and exact adjacent dedup; 2,000 limit.
- GPX import/export uses actual current app validation, not older Android limits.
  Current features include Android sharing and document chooser. Preserve names,
  coordinate precision and waypoint/track semantics; keep limits and malformed
  inputs explicit in migration tests.
- `geo.js` + `terrain-core.js`: true metre distances independent of exaggeration,
  overview plus refined visible DEM sampling, exact waypoints, cancellation and
  missing-value gaps. Totals use three-sample averaging and5m anchor threshold.
  Profile decimation retains per-pixel min/max and gaps. Hover/selection uses a
  cached distance locator; profile/map linked marker and interval statistics.

No native planner/profile UI has been claimed complete. Old Compose model/storage
is potentially reusable, but its GPX limits and current interaction semantics
must be reconciled against these sources and tests before migration.

## Overlays, labels and settings inventory

`topographic-overlays.js`, `linz-topo.js`, `alpine-labels.js`,
`imagery-boundary.js`, `topographic-overlays` settings and mobile controls govern
contours, tracks, huts, names, imagery source boundaries, summit labels, collision
priorities and selected peak. Their ordering, draping and visibility rules need
native equivalents and reference screen captures. Preserve photographer/provider
attribution and LINZ CC BY4.0 attribution. Basemap choices include LINZ, satellite,
topo; hillshade, sky presets/custom color,2D/3D, elevation exaggeration and labels
are user-facing settings. Preserve location and saved/offline region interactions.

Loading controls expose selection intervals/concurrency, device/phone/detail
budgets, adaptive terrain and movement detail, imagery fade and preparation ahead.
Carry performance intent into the custom pipeline without retaining browser-only
knobs that have no native meaning; any product-facing changes need documenting.

## Trace/performance evidence informing native architecture

Reopened original supplied JSON (build a21b411…, not 0.18): movement sample≈9.93fps,
frame p95≈183.8ms, CPU submission p95≈18.3ms,35 decode samples p95≈442.2ms,
68 admission samples p95≈3162ms; no GPU timer samples. LINZ selection143 calls,
3142.5ms inclusive; terrain selection2030.1ms inclusive; texture preparation
1148.5ms inclusive. Nested totals overlap and cannot be added. They do not predict
native speed. Original system trace has no sampled JS function attribution.

Native response: one render owner, cached partition updates on residency changes,
bounded direct buffers/caches/uploads, source decisions in native data code,
priority/dedup requests and no JS/bridge image-delivery loop. Do not assume these
remove GPU bottlenecks or establish a speed ratio. Current emulator has no KVM,
software graphics and slow system boot; timing is unsuitable for phone claims.

## Next audit/proof gates

Obtain real DEM and same-view reference captures once network configuration is
published. Verify all patch boundaries, natural/raw modes, shadows, sky/horizon,
exact touch/inertia/terrain collision, cross-LOD seams and no texture popping.
Then migrate route/UI features using golden state transitions and installable
checkpoints. Physical-phone cold/warm/movement/settled traces are required for
performance comparisons; list device, build, settings and exact workload.
