# Full-XYZ renderer integration contract

Research/design contract, 14 October 2026. A separate100m pilot is implemented in
`lidar-summit.html/js` with three saved LOD levels and complete fine clusters.
Browser/device inspection and full2km integration remain outstanding. The items
below describe the regional target; they are not all implemented in the pilot.

Add a Cook-only Cesium 3D Tiles mode, reusing the lazy viewer/manual render loop
and camera controls in `public/cook-comparison.js`. Keep existing planner,
profiles, studio and original renderer. HeightmapTerrainData and quantized-mesh
are heightfields and cannot represent the original-point full-XYZ overhangs.

## Coordinates: mandatory release gate

Input is EPSG:2193 + NZVD2016 (7839), localized by `aoraki.json`. Restore absolute
coordinates. Install/hash-pin the official PROJ distribution
https://cdn.proj.org/nz_linz_nzgeoid2016.tif. Convert to NZGD2000 geographic 3D
(EPSG:4959) using `TransformerGroup`, `allow_ballpark=False`, a bounded area of
interest, and require `best_available`. Then use `Transformer.from_crs` with
`only_best=True`, `always_xy=True`, and `errcheck=True`. Require the selected
operation actually references NZGeoid2016 without optional/null grid fallback.

NZVD2016 H to ellipsoidal h ADDS N. Explicit `vgridshift` multiplier is +1 in
that direction (its default is -1). Check all corners and centre: finite values,
h-H agrees with independent geoid sampling, inverse round-trip within 1 mm.
Record selected horizontal datum/ECEF transform accuracy and epoch separately.
Transform every vertex to ECEF and local ENU, not merely NZTM offset plus an
origin translation: projection convergence, scale and curvature matter.

Root 3D Tiles transform is ENU-to-ECEF. Export glTF Y-up coordinates (E,U,-N),
or retain Z-up and include the prescribed glTF root-node axis matrix; never
apply both. Cesium performs the corresponding runtime Y-up to tile Z-up change.
Camera target needs the same vertical transformation as the mesh.

## Assets and hierarchy

Use 3D Tiles 1.1 explicit JSON, direct GLB content and REPLACE refinement. Tight
local bounding boxes; conservative geometric error in metres, including every
approximation/quantization contribution. Finest leaves may have geometricError
zero but retain a separate reconstruction/source-accuracy report. Use absolute
same-origin `/api/lidar/files/<sha>.glb` and `.json` content-addressed URLs. No
service credential in client code. Preserve original LAZ externally.

Start localized Float32 + lossless meshopt compression. Vendored Cesium 1.146
supports KHR_meshopt_compression, EXT_meshopt_compression and KHR_mesh_quantization.
Decode and compare geometry/attributes before accepting assets. Quantization
must share boundary coordinates and explicitly join the error budget. For a
uniform 100 m range, Uint16 nearest rounding has a worst 3D bound ~1.32 mm;
actual non-cubic tile extents need the corresponding per-axis bound instead.

Local BallMerge triangle unions can be nonmanifold. Geometry-preserving CGAL
orientation duplicates coordinates, never changes a triangle's position. Use
flat/crease-aware normals and weld only identical complete attributes. Averaging
all coincident positions can smooth intersecting sheets incorrectly. Initially
use doubleSided materials. Assign faces lying exactly on an interior divider to
ONE owner to prevent duplicate coplanar surfaces. Clip/interpolation tolerance,
mixed LOD seams and owned facet coverage require independent checks.

Fine render clusters should be spatially bounded, smaller than the 100 m
processing patches. Coarse parents should merge children and release INTERNAL
processing dividers; otherwise the border triangle floor dominates distant views.
Keep exterior shared geometry unchanged. Propagate conservative parent error.

## Streaming and performance

Initial Cesium settings: maximumScreenSpaceError 1.5, cacheBytes 128 MiB,
maximumCacheOverflowBytes 64 MiB, skipLevelOfDetail false, dynamicScreenSpaceError
false, foveatedScreenSpaceError false. These are starting budgets, not proven
Chromebook performance. Use tileFailed/loadProgress/tilesLoaded to report failures,
wake manual rendering and detect settlement. Keep parent until replacement ready;
measure visible transitions before adding any crossfade. Avoid the old DEM terrain
occluding/z-fighting with the full-XYZ mesh. Preserve existing camera collision
guard; programmatic camera movement is not covered by a simple enableCollision.

Measure source cold/warm download latency, decode time, CPU/GPU memory estimates,
visible/submitted triangles and actual frame times for matched overview and
extreme-close cameras. Controller fixtures are not browser FPS benchmarks.

## Source-specific imagery

Generate explicit UVs from real geographic coordinates and the photo affine
transform. Embed authentic JPEG/PNG initially. Existing enhanced photo coverage
is only ~1.77 km and does not cover the new 2 km NZTM square. Test the actual
footprint; never clamp missing coverage into edge streaks. Reuse source-specific
AI enhancement only where georeferencing and coverage match. The old cliff
shader assumes the old heightfield/grid/gutters and must not be copied unchanged.
No generic cliff addon or generated geology. Baked source UVs first; any Cesium
CustomShader port is separate tested work. Top-down imagery does not contain
unobserved vertical-face photographs.

## Primary documentation

- https://cesium.com/learn/cesiumjs/ref-doc/Cesium3DTileset.html
- https://github.com/CesiumGS/3d-tiles/blob/main/specification/README.adoc
- https://github.com/KhronosGroup/glTF/blob/main/extensions/2.0/Khronos/KHR_mesh_quantization/README.md
- https://github.com/CesiumGS/cesium/blob/1.146/CHANGES.md
- https://proj.org/en/stable/operations/transformations/vgridshift.html
- https://pyproj4.github.io/pyproj/stable/api/transformer.html
- https://www.linz.govt.nz/guidance/geodetic-system/coordinate-systems-used-new-zealand/vertical-datums/new-zealand-quasigeoid-2016-nzgeoid2016
