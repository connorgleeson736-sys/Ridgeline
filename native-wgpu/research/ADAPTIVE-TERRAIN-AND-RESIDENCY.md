# Adaptive terrain and warm GPU resources

2026-10-30 Melbourne. User prioritizes maximum performance with retained visual
quality, asks to reuse0.18optimizations, adds existing-phone ZIP attachments, and
leaves adaptive implementation choice to us. Chosen: carry over0.18's conservative
shared-grid policy, avoid per-view irregular remeshing or a new terrain engine.

## Sources actually read

- Authoritative0.18source `d880143418ac46ece98adff0b3b2fbaedf044d46`:
  `public/adaptive-terrain.js`, `ADAPTIVE_TERRAIN.md`,
  `scripts/patch-adaptive-terrain.py`. The128surface is retained;32/64interiors
  are selected only within bounded screen error, with full128boundaries/skirts.
- [MARTINI RTIN implementation](https://github.com/mapbox/martini/blob/main/index.js)
  and [algorithm overview](https://github.com/mapbox/martini): hierarchical
  triangle errors and threshold-specific irregular mesh generation. Attractive
  triangle reduction, but unique tile index generation/upload and stitching would
  add work/complexity compared with our existing reusable grid/index atlas.
- [CDLOD implementation/paper](https://github.com/fstrugar/CDLOD): regular-grid
  quadtree, distance-based selection and continuous transitions. Useful principles;
  replacing our renderer with the older DirectX9architecture is unnecessary.
- Existing MapLibre tile-pyramid/cache study in MAPLIBRE-GESTURES-HLOD.md and
  exact6.14camera/terrain study in MAPLIBRE-614-ADAPTATION.md remain applicable.

## Implementation

Original129x129f32height data is unchanged. Two conservative residual error bounds
are computed once on the existing source worker after mesh preparation, then
cached with the immutable source buffer. No profile analysis runs on the GPU
owner thread or during each camera update, and no extra DEM requests are made.
Subtract a coarse-cell affine plane and bound its residual range over every fine
vertex. Both surfaces/fans are convex interpolants inside the cell, so the range
bounds their vertical difference; slopes can simplify while spikes retain detail.
The original0.18numeric margin is retained.

The renderer bounds the height-to-screen derivative over the full actual terrain
height prism. New simplification needs <=0.6drawing-buffer pixels and <=10pixel
cells. Retention uses the original0.9pixel/13pixel band; coarsening requires350ms
of stable eligibility and refinement is immediate. Unknown metadata, near-plane
crossings and unsupported subrectangles keep the full mesh. One delayed wake can
finish stable coarsening without a permanent frame loop or delaying new input.

Interior index variants use the existing shared grid/height texture. Every visible
subrectangle keeps its original fine boundary samples and original skirt indices,
including independent photographic subdivisions. Full-tile surface counts:
128=32768,64=8952,32=2680. Variants live in the bounded existing index atlas; exact
selected-index overflow retains quality when the atlas is deliberately constrained.

Route-bearing sources stay at128so the separate terrain-draped route/marker pass
keeps exact depth relationships. Profile, camera and picking queries retain the
full original heightfield. This is bounded visual approximation, not byte-identical
geometry in every eligible interior; original source detail remains available.

## Residency and CPU submission work

On Android, recent terrain retention is now three times the previous geometry
source count, capped192. Example:64active-source budget can retain192terrain
sources while photographic source caps stay unchanged. With shared heights this
uses less raw combined geometry GPU/CPU payload than the old duplicated-vertex
representation at64sources (allocator/driver overhead excluded). Revisited sources
reuse their height textures/fields and metadata, rather than rebuilding them.
Actual Android memory-pressure signals release off-demand cache entries while
preserving current demanded/active coverage. UI-hidden alone does not discard the
warm cache. Automatic disk cache/saved regions remain separate.

Terrain grid and instance buffers bind once per pass. Image/height bindings change
only when needed, and adjacent compatible patches batch into instanced draws.
Visible-patch, instance-data and adaptive-selection scratch storage is reused.
The source upload now transfers ownership of its height vector rather than cloning
it into the retained CPU field. No unchanged scene/instance transfer was added.

## Focused evidence / limits

Two new core checks cover full boundaries/skirt identity, winding/coverage, plane
and spike error metadata. The GPU fixture reduced a plane from32768to8952surface
triangles with identical output pixels, and verifies route sources restore full
geometry. Vulkan and GLES execute the changed path; retention/memory-pressure
assertions keep demanded terrain while releasing unused cache. These are controlled
fixtures, not physical-phone FPS/power results or complete real-terrain acceptance.
The AndroidSAFattachment picker compiles and all six original ZIP headers match its
range-reader layout; real document-provider permissions still need phone execution.
