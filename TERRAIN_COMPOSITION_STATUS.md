# Prepare ahead: finished terrain composition — 19 October 2026

Latest cache-return fixes and recovery: see CACHE_RETURN_STATUS.md. Earlier historical descriptions below retain their original context.

Current radius extension: see PREPARE_RADIUS_STATUS.md. Historical fixed-ring limits below describe the preceding implementation; radius1 retains those limits.

Existing Ridgeline Site appgprj_6acc83afb6588191aff43dc542d98b5f, starting version43/source990792e552d9df2fbd005198e9ba43c8256e1754. User explicitly requests extending the existing checkbox to prepare final terrain composition. Keep owner-private audience, R2 LIDAR, Original default, imagery/grade/LOD, optional Three, route planner, measured DEM and aggregate96–700 MiB cache setting.

## Current implementation

The same Prepare ahead checkbox now runs two sequential stages after the visible view settles: (1) existing native imagery/DEM load/decode/GPU preparation; (2) finished nearby terrain colour textures, using already loaded imagery and measured DEM coverage. Default remains off and the saved preference is unchanged. No new data/images are generated. Colours and imagery sources stay unchanged. There is no hidden map/camera movement and no off-screen terrain draw to the canvas.

public/terrain-composition.js is guarded by the existing MapLibre6.14.0 adapter. Capture the current native RenderState/style through the normal RTT layer completion hook. Create a fresh native render state and isolated single-patch terrain-coordinate/fingerprint metadata. Native RenderToTexture.prepareForRender generates the exact transforms/fingerprints; native Painter.renderLayer uses the existing layer shaders and paint values. Compose the native drape stacks into normal native tile RTT targets, generate mipmaps and submit with nonblocking gl.flush. Never invoke native final drawTerrain. Restore FrameUBO, framebuffer and viewport in finally, using native Context tracking rather than GPU readback/getParameter/finish. Shaders/program variants have already been used by the visible scene.

Only complete imagery coverage is accepted: prefer a complete cached child cover; otherwise one nearest complete cached/visible parent. Never draw overlapping cached resolutions. Raster textures, vector routes and background layers retain native ownership/order; screen-space labels/circles remain out of drape stacks as in the normal renderer. Up to4 native stacks. Custom layers opt out; hillshade uses existing ready FBOs only and skips speculative composition if its off-screen target still needs preparation. Soft terrain shading remains off by default. Unsupported/insufficient coverage is skipped safely and rendered normally when visible.

Completed native Tile/RTT objects are atomically inserted into the existing warm drape LRU (public/native-residency.js); incomplete/failed results release their targets and are never cached. Normal native TerrainTileManager.update adopts the same prepared patch/texture when it enters view. Prepared source fingerprints, style revisions, imagery dirtiness and full invalidations use the existing retention/invalidation code. Complete coarse imagery stays while finer coverage is incomplete, then normal refinement replaces it. This accelerates reuse at the prepared LOD, not a promise that all future zooms need no rendering. Required measured DEM dependencies participate in existing protection. Temporary source lookup entries are cleaned. Changing styles/detail/source/context clears the captured state until the next normal render.

## Bounded work / memory

- Original visible requests, native follow-up rendering, held transition covers and pending byte/source work take priority. Checkbox off, gesture, hidden document, context loss, low-power and data-saver pause scheduling.
- Existing650ms settle/idle callback policy (4ms idle allowance),350ms spacing and native source budgets remain.
- Composition neighborhood uses current native terrain detail closest to the first-hit surface, around camera/first-hit anchors inside NZ. At most8 candidates; no fine source requests are added for composition.
- At most1 synchronous patch submission per idle callback,4 attempts per settled view and8/minute. Above8ms CPU submission time, stop further compositions for that view. This measures JS submission, not GPU execution time.
- Admission leaves10% of the existing warm-view budget free. Estimated actual stack allocation per view is capped at min(12 MiB,20% of that budget). All prepared RTTs count in the existing aggregate slider's view allocation; no separate cache or extra allowance. Speculation does not evict an existing warm view to admit itself.
- If new source preparation is blocked by its budget, composition can still reuse already loaded data within the separate existing view allocation. Source preparation keeps its existing12 tasks/view,24/minute and min(12 MiB,8% of source budgets) limits.
- Save imagery report includes preparedCompositions, compositionReuses, compositionSkipped, compositionMs and compositionQueued alongside existing counters/gating reason. These are preparation/submission stats; existing native drape reuses count actual adoption.

## Validation

node checks/terrain-composition.cjs exercises actual pinned native coordinate transforms, RTT fingerprints, Tile allocation/adoption and native raster shader-dispatch paths. A temporary test-only copy exposes native RenderState/Painter classes; the production engine and provenance hashes remain unchanged. GPU handles/program draws are controlled. Tests verify same finished texture adopted without recomposition, complete coarse cover during incomplete fine arrival, source revision invalidation, multiple drape stacks, mipmaps/nonblocking flush, shared cache limits, incomplete coverage rejection, atomic shader failure and restoration of framebuffer/viewport/FrameUBO. No visible source selection/camera/draw list is changed by preparation. Report: TERRAIN_COMPOSITION_VALIDATION.json.

node checks/composition-scheduler.cjs verifies production scheduler: default-off, visible/network first, one idle operation at a time, navigation cancellation, checkbox cleanup,4/view and8/minute, cache-pressure gate and slow-submission backoff. Report: COMPOSITION_SCHEDULER_VALIDATION.json.

Existing checks/prepare-ahead.cjs verifies native source load/decode/GPU preparation and checkbox persistence/cleanup; it deliberately has no composition fixture (separate suites above). Regression checks pass for native/raster residency, loading continuity, cache slider, hierarchical streaming, base viewer, ESM bootstrap and optional Three integration. Production guard requires all three reports and exact built-source hashes. Real browser GPU pixels and Chromebook FPS remain unverified: managed Sites has no control-browser skill, and no improvised browser was launched. Do not claim measured real-device speedup.

## Recovery / publication

Restore this Site via Sites source helper into an empty checkout if necessary. Read this status plus PREPARE_AHEAD_STATUS.md, CACHE_SLIDER_STATUS.md and CACHE_LOADING_STATUS.md. Never rely on scratch assets: all source/docs/reports are Git-backed and South Island imagery remains in persistent Git/R2.

Run terrain-composition.cjs, composition-scheduler.cjs, prepare-ahead.cjs and relevant residency/continuity checks. Helper owns final build-site.mjs, checks/base-production.mjs, commit/push and archive. Publish exact returned source/archive through private Sites deployment, retaining audience/storage. URLs: viewer24/native-residency12/terrain-composition1/prepare-ahead2/diagnostics14; app27/raster7/tile-cache8 unchanged. After publication run scripts/imagery/south-island/check-publication.py via hidden PTY stdin with fresh origin/bearer; it verifies live module hashes plus persistent overview/range imagery and writes immutable external publication proof. No imagery rebuild is needed.

Research: official MapLibre terrain/custom layer and life-of-a-tile documentation; implementation checked against shipped6.14.0 native code, not assumed from current main. Khronos WebGL framebuffer semantics and browser WebGL best practices support off-screen targets, nonblocking submission and avoiding synchronous readbacks. Code does not change the vendored engine.
