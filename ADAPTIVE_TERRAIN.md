# Native adaptive terrain grids

Applies automatically throughout the New Zealand viewer in Original and Original + Direct. LINZ imagery and elevation streaming, source LOD, routes and measured elevation samples are unchanged. No offline terrain preprocessing or additional tile requests are used. luma.gl is unchanged.

`public/adaptive-terrain.js` selects 32/64/128 interiors with all original 128-grid boundary vertices and all native skirts. Variants share the native vertex buffer and own only index buffers/VAOs. Surface triangles are 2,680 / 8,952 / 32,768; with the original skirts they are 3,704 / 9,976 / 33,792. The additional boundary cells deliberately reduce the theoretical savings slightly to preserve seams.

Metadata is measured from the actual resident native DEM sampler at the shader's half-pixel coordinates. Inside each coarse cell, subtract an affine plane from the fine-grid heights. Both the native surface and the coarse surface are convex interpolants of those residuals, so their vertical separation is bounded by the residual range, including boundary fans. This measures simplification relative to the original 128 mesh, not ground-truth accuracy of the DEM. A small numeric margin is included.

The camera's native perspective matrix bounds the height-to-screen derivative over the tile's full measured elevation prism. Coarsening requires at most 0.6 drawing-buffer pixels of bounded error and a bounded projected cell size. Existing coarse grids are retained within a 0.9 pixel / 13 pixel-cell band; refinement is immediate. Coarsening requires 350 ms of stable eligibility. Unknown metadata, near-plane crossings, unsupported geometry and globe mode retain the original mesh.

Analysis uses idle callbacks in roughly 2 ms slices and pauses during movement or when hidden. At most 32 jobs and one 129² Float64 sample array are retained; completed metadata belongs to a WeakMap keyed by the native DEM. Source changes, data replacement and neighbour-border backfill invalidate metadata. Only visible work remains queued. There is no permanent animation loop.

Selections are frozen before each native painter render, shared by colour/depth/mesh lookups, and mark terrain depth dirty when they change. Runtime toggling, 2D/3D transitions, renderer removal and context restoration clean up variant resources. The native vertex buffer remains owned by the native terrain. Route/profile elevations keep the original DEM sampling path; the visual approximation is bounded in screen space.

The pinned engine bridge in `scripts/patch-adaptive-terrain.py` only exposes owned index-buffer creation and tracks DEM border revisions. `checks/engine-patch.cjs` reproduces all patches from the recorded baseline. Browser assets have explicit cache-busting versions.

Validation:

- `node checks/adaptive-terrain.cjs`: manifold coverage, winding, identical 128 boundaries and skirt indices, exact match to native 128 indices, 16,000 continuous surface-error samples, 24 real native Mercator camera cases spanning North/South Island latitudes, actual native DEM/buffer types, shared depth/colour selection, hysteresis/refinement, cooperative work, toggling, border mutation, context lifecycle and ownership cleanup.
- `python3 checks/maplibre-direct-shader.py`: production Direct shader pixels and GLES rasterisation of adjacent 32/64, 64/128 and 32/128 surfaces without holes/winding errors.
- Existing Direct, filtering, diagnostics, native performance and patch-reproduction checks.

The local browser preview could not start because the managed environment reported another development server. Native CPU and offscreen GLES checks do not establish device FPS or replace on-device visual assessment. Use the Adaptive terrain detail toggle and live performance diagnostics to compare identical camera paths.
