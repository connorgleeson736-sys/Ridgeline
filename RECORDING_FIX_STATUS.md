# Recording-led imagery and terrain fixes — 17 October 2026 Melbourne

Project: appgprj_6acc83afb6588191aff43dc542d98b5f.
Origin: https://ridgeline-nz.connorgleeson736.chatgpt.site .
Preserve owner-private audience and R2 binding. Source remains version controlled.

## Evidence and changes

Inspected the supplied 83.44-second, 1359x620 recording at 0/3/12/39/50 seconds.
Two symptoms: missing terrain during movement and a persistent blue/green blurry
survey patch west of the summit. Earlier cache-only tests did not diagnose them.

**Upstream imagery cutline confirmed:** downloaded 128 matching LINZ/Esri z14
tiles, 64 per provider, over 170.04–170.20 E and 43.52–43.64 S. All decoded. The
same cutline and low-detail patch are in LINZ delivery before any rendering.
Esri has materially better texture there, with different captures, some cloud
and other survey boundaries. See IMAGERY_SOURCE_BOUNDARY_VALIDATION.json and
non-deployed inspection mosaics in qa/recording-2026-10-17/.

Startup now selects the existing Satellite / Esri basemap, from the initial
style itself, avoiding wasteful first-frame LINZ requests. This selection remains
when navigating elsewhere until the user changes layers. LINZ aerial stays
selectable. No automatic per-tile provider mixing, no generated/upscaled images,
no runtime mosaics, no extra imagery source or colour-grade change. Elevation,
profiles and planning remain LINZ. Source/key attribution and controls retained.

**Native false culling reproduced:** authentic LINZ z5 Cook tile max = 3056 m;
z16 summit tile max = 3717.3 m. Exact source hashes/extrema: DEM_CULLING_SOURCE.json.
The pinned native Terrain.getMinMaxElevation can use a coarse fallback DEM to
bound a descendant/ancestor during visibility traversal, excluding measured
fine peaks. New public/terrain-visibility.js (guarded to 6.14.0):

- Includes resident fine DEM minima/maxima in all geographic ancestors.
- Adds a culling-only margin of two source ground pixels, 2–800 m before relief
  exaggeration, to account for coarse pixel averages being poor extrema bounds.
- Uses a conservative -50..4000 m NZ land envelope while no DEM bounds exist.
- Caches the metadata hierarchy while resident DEM identities stay unchanged;
  scans references on terrain updates/data events and rebuilds only on change.
  Metadata is bounded by current residency, not accumulated journey history.
- Does not change elevation samples, GPU displacement, meshes, profiles, source
  LOD policy, frustum intersection tests or surface-focus targeting.

This correctness fix considers more tiles where mountains would be rejected.
Do not claim it universally reduces requests/triangles. Existing byte-bounded
imagery/DEM/drape retention and geometry-only colour-invalidation fixes remain.

## Verification and limits

checks/terrain-visibility.cjs uses the actual native Mercator transform, Terrain,
frustum and covering traversal, with measured source extrema and controlled
residency. Of 60 close camera configurations, native coverage omitted the summit
in 10; the installed adapter corrects all 10. Total candidate counts 1904 -> 2186;
maximum per view 51 -> 56. This is a coverage tradeoff, not an FPS measurement.
Checks also cover metadata reuse, eviction, unknown bounds and cleanup.
Results: TERRAIN_VISIBILITY_VALIDATION.json.

Base planner/style/worker success/failure/bootstrap, caches, residency, safe tile
retry, performance, elevation, surface-focus and optional Three.js regressions
passed. Browser control is unavailable: no claim of live browser pixels, a full
replay of the recording after publication or measured device frame rates.

Production entry HTML v13; app v19; visibility adapter v1; native residency v6.
Passive report build tag 2026-10-16-recording-coverage; includes visibility stats.

## Recovery

Run node checks/terrain-visibility.cjs, checks/tile-unloading.cjs,
checks/native-residency.cjs, checks/raster-residency.cjs,
checks/imagery-recovery.cjs, checks/imagery-diagnostics.cjs, checks/base-viewer.cjs
(normal, TEST_WORKER=ok/fail, TEST_BOOTSTRAP=1 with experimental VM modules),
base-performance/elevation, imagery-retention/original-streaming/surface-focus,
and three-planner. Then Sites helper build/package/private publication.
Do not replace source-image inspection with HTTP-only success claims. If symptoms
persist, export actual view diagnostics and distinguish culling/LOD IDs from GPU
or compressed-cache eviction. Do not reintroduce cinematic colours or generated
cliff materials.
