> Startup viewer: **Original MapLibre**. Three.js GPU terrain remains an optional renderer. See [BASE_VIEWER_STATUS.md](BASE_VIEWER_STATUS.md) and [THREE_VIEWER_STATUS.md](THREE_VIEWER_STATUS.md).

# Ridgeline base viewer — 2026-10-15

## Active scope

The user selected **main viewer only**. All custom mesh viewers, experimental
renderers and their public datasets are retired. `/cook-model`, `/cook-comparison`,
`/cook-model-notes`, `/terrain-lab` and `/lidar-summit` redirect to the main planner,
including their former `.html` aliases. No new satellite images or AI imagery
were generated for this update.

The project is `appgprj_6acc83afb6588191aff43dc542d98b5f`. Preserve its owner-private
audience. Published origin: https://ridgeline-nz.connorgleeson736.chatgpt.site .

## Latest: confirmed overview-delivery defect — 2026-10-18 Melbourne

The user's unchanged imagery was reproduced at the delivery contract: production
returns valid saved WebP bytes as `application/octet-stream`; the loader's
`image/*` guard rejected them and silently fetched old LINZ imagery. The loader
now validates the actual WebP container instead. Authentic production bytes pass
the actual registered protocol/cache with zero fallback. Real-byte/octet-stream
regression and malformed/HTML fallback checks pass. See
`OVERVIEW_DELIVERY_VALIDATION.json` and `ALPINE_OVERVIEW_STATUS.md`. Existing open
pages need one reload. Browser/GPU appearance remains unverified.

## Previous: precomputed Cook imagery overviews — 2026-10-18 Melbourne

See [ALPINE_OVERVIEW_STATUS.md](ALPINE_OVERVIEW_STATUS.md). Zooms 0–12 covering
the Cook alpine area now use downsampled zoom-13 photography, avoiding the
zoom-driven 10 m colour switch while preserving coarse distant HLOD. A roughly
57 km source square has an inward edge fade. Both colour modes and the reviewed
Esri patches are included. There are 98 coordinates / 130 deduplicated lossless
files (10.18 MB), loaded individually through the existing cache, with safe
native imagery fallback. Fine imagery, DEMs and planning are unchanged.
All source snapshots, progress, output hashes, recovery scripts and quantitative
checks are retained in source control. Source/pixel/runtime checks pass; live
browser appearance and device FPS are unverified.

## Previous: requested LINZ snow/rock colour — 2026-10-18 Melbourne

See [SNOW_COLOUR_STATUS.md](SNOW_COLOUR_STATUS.md). The Original viewer now
brightens LINZ snow toward the Esri reference and slightly darkens rocks using
native raster uniforms. Only regional Esri pixels receive a worker-side
counter-transform, retaining their reference appearance through the new shader
settings. Natural colour off remains ungraded. No LINZ photo processing,
extra frame pass or HLOD/cache-budget change. Authentic Cook reference checks
and the relevant planner/worker/cache regression checks passed; no live browser
verification is available.

## Previous: precise regional imagery and stronger HLOD — 2026-10-18 Melbourne

See [REGIONAL_STREAMING_STATUS.md](REGIONAL_STREAMING_STATUS.md). LINZ is restored
at startup. Only reviewed gaps in the fine-survey footprints west of Cook use
Esri; good Cook/Tasman/Fox/Franz imagery remains LINZ. The 336 successful source
checks, conservative geographic mask, Esri z19 placeholder avoidance, local
surface/pivot detail, stronger distant decay and bounded parent-texture holds
are documented there with tests, costs, limitations and recovery scripts.
Controlled native selection: 162 -> 139 DEM tiles with both focus zooms preserved.
No live browser/FPS result is claimed.

## Previous: supplied recording — 2026-10-17 Melbourne

See [RECORDING_FIX_STATUS.md](RECORDING_FIX_STATUS.md) for confirmed source-cutline
evidence, startup Satellite imagery choice, measured coarse/fine DEM culling
failure, the new visibility adapter, validation tradeoffs and exact recovery.
Do not claim that cached tile tests visually reproduced the recording. No live
browser replay is available. LINZ elevation/planning remain unchanged.

## Previous: decoded DEM residency and colour-drape invalidations — 2026-10-16 UTC

The user still sees chunks unload out of view. Two additional native lifecycle
paths were reproduced and fixed, rather than increasing every cache blindly:

- The source residency pool previously attached only to `raster`. It now also
  supports a separate **shared elevation pool** across `terrain`,
  `terrain-fallback` and `shade-dem`: 48 MiB normally, 24 MiB on reported <=4 GB,
  12 MiB on Save-Data. Native source-cache ownership and frustum/LOD selection
  stay unchanged. Up to two hidden DEM loads may finish (one on Save-Data).
  Account conservatively for main/worker decoded arrays, DEM GPU storage and a
  potential hillshade target, including resources not prepared yet. This is an
  off-screen resource estimate, not total GPU or process memory. Visible tiles
  are never evicted by these pools. The shared pool replaces the shade source's
  earlier 32-tile cap. On wide viewports the byte budget can retain fewer DEM
  tiles than the previous count cap; the benefit is predictable bounded memory,
  reuse within that budget and completion of useful pending DEM work.
- MapLibre 6.14.0 `_handleTerrainDataEvent` calls `releaseRTT(tileID)` for **any**
  arriving source tile. The geometry-only DEM is not a colour-drape input, yet
  that call released overlapping visible and warm imagery textures. A small
  version-guarded synchronous event context now skips only that unnecessary
  release. The complete native event handler still updates elevation caches,
  camera, terrain depth and symbol placement. Imagery, hillshade, style, source
  revisions and unknown events retain normal invalidation. Any style layer
  referencing the geometry DEM, or any custom layer, disables this shortcut.
- WebGL context loss explicitly clears both off-screen source pools and warm
  drapes. Normal native context/source restoration remains authoritative.
- Passive report stats now include `demSource*` residency counters and
  `geometryOnlyEvents` (colour releases avoided). Build tag
  `2026-10-16-dem-residency`; HTML bootstrap v12, raster adapter v3, native
  adapter v5. No colour, authentic imagery, DEM values, route or LOD-policy change.

Validation: `node checks/tile-unloading.cjs` uses the actual bundled DEM
TileManager, TileCache, TerrainTileManager and native terrain event handler.
Native baseline evicted 14 decoded tiles during the controlled 28-step pan;
48 MiB residency returned to the starting tile with zero reload/decode/upload
calls and the same decoded array and texture. Additional actual Mercator-camera
orbits at zoom 12, 14 and 16 returned all 14/21/28 original DEM tiles without new
loads. Tests verify hidden-load completion, eviction bounds, visible resource
protection, original imagery/style invalidation, camera/depth/symbol work and
context-loss cleanup. Results: `TILE_UNLOADING_VALIDATION.json`.

Network, DEM samples and GPU allocation are controlled. These checks establish
native lifecycle behaviour, **not live browser pixels or measured frame rates**.
Browser control is unavailable in this environment. The exact West Coast patch
and all user-observed transitions remain unverified. Cache limits and requesting
new LOD IDs can still cause legitimate refinement; no claim of keeping an
unbounded journey or every zoom resident.

Recovery: run the new check plus `checks/native-residency.cjs`,
`checks/raster-residency.cjs`, `checks/imagery-recovery.cjs`,
`checks/base-performance.cjs`, and the planner/bootstrap/elevation checks. Use the
Sites helper to build, push and package, preserving the owner-private audience
and R2 binding. If the symptom remains, use **Save imagery report** while
reproducing it and inspect both source-pool eviction counters, RTT invalidations,
geometry-only skips and actual source XYZs; do not treat HTTP success as pixel QA.

## Previous: colour rollback and evidence-led imagery investigation — 2026-10-16 UTC

The user rejected the cinematic look and reported both problems persist. Restored
pre-cinematic colours/lighting exactly; removed custom sky/fog. See
[IMAGERY_RESEARCH.md](IMAGERY_RESEARCH.md) for primary-source research, confidence,
changes, costs and recovery. Do not claim the exact West Coast patch is solved.

Confirmed and fixed our unsafe automatic `refreshTiles()` retry on errored raster
tiles: the native 6.14.0 path makes a textureless tile renderable while retrying.
`checks/imagery-recovery.cjs` reproduces this with an actual native Mercator camera
and verifies fresh loading tiles stay non-renderable until upload. Removed
speculative fine-child promotion and automatic LINZ/Esri tile mixing. Retained the
bounded caches. Reduced hidden pending allowance to 4 (2 on Save-Data), removing
contention with the viewed area. Hillshade has its own source manager, shared
matching DEM byte cache, visual maxzoom 16 and a 32-tile/two-level cache cap;
geometry/planning elevation and maxzoom 18 remain unchanged.

Map layers -> Detail & loaded views -> **Save imagery report** exports bounded,
passive diagnostics from the actual view: camera, XYZ/state/texture availability,
cache metrics and recent source events. No API keys, URLs, error messages or routes
are included. No polling, GPU readback, image generation or new imagery requests.
A report plus screenshot is needed if the regional patch remains; earlier
constant-camera and HTTP checks did not visually reproduce that exact artefact.

## Previous: source texture residency and cinematic rendering — 2026-10-15

The user reported the previous update still unloaded imagery and retained the
West Coast artefact. The earlier compressed-cache and rendered-RTT tests were
insufficient: neither exercised real **source TileManager / TileCache** eviction
and source LOD selection. Do not claim that source availability checks alone
prove an on-screen artefact fixed.

`public/raster-residency.js` now adds a version-guarded, shared source-texture
residency pool to Original, wired through `native-residency.js`:

- Native cache size was `min(configured cap, viewport tiles × zoom ranges)`.
  In the real engine's controlled 256px viewport, this is **32 tiles**, despite
  the configured cap of 192; 45 tile changes evicted 13 source textures.
  The adapter replaces this implicit viewport cap with a shared **byte budget**:
  128 MiB normally, 64 MiB on reported ≤4 GB, 32 MiB on Save-Data. This is
  off-screen source residency, additional to visible resources and the existing
  rendered-drape budget. It is not a total process/GPU memory measurement.
- Native source caches continue to own actual Tile and GPU Texture objects.
  A shared LRU across LINZ/Esri/topo evicts only under budget pressure or valid
  source/lifecycle invalidation. Native visible tiles are never budget-evicted.
  No unbounded promise of retaining an entire NZ journey is made.
- Native raster removal aborted unfinished off-screen loads. Up to **24** are
  now parked in the source cache until completion (6 for Save-Data), without
  entering the draw list. Eviction/reset aborts them safely. Failed parked tiles
  are discarded and retried on return, rather than caching an errored child.
- Native ideal-tile selection could choose a ready coarse parent even when finer
  children were already cached. Already-loaded, **frustum-visible** children up
  to three levels finer are now retained in source composition. No new fine
  requests are created by this promotion. Native raster overlap/depth handling,
  terrain heights, camera, source maximum zooms and geometry remain unchanged.
- Imagery source distance decay is less aggressive: 5 levels / ratio 4 normally,
  6/4 in low power, instead of sharing the DEM's 7/4 or 8/3 budget. Existing
  first-surface focus and user imagery bias remain. This spends somewhat more
  source work on visible photography to avoid the coarse regional appearance;
  it is not a claim of universally fewer requests or higher device FPS.
- Missing (HTTP 404) or empty LINZ aerial tiles now try authentic **same-XYZ Esri
  World Imagery** through the same bounded cache. A native 204 otherwise becomes
  a transparent no-content tile rather than normal parent fallback. The LINZ
  attribution names the gap provider. Auth failures, elevation data and normal
  successful LINZ tiles are never substituted. This handles a real failure path;
  the exact user-reported West Coast camera was not available for pixel QA.

The **Cinematic colour** switch now applies constant native raster shader values:
LINZ saturation +0.17, Esri +0.12, contrast +0.13, brightness range 0.035–0.93.
The highlight range preserves white headroom with the increased contrast.
Hillshade has warmer highlights, blue-grey shadows, strength 0.32 and sun altitude
35°. Native sky/fog adds subtle distance haze (ground blend 0.12). No fullscreen
postprocessing, generated replacement imagery, altered DEMs or vendor edits.
Entry HTML/bootstrap responses revalidate so a reload discovers current imports.

Validation and recovery:

- `node checks/raster-residency.cjs` constructs the real bundled Style,
  TileManager, TileCache and Tile lifecycle. Reproduces baseline eviction, then
  verifies 60-tile pan-away/return with **zero fetch/decode/upload calls**, reuse
  of the exact source texture, fine-child retention under coarser desired LOD,
  off-screen completion, failed-load recovery, shared byte limits across two
  providers and production controller wiring. GPU allocation/frustum are
  controlled: this is not a live browser/FPS test. Results:
  `RASTER_RESIDENCY_VALIDATION.json`.
- `node checks/imagery-retention.cjs` additionally checks 204/404 same-XYZ gap
  fallback, no auth/DEM substitution and prior compressed-cache/source tests.
- `CINEMATIC_COLOUR_VALIDATION.json` evaluates native shader equations on three
  authentic LINZ z15 sample tiles. Mean channel spread rises about 50%; none
  introduce fully clipped white pixels. This omits live hillshade/fog and is not
  browser visual QA.
- Run native RTT, surface-focus, planner/bootstrap, performance and elevation
  checks; use the Sites helper for build/push/archive. Browser control remains
  unavailable; do not substitute source-status checks for image verification.
  If the exact regional boundary remains, recover its precise camera/screenshot
  and distinguish a survey cutline from failed selection/requests.
- Preserve MapLibre **6.14.0** vendor hashes, Original startup, optional Three,
  route/profile behaviour, R2 storage and owner-private access. Native internals
  are pinned; review these adapters before any engine upgrade. Latest source and
  recovery notes are committed through the Sites workflow.

## Previous: visible-surface focus and West Coast source checks — 2026-10-15

User located the persistent soft area around **Franz Josef / Fox**. Direct source
checks fetched and decoded 24 authentic LINZ/Esri tiles at three sample positions,
at z13/15/17/18. All decode at 256×256, including z18. See
`WEST_COAST_SOURCE_VALIDATION.json` for coordinates, XYZ and byte hashes.
This disproves blanket unavailability at those points; **it does not prove the
exact reported on-screen artefact is fixed or every West Coast tile is sharp**.
No regional provider substitution or generated satellite imagery was introduced.

- New `public/surface-focus.js` is version guarded for MapLibre 6.14.0. It uses
  the native centre-ray **first terrain intersection**, using the loaded DEM
  coverage, rather than assuming a navigation pivot behind a ridge is visible.
  The native hit's z is metres; it is explicitly converted to normalized Mercator
  before measuring camera/surface range. No elevation or camera is modified.
- Source LOD now applies fine-detail priority inside a smooth spatial footprint
  around that surface. The extra close-face target is capped at **one zoom level**;
  source maximums and user detail controls still apply. Outside the focus footprint
  the existing distance-based source budget applies. If terrain is absent or the
  centre points into sky, the previous safe source policy remains available.
- Native rendered terrain coverage now receives the same surface focus target.
  Its original **9.314/3 native distant LOD budget is retained**, obtained through
  the pinned engine's own LOD factory via `Map.setSourceTileLodParams` on an
  isolated holder. The covering provider adapter adds only a calculateTileZoom
  option to the native terrain-render traversal; source/vector policies,
  bounding volumes, frustum tests, measured heights and tile ownership remain.
  This avoids downloading fine imagery only to flatten it into a coarse drape.
- Renderable terrain tiles are ordered by distance from the first visible surface
  before native RTT preparation. Native MapLibre updates at most one stale
  source/zoom texture per frame; it now updates the viewed face first. Native
  fingerprint checks and the warm texture cache remain active.
- Surface picks are throttled to once per **240 ms**, plus final move-end and
  terrain changes. Stable picks do not repeatedly dirty sources. No depth-buffer
  readback or extra raster network request is introduced. This is centre-view
  priority, not gaze tracking or full terrain occlusion culling; adjacent native
  quadtree tiles can necessarily share one integer LOD.
- Natural colour is more vibrant than the previous strongly desaturated setting:
  LINZ saturation −0.18, Esri −0.12, contrast +0.015, brightness max 0.99.
  These are constant shader values at every zoom; no application-induced
  zoom-dependent colour jump or replacement imagery is introduced.

Validation: `node checks/surface-focus.cjs` exercises the real bundled native
covering traversal with controlled first-hit/bounds; verifies surface units,
focused desired LOD, preserved native distant render budget, no per-frame picks,
steady-source behaviour, sky fallback and cleanup. Its synthetic scene has eight
focused tiles versus six with native default coverage, so this is selective
quality allocation **not a claimed universal tile-count or FPS reduction**.
`node checks/native-residency.cjs` additionally exercises real native RTT
fingerprints and confirms the viewed tile is the first stale drape refreshed,
while others keep their cached textures. See the corresponding validation JSON.
Planner/bootstrap, colour controls, compressed cache, source residency and
optional renderer handoff checks also pass. Browser/pixel QA was unavailable in
this environment; obtain a precise screenshot/camera if the user's specific
boundary persists rather than claiming source detail that LINZ does not contain.

Recovery: run the two checks above plus `checks/base-viewer.cjs`,
`checks/base-performance.cjs`, `checks/original-streaming.cjs`, and
`checks/imagery-retention.cjs`; use Sites source helper for build, push and archive.
Preserve pinned vendor hashes and owner-private access. Unsupported engine versions
skip the surface adapter. Do not add this adapter to the optional Three viewer.

## Previous: rendered residency and adjustable detail — 2026-10-15

The user clarified that imagery appeared stuck at an overview LOD and still
unloaded on turning away. Download caching alone did **not** retain the rendered
terrain textures: native TerrainTileManager.update explicitly released them when
leaving the frustum. This update adds actual rendered-drape retention to Original.

- `public/native-residency.js` is an adapter for the **pinned MapLibre 6.14.0**
  terrain manager. No vendor assets/hashes change. It temporarily defers only
  releases of tiles leaving the visible set, retains the tile and its actual RTT
  textures in a byte-limited LRU, and restores that same tile/texture on return.
  Native frustum culling continues; off-screen tiles are not drawn. Source data,
  style, zoom/layer fingerprints, explicit tile invalidation, terrain replacement,
  context loss and teardown remain able to invalidate cached content. Visible
  tiles are not evicted to meet the off-screen budget.
- The adapter uses version-checked internal interfaces; retain regression coverage
  when upgrading MapLibre. Unsupported versions/shapes skip it rather than altering
  unknown internals. Budget counts RGBA textures and their mip chains (4/3 factor),
  including all RTT stacks. This is not a total GPU/process-memory measurement.
- Map layers → **Detail & loaded views** provides separate imagery and terrain
  detail sliders, plus the retained-view memory budget. Defaults: +0.25 source LOD
  (about 1.2× nominal linear detail) each, with 0–1.5 levels / 1–2.8× selectable.
  Integer tile selection means individual views refine in steps, not exactly 1.2×.
  Higher choices consume more network, triangles and memory; no FPS claim is made.
- Imagery detail adds its bias before the existing native distance/focus policy,
  including distant requests. Terrain detail reduces the native rendered/source
  terrain tile-size parameter from 512 by 2^bias, increasing spatial subdivisions
  and requested DEM detail at the same camera. Shared 128-cell native meshes,
  source maximum zooms, native culling, measured elevations and exaggeration remain.
  RTT resolution also follows imagery detail, so fine imagery is not solely
  downloaded into a coarse texture. Controls apply to Original's 3D view and
  are disabled when Three.js is selected. Preferences persist locally under
  `ridgeline-detail-v1`; slider refresh is debounced 160 ms.
- Retained-view default is 96 MiB (48 MiB on reported ≤4 GB), adjustable 32–256 MiB.
  This is **additional off-screen rendered texture residency**, beyond existing
  source and compressed caches. It keeps recent views ready; it cannot retain
  an unlimited New Zealand journey. Old views retire only under budget pressure
  or valid content/lifecycle invalidation. A budget reduction schedules a frame
  so native recycled texture resources can be drained.
- Failed still-resident imagery tiles get at most two targeted retries after
  1.5/3 seconds. Abort, auth and missing-tile errors are excluded. Successful loads
  remove retry state; all timers/listeners are cleared on teardown. This addresses
  transient failed children otherwise leaving a coarse parent visible indefinitely;
  it does not claim to diagnose every West Coast tile without browser inspection.
- Natural-colour grading is now restrained and **independent of camera zoom**
  (LINZ saturation −0.55; Esri −0.45; contrast −0.07; brightness max 0.99).
  No zoom-driven application grade can become more vibrant or be baked into
  retained drapes at another zoom. Actual provider/seasonal colour differences
  can still exist. Natural colour remains switchable; no imagery is generated.
- Style restoration destroys old performance/residency controllers before adding
  new ones, preserves existing route sources/layers and replaces peak markers,
  avoiding nested adapters and duplicate planner objects after context recovery.

`NATIVE_RESIDENCY_VALIDATION.json` comes from `node checks/native-residency.cjs`.
The test invokes the real bundled Map.setTerrain, TerrainTileManager.update,
covering traversal, Tile objects and RenderToTexture fingerprints, using a
controlled frustum and mocked GPU allocation. It verifies identical texture/tile
reuse on return, native revision invalidation, invalidation of off-screen content,
100 turns with byte-bounded eviction, visible-content protection, source/RTT
slider coupling, actual finer geographic subdivision, native imagery bias,
targeted retries and teardown. This is stronger than a source-download-cache test;
it is **not** a browser pixel or frame-rate benchmark. Managed browser QA remains
unavailable. Keep Original the default and the optional Three.js renderer intact.

Run the new check plus base-viewer (all worker/bootstrap variants), base-performance,
base-elevation, original-streaming, imagery-retention, three-planner and lidar-storage.
Build/package/publish through Sites using the same owner-private project and R2
binding. Persist the source milestone and this recovery document via the source
helper. Earlier colour/imagery benchmark JSON files below are historical, not
current browser validation; their previous zoom-dependent paint is superseded.

## Latest: Original startup and imagery retention — 2026-10-15

- Original now starts directly even when WebGPU is available. Three.js and its
  GPU/TAAU modules are loaded only on selection. Switching and shared routes remain.
- Compressed imagery now has a dedicated LRU, independent of DEM churn: 48 MiB /
  2,048 tiles on ordinary devices, 24 MiB / 2,048 on reported ≤4 GB, 12 MiB /
  512 with Save Data. DEM gets a separate 16/12/8 MiB cache (512/512/192 tiles).
  These replace the earlier shared compressed cache. They are page-session
  caches; HTTP caching remains. Native decoded/GPU out-of-view cache is unchanged.
  Off-screen drawing remains culled; this is source retention, not permanent
  GPU residency. Bounded eviction still occurs during sufficiently large journeys.
- LINZ aerial, Esri World Imagery and Esri topographic all use the supported
  `ridge` protocol. HTTPS destinations are strictly mapped, not arbitrary proxies.
  Previously Esri bypassed this compressed cache. Same source bytes and max zooms.
- One shared retry after 300 ms for HTTP 429/5xx, within the existing request
  timeout. Retry-After is respected up to 2 s; longer server windows are not retried. Authentication failures are not retried. Short imagery outages keep
  the loaded LINZ source instead of switching the entire view to another provider.
  Authentication fallback and the existing terrain outage fallback are retained.
- Unchanged raster visibility is not rewritten when a layer is selected again.
  Existing source LOD, frame-pressure budgets, worker profiles and R2 recovery
  storage continue unchanged.
- User identified the West Coast artifact as a **colour boundary**. Authentic
  samples at 169.98/-43.56, 169.8/-43.5 and 170.141/-43.595 show differing colour
  and seasonal coverage between source zooms 12 and 13. LINZ's mosaic comprises
  different surveys. The stock MapLibre 6.14.0 draw_raster implementation bypasses
  raster cross-fades in terrain mode, so increasing fadeDuration would not solve
  that 3D change. No engine/vendor patch was made.
- Natural-colour grading now retains stronger, more consistent colour moderation
  through the survey-change zoom range, tapering gently to close-up source colour.
  Contrast is slightly softer around the transition. This executes in the existing
  raster shader, adding no image processing, extra texture passes, downloads,
  replacement imagery or generated detail. Natural colour can still be disabled.
  This **reduces**, but does not eliminate, survey/seasonal seams. Do not claim a
  source-data colour boundary has been completely repaired.

Evidence: `IMAGERY_RETENTION_VALIDATION.json` (three imagery providers, 300 photo
revisits, 600 competing DEM loads, zero revisit downloads; real native raster
loader/protocol/mipmap handoff with mocked bitmap decode); existing native DEM,
planner, bootstrap and performance checks. Bootstrap runs with a WebGPU-capable
fixture and asserts no Three.js import. Server-error tests retain LINZ after
five transient errors and still fall back on auth failure.

`WEST_COAST_COLOUR_VALIDATION.json` compares matching geographic footprints from
original source tiles using actual MapLibre grading equations at camera zooms
11.5/12/13. Mean chroma-gap reduction over those samples is ~26.8% against the
previous grade; RGB mismatch also decreased. This is CPU image-statistics QA,
not a screenshot, a browser/device FPS benchmark, or proof all cutlines disappear.
No derived imagery is served. `checks/west-coast-colour.py <fixture-directory>`
expects `{west,coast,summit}-{12,13}.webp` and `paint.json` with before/after exports
of RidgeImagery.paint('linz'); previous paint lives at source commit
`eb693b7dd22d1139115bcd9a7feb2d32bfa659c5`. Source-image hashes are recorded.

Run new checks: `node checks/imagery-retention.cjs` and the colour check, plus
existing Original/bootstrap/profile/planner/production checks. Managed browser
visual QA was unavailable; real-device inspection of the reported boundary and
rapid camera turns remains outstanding. Preserve the same project and audience;
use the Sites helper to commit/push/package and deploy its exact returned archive.

Primary source research: https://github.com/linz/basemaps/blob/master/docs/README.md
and https://github.com/linz/basemaps-config/blob/master/config/tileset/aerial.json .

## Original streaming update — 2026-10-15

Latest user clarification: apply streaming/cache fixes to **Original MapLibre only**.
The Three.js renderer, material, worker, default-selection behaviour and GPU path
are unchanged by this update. Choose **Original** in Map layers to use these fixes.

- Keep unfinished parent viewport tiles during zoom (`cancelPendingTileRequestsWhileZooming: false`).
  MapLibre retains loaded parent/child coverage while replacements arrive; we do
  not replace its culling, terrain meshes or internal tile-manager implementation.
- Native out-of-view cache caps per source: 192 ordinary / 128 at ≤4 GB /
  96 at ≤2 GB / 80 with Save Data, with eight viewport-equivalent zoom levels.
  These are tile counts, not a claim about process or GPU memory. Actual capacity
  is the smaller native viewport-based limit and the configured cap.
- `public/tile-cache.js` registers the supported `ridge` protocol for LINZ imagery
  and DEMs. It maps exclusively to the same LINZ HTTPS endpoints. A byte/count
  bounded compressed LRU (32 MiB ordinary, 20 MiB ≤4 GB, 12 MiB Save Data) retains
  source bytes independently of decoded/GPU eviction. It survives renderer
  switches within this page, not a page reload. Existing HTTP caching still applies.
- Concurrent identical requests share one fetch with independent cancellation.
  With no subscribers, obsolete fetches get a 600 ms reversal grace, then abort.
  Requests time out after 20 seconds. Errors are never cached; cache directives
  and max-age are respected, with six-hour maximum residency. Each consumer gets
  its own buffer so a worker transfer cannot detach cached data.
- Distance LOD uses native 7 levels / 4× ratio, or 8 / 3 in low power. **Increasing**
  the levels parameter makes detail decay faster into the distance. The native
  result is smoothly blended up to the requested source zoom near the camera's
  foreground/focus distance. Maximum imagery/DEM source zooms remain 19/18.
  Unchanged LOD settings are not reapplied on every refresh.
- Original starts with the transferred view's elevation (or the initial summit
  elevation), native ground clamping and automatic terrain skirts. This avoids
  unnecessarily starting a transferred close-up at sea level. Normal native
  terrain camera updates continue as source DEMs become available.
- No new imagery, elevation modification, AI reconstruction or custom mesh is added.
  Routes/profiles retain their existing authoritative sampling and source paths.

`ORIGINAL_STREAMING_VALIDATION.json` is generated by
`node checks/original-streaming.cjs`. It exercises the actual bundled MapLibre
LOD function and real RasterDEMTileSource → ImageRequest → protocol → cache → DEM
worker handoff, with mocked browser bitmap decoding and GPU unused. It checks
200 repeated pan loads over 20 unique tiles (20 downloads), independent aborts,
quick reversals, expiry, errors, LRU bounds, transferred-buffer safety and DEM
`colorSpaceConversion: none`. Distant synthetic tile demand falls about two
source zoom levels relative to the old policy at tested view zooms 5/12/17/19;
foreground/focus demand retains its requested zoom. This is **not** a browser
frame-rate, network-latency or visual benchmark. Managed browser QA remains
unavailable; verify fast reversals and extreme close-ups on a real device.

Recovery: keep this project/audience/R2 binding; run the new check plus the
existing base-viewer, bootstrap, base-performance, base-elevation, lidar-storage
and three-planner checks. Use Sites build-site.mjs and base-production.mjs, then
the Sites source helper to commit/push/package and private-publish its exact
commit/archive. The previous architecture below records the earlier baseline;
the streaming settings in this section supersede its cache/LOD/cancellation values.

Research: https://maplibre.org/maplibre-gl-js/docs/API/classes/Map/
(setSourceTileLodParams), https://maplibre.org/maplibre-gl-js/docs/API/type-aliases/MapOptions/
(cache and cancellation), https://maplibre.org/maplibre-gl-js/docs/API/functions/addProtocol/ .

## Current architecture

- `public/index.html` loads `viewer.mjs`, which loads the pinned, locally served
  MapLibre **6.14.0** module and versioned worker before importing the planner.
  Official npm SHA-512 integrity and individual SHA-256 asset hashes are recorded
  in `public/assets/maplibre-provenance.json`; the BSD licence accompanies them.
  The former engine was 5.6.2. Current upstream includes terrain drape reuse and
  release, reduced marker GPU stalls, raster fading and terrain camera fixes.
- All 3D terrain uses native MapLibre raster DEM rendering and the existing LINZ
  Terrain-RGB endpoint, maximum source zoom 18. Imagery maximum zoom 19 remains.
  Source elevations and route/profile calculations are unchanged. There is no
  custom mesh overlay, separate terrain engine or experimental data download.
- `performance.js` avoids canvas reallocation at gesture boundaries. Its native
  source LOD budget is 4 levels / 4× tile-count ratio, or 3 / 3 in low-power mode.
  The old custom `calculateTileZoom` override is removed. Per-source tile cache
  limits are 96 on ordinary devices, 64 at reported ≤4 GB, or 48 with Save Data.
  Existing HTTP caching and obsolete-request cancellation remain in use.
- Rendering keeps the existing overview pixel budget and restores close-up
  detail with hysteresis. Close-up canvases have a soft six-million-pixel budget,
  with pixel ratio never below 1. Ordinary gestures do not resize the buffer.
  Only sustained pressure (24 samples, at least 750 ms, upper quartile >28 ms)
  permits one modest reduction during that gesture. Detail returns after 650 ms
  idle; ordinary settling uses 280 ms. No permanent render loop is introduced.
- `imagery.js` grades the existing raster shader smoothly by camera zoom. Its
  natural-colour mode reduces overview saturation most strongly, then tapers
  toward source colour at close zooms. Colour expressions are evaluated by the
  engine, with no application paint writes on every move. Raster tile fading is
  420 ms, disabled for reduced-motion preference. Linear/anisotropic filtering
  uses the native renderer. Users can disable natural colour in Layers.
- Soft `igor` hillshading at 0.16 strength reuses the same DEM source. Neutral
  shadow colour and a map-fixed northwest light replace the previous green
  shadow cast. It is off below zoom 8, in low-power mode, over the already shaded
  topographic map, and by default on Save Data / reported ≤2 GB devices.
- A single transient tile error no longer swaps the entire basemap or terrain
  source. Missing/aborted tiles do not force that swap; four other errors within
  15 seconds, or authentication failure, retain the existing fallback path.
- Profiles still decode in a lazy worker, use bounded decoded and disk caches,
  cancel obsolete work and preserve source heights. Completed profiles are no
  longer invalidated by every ordinary pan. Actual edits and focused-view changes
  still refine the profile. Worker cleanup and graphics-context recovery are kept.
- `lib/retired-viewers.mjs` and the Worker routing rules handle old bookmarks.
  The existing owner-private `/api/lidar/` checkpoint service and R2 binding remain
  intact for recovery; no persistent processing outputs were deleted.

## Evidence and limits

See `BASE_VIEWER_VALIDATION.json` and `BASE_IMAGERY_VALIDATION.json`.

The controlled 100-gesture, DPR 2, zoom 17 fixture reduced forced drawing-buffer
resizes from **200 to 0**. Sustained 33 ms frames produce one adaptive downshift
and restore pixel ratio 2 after settling. This is an event/frame-time fixture,
not a browser FPS benchmark.

All main planner integration checks pass, including module bootstrap order,
worker and worker-failure paths, style-spec validation, route drawing, profiles,
API-key changes, colour/shading controls, terrain fallback, 2D/3D and cleanup.
Profile cache checks show five cold detail requests and zero repeated requests,
with concurrency capped at three. R2 checkpoint API tests pass unchanged.

The source public directory falls from 295,057,880 bytes / 3,506 files to
1,759,868 bytes / 16 files. Framework output adds 12 static chunks: the production
client contains 2,147,489 bytes / 28 files. Previously unused datasets were not
all loaded on startup, so this is **not** a claimed startup speedup. The newer
engine plus one worker increases the estimated gzip JavaScript total from
279,554 to 447,239 bytes; navigation/cache improvements justify this tradeoff.

Matched existing LINZ tile footprints at zooms 12 and 13 show different colours
and substantial differences in snow cover. At fixed camera zoom 11.5, the actual
grading equations reduce the sampled mean-chroma step by approximately 42.7%.
That is colour-statistics analysis, not a rendered comparison. Different seasons,
capture dates and shadows remain; grading and fades cannot make them identical.

**Outstanding:** real browser GPU/FPS and visual verification on ordinary laptops
and Chromebooks. The supported browser-control skill is unavailable here. Do not
describe fixture timing as measured device performance. No preview server or
replacement browser automation was used.

## Validate and publish

After the supported Sites installer completes:

```text
node checks/base-performance.cjs
node checks/base-viewer.cjs
TEST_WORKER=ok node checks/base-viewer.cjs
TEST_WORKER=fail node checks/base-viewer.cjs
TEST_BOOTSTRAP=1 node --experimental-vm-modules checks/base-viewer.cjs
node checks/base-elevation.cjs
node checks/lidar-storage.mjs
node /root/.codex/plugins/cache/openai-curated-remote/sites/1.0.0/scripts/build-site.mjs
node checks/base-production.mjs
```

Use the Sites source helper to commit/push and package the exact verified build,
then save and publish that source/archive through the confirmed owner-private
operation. The former `scripts/prune-static-build.mjs` is retired: experimental
assets are now absent from public source rather than pruned after copying.
`checks/base-production.mjs` verifies their absence, engine hashes, loader imports
and the hosting size budget before publication.

## Recovery

Open this exact Site's latest repository using the Sites source helper, then read
this document before making changes. The current replaceable working checkout is
`/workspace/sites/ridgeline-base-viewer`; never rely on its continued existence.
All new code, validation and documentation belong in the pushed project source.

The previous full experimental website is saved at source
`8df0f2de66a67f6a6dacab3058c7f69df68f66c3` and private saved version
`appgprj_6acc83afb6588191aff43dc542d98b5f~appgver_6279474bf718819195800ecd633951a9`.
Recover its processing tools/fixtures into a separate checkout only if explicitly
requested. Do not republish the retired experiments as an automatic recovery step.
Earlier LiDAR and imagery R2 run identities and hash-verified restore instructions
remain in the archived project documents and Git history.

Primary references: [MapLibre releases](https://github.com/maplibre/maplibre-gl-js/releases),
[native map API](https://maplibre.org/maplibre-gl-js/docs/API/classes/Map/),
[raster and hillshade styling](https://maplibre.org/maplibre-style-spec/layers/),
[LINZ aerial configuration](https://github.com/linz/basemaps-config/blob/master/config/tileset/aerial.json).

## 18 October 2026 — alpine atlas

Current route, label and slight snow/rock colour update: see ALPINE_ROUTES_STATUS.md for exact sources, the indicative-corridor accuracy contract, tests and recovery. 8 routes / 18 summit labels. Natural imagery cache variant is now snow-v2; Esri compensation and the saved overview pyramid have been rebuilt consistently. Original remains the default, and all previous terrain/imagery retention adapters are retained.

## 18 October 2026 — South Island overview / directional cache update

SOUTH_ISLAND_IMAGERY_STATUS.md supersedes the earlier Cook-only overview runtime and eight-route atlas. The 18 summit labels and user-drawn/saved/imported routes remain. Editorial climbing routes and their catalogue/search/Three buffers are removed. Shading defaults off and native LINZ snow-v3 paint is constant across zooms. A tiny directional byte-cache warmup predicts the next zoom/pan tiles; all six existing LOD/residency modules remain byte-identical to previous production. See current asset, colour, LOD and delivery reports plus recovery instructions in the South Island status document. Large generated/source packs stay in authenticated R2; only small coarse static copies ship with the client.

## 18 October — loading continuity / higher tilt

The later LOADING_CONTINUITY_STATUS.md documents selective geometry/imagery disappearance fixes based on the user's 21:32 recording. Residency lifecycle adapters now protect DEMs needed by visible/warm terrain, keep complete old coverage until replacements are ready and defer incomplete colour refreshes. Max pitch is85deg. Source imagery, geographic measurements and existing LOD selection are unchanged. The old six-module byte-identical residency statement applies to the earlier South Island release; current LOD selection modules remain identical, while residency has intentionally changed. See LOADING_CONTINUITY_VALIDATION.json and updated native/hierarchy reports.


19 October cache/loading update: see CACHE_LOADING_STATUS.md and CACHE_LOADING_VALIDATION.json. Devices reporting4 GB now default to the352 MiB standard cache profile; obsolete unfinished off-screen work is smaller, and repeated coverage/dependency scans are avoided. Existing loading-continuity protections and native LOD remain.
