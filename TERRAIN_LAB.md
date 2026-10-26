> Archived on 2026-10-15: the user retired all experimental terrain viewers. The active website is the main NZ planner only. See `BASE_VIEWER_STATUS.md`. These historical processing and recovery records are retained; they do not describe the current published UI.

# Regional adaptive terrain prototype

Open `/terrain-lab.html`, or use **Layers → Adaptive terrain lab** in the planner.
This is a separate renderer experiment; the planner's terrain and route-elevation
pipeline still operate as before.

## Geometry and data

The lab renders an indexed RTIN mesh in a MapLibre WebGL 2 custom layer. MARTINI
runs in a worker. Vertex attributes contain compact integer grid coordinates;
the shader reads original heights from an R32F texture. Mesh replacement is
atomic. The same camera, heights, imagery, resolution and material are used for
both modes. The reference is the complete sampled source grid, **not** the
planner's existing MapLibre terrain renderer.

The captured Taranaki summit patch contains 1,025 × 1,025 original elevation
samples, at approximately 0.924 m sample spacing over a 946 m region. It came
from LINZ's Terrain-RGB elevation mosaic at zoom 17 on 13 October 2026. The
regional Taranaki LiDAR 1m DEM (2021) is listed in LINZ's mosaic configuration;
this snapshot is not a per-pixel certification of that underlying dataset or
survey accuracy. Sampling spacing and underlying DEM resolution are distinct.
Decoded original heights are stored losslessly as gzipped Float32 values;
aerial imagery is stored in lossless WebP. `region.json` carries the dimensions,
coordinates, attribution and decoded-height SHA-256, checked by the worker.

MARTINI's hierarchy selects candidate triangles. A separate scan conversion
checks every source-grid node against the resulting triangle planes. The
selector is tightened until the selected maximum height difference is met.
Meshes retain original heights at selected vertices; no AI or synthetic terrain
is used. View-based refinement chooses a tighter quantized tolerance as the
camera gets closer, never exceeding the user's selected limit. It runs after
movement settles and discards stale results.

## Measured geometry on this snapshot

| Selected limit | Triangles | Measured max source-node error |
| --- | ---: | ---: |
| 0.10 m | 1,717,174 | 0.000183 m |
| 0.25 m | 830,489 | 0.200043 m |
| 0.50 m | 333,034 | 0.425049 m |
| 1.00 m | 194,418 | 0.637512 m |
| 2.00 m | 79,873 | 1.500061 m |
| Full sampled grid | 2,097,152 | 0 m at source nodes |

At the default 0.50 m limit the triangle reduction is 84.1%. This is a geometry
measurement, not an FPS improvement. Error limits apply to original source
nodes, not unsampled features between them.

## Comparison and validation

The device comparison preloads both meshes, fixes the camera, disables
wireframe, warms up eight frames per mode and records 24 frame intervals each.
When supported, disjoint-aware GPU timer queries measure only the terrain draw.
Frame intervals include browser work and refresh-rate limits. Unsupported GPU
timers are reported as unavailable. Cancellation, visibility changes and
navigation dispose or restore test state. A slower full-grid reference can
temporarily load the device substantially; this comparison is user initiated.

Run the portable checks from the repository root:

```sh
node checks/mesh-geometry.cjs
node checks/terrain-lab.cjs
TEST_GPU=1 node checks/terrain-lab.cjs
```

Geometry tests verify the snapshot hash, all 1,050,625 nodes, five limits,
conforming interior topology, a planar surface and a narrow peak. Integration
checks exercise the actual worker and controller with mocked browser/graphics
APIs and the camera math from the shipped MapLibre build, including texture row alignment, mesh switching, comparison timers,
cancellation, panel controls and cleanup. Their simulated timer values are not
performance measurements. Separately, the real shaders compiled, linked and
rendered the actual summit mesh and imagery using native OpenGL ES 3.2 and the
actual MapLibre camera matrix. The renderer reads the runtime
`defaultProjectionData.mainMatrix` field; a regression check uses the shipped
engine's ProjectionData instead of copying the incorrect `projectionMatrix`
field mentioned in its custom-layer documentation.
Full browser QA was unavailable in the implementation environment.

## Prototype boundary

This is one static region. Production integration still needs streamed mesh
tiles, cross-tile shared boundary rules, bounded geometry caches, cancellation
and device/browser profiling. The regional results do not establish a speedup
over the planner's current renderer or justify replacing it yet. No route
calculation is performed on simplified geometry.

MARTINI is pinned to upstream blob `e93a9f3fc76b196adc44db3128d859d85fa37cbd`
and distributed with its ISC license in `dist/assets/vendor/`.
