# MapLibre-style seam protections

This checkpoint supersedes the user's previous request to remove all skirts.
Selective skirts are restored, preserving original surface heights and detail.

- Geometry cover owns seams; imagery subdivisions do not create internal walls.
- Collinear opposite edges omit skirts only when their entire shared coverage is
  proven continuous against exact resident triangulated height fields. Missing,
  discontinuous, unaligned or unproven joins retain protection. World-wrap edges
  conservatively keep skirts. No source height is averaged, overwritten or reduced.
- Edge masks rebuild on geometry-cover changes or newly uploaded height fields,
  not every camera frame. Surface/profile data stay immutable and caches valid.
- Skirt depth uses MapLibre 6.14's mean-Earth-circumference / 2^logicalZoom / 5.
  Screen density does not shorten it. Skirts mask gaps within their depth, not
  repair arbitrary missing geography or mismatched provider data.
- One extra shared UV grid is 133,128 bytes for the entire renderer, not per tile.
  Full tile perimeter adds at most 1,024 triangles to a full 32,768-triangle surface;
  proof-based omission reduces internal edge work. Adaptive surfaces stay adaptive.
  Both grids fit uint16 indices; original R32Float height textures remain unchanged.
- Existing neighbour-halo sampling uses actual adjoining original DEM pixels at
  pixel centres; it already avoids independent per-tile clamping at shared edges.
  It provides the role of MapLibre DEM border backfilling without copied mutable
  DEM borders or later GPU height patches. New bitwise shared-edge tests exercise
  all 129 east/south vertices across original DEM and overscaled source boundaries.
- Existing nearest resident parent and complete sharper-child coverage are kept;
  coarse coverage is replaced only where usable finer data exists. GPU fixture
  checks no fallback holes and history-independent ownership.
- Existing exact geographic image UV/crop mapping, trilinear full nine-level mips
  and anisotropy remain. ClampToEdge is explicit on all sampler axes; array-layer
  isolation is preserved. Source-photograph colour discontinuities are not hidden
  by inventing blends or changing original imagery pixels.

Evidence: software Vulkan and GLES close 36,092 and 36,093 exposed background pixels
in a controlled discontinuous parent/child fixture versus negligible skirt depth.
A continuous parent/child fixture keeps eight outer edge segments and omits all
internal seams. Four child photographs on one terrain source retain only the
original 1,024 perimeter triangles. Exact overflow indices, grading, source cover,
mip average, original heights/picking and unchanged settled-frame uploads pass.
26 core, four renderer and one CLI tests pass; seven focused DEM JVM checks pass.
All three optimized Android ABIs build. These are correctness checks, not A20 FPS
or real-terrain visual acceptance. The forced GLES texture-array path is checked
separately with gpu_smoke --gles --arrays and passes (36,093 repaired pixels).

Reference: refreshed android-preview-download, release0.18 source d880143418ac46ece98adff0b3b2fbaedf044d46,
public/assets/maplibre-gl.mjs (DEM backfillBorder, u_ele_delta/getSkirtLength) and
existing native DemSampling/source-cover algorithms. This keeps Ridgeline's
full-resolution direct sampling, current caches and no-fade replacement policy.
