> Archived on 2026-10-15: the user retired all experimental terrain viewers. The active website is the main NZ planner only. See `BASE_VIEWER_STATUS.md`. These historical processing and recovery records are retained; they do not describe the current published UI.

# Mt Cook renderer comparison

Open `/cook-comparison.html`, or **Layers → Mt Cook · compare three renderers**.
This page compares real rendering pipelines, one active at a time, with a shared
camera and captured source data. It does not modify route calculations.

## Data and renderer scope

The Aoraki/Mount Cook summit region is approximately 1.77 km across. Its
2,049 × 2,049 heights were decoded from an 8 × 8 set of LINZ Terrain-RGB tiles at
zoom 17, plus adjacent edge pixels, captured 13 October 2026. The ground sample
spacing is approximately 0.865 m. Source survey resolution and accuracy are not
certified by that sampling interval. Original decoded heights are stored as
lossless gzip Float32 data; imagery is lossless WebP. The worker verifies the
decoded-height SHA-256 and finite values. No generated elevation is used.

* **Original Ridgeline:** the actual MapLibre 5.6.2 raster-dem terrain pipeline,
  original horizon LOD function and aerial imagery. A local protocol encodes
  tiles from the common source grid instead of making live LINZ requests.
* **Adaptive:** a MapLibre custom layer with 64 potential 257² source chunks.
  Each visible chunk uses verified MARTINI RTIN geometry. All 1,024 perimeter
  nodes of every chunk are retained, so differently refined neighbors share
  exact edges. Height-error caps tighten according to projected pixel error.
  Off-screen chunks are culled; worker mesh results and GPU caches are bounded.
* **CesiumJS 1.146.0:** actual Cesium regional WebMercator heightmap terrain.
  A root 257² tile samples the source at stride 8; child levels use strides
  4, 2 and 1. Native leaves have `childTileMask: 0` and zero geometric error,
  preventing requests for nonexistent finer source heights. Coarse-grid errors
  were measured using Cesium's actual grid diagonal against every source node.
  The photo uses its own exact pixel-edge extent; DEM nodes use pixel centers.
  No Cesium ion account, World Terrain or default imagery is used.

Cesium's zero leaf geometric error marks the source-resolution stopping point;
it does not certify zero rendered height error. Cesium's native mesh encoding
can quantize positions/heights further. The adaptive metre limit and Cesium
pixel limit therefore must not be interpreted as equivalent quality settings.

Cesium in this comparison is a **heightmap provider**, not precomputed
quantized-mesh terrain. Therefore this is not a benchmark of a production
precomputed Cesium terrain service.

## Camera and navigation

The shared gesture controls pan, orbit and zoom each renderer using the same
view parameters. Zoom spans 14–22, with an **Inspect ridge** shortcut at 20.3.
At zoom 22 on a typical desktop viewport, camera-to-target range is on the order
of tens of metres. Elevation follows the original source grid when panning;
the camera guard raises the target when necessary to clear source terrain.
The maximum tilt is 70 degrees. Zoom beyond the captured source resolution
magnifies existing geometry and imagery rather than creating new detail.

Cesium's heading/pitch/range and vertical FOV are derived from the exact
MapLibre camera. The photo and numerical height samples are shared. The LINZ
height reference is retained numerically for this scoped rendering experiment;
the output is not a geodetic conversion or survey product. Cesium's curved
ellipsoid and MapLibre's Mercator geometry remain different render models.

All engines use the same fixed pixel ratio, capped at 1.5; antialiasing,
atmosphere, lighting extras and animated effects are disabled. The original
renderer comparison deliberately does not apply movement-only resolution
changes. A floating panel preserves the map's full viewport dimensions.

## Device comparison

**Run three-way comparison** freezes input, loads each renderer and waits for
its tiles or adaptive geometry to settle. It then warms up 15 rendered frames
and records 60 frame intervals for each engine. Results show median and 95th
percentile intervals. Only actual MapLibre render or Cesium postRender events
count. Hidden MapLibre events never count as Cesium frames, and only one Cesium
animation callback can be pending. Cesium is otherwise rendered on demand.

Cancellation, visibility changes, viewport resize and timeouts restore
interaction. This measures on-device rendered frame intervals, not isolated
GPU draw time or API latency. Values may be refresh-rate limited. Geometry
accuracy/LOD settings differ between engines and are displayed explicitly;
equal source data is not a guarantee of equal rendered detail. No speedup or
winner has been claimed from server-side tests.

## Validation

```sh
node checks/cook-data.cjs
node checks/cook-controller.cjs
```

The data checks use the bundled real Cesium provider classes and the bundled
MapLibre camera math. They verify source integrity, regional tile availability,
native leaves, shared RTIN boundaries at different error tolerances, source
coordinates, culling and deep zoom. Cesium's actual heightmap tessellation was
also executed against captured tiles. Controller tests use actual worker
geometry and camera math with mocked DOM/graphics/Viewers to verify switching,
native raster-dem configuration, close views, frame-count integrity,
cancellation and disposal. Their simulated timings are not performance data.

The adaptive shaders compiled and rendered the actual Mt Cook heights, imagery
and MapLibre camera under native OpenGL ES 3.2. Full browser/UI QA was not
available in the implementation environment, so actual Cesium browser rendering
and browser integration remain unverified there.

Cesium and its worker assets are vendored from the official npm 1.146.0 package;
the license and third-party notices remain in `dist/assets/cesium/`.
