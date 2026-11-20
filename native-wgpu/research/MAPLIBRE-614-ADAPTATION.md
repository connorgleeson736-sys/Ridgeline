# Ridgeline camera, cache and terrain adaptation

Decision date: 2026-10-30 (Melbourne). User keeps Rust/wgpu, asks familiar MapLibre
behavior, prioritizes maximum performance over matching internal structure, keeps
full imagery/terrain detail, and requests targeted checks instead of broad loops.
User decisions: match Ridgeline0.18camera feel; 1GBadaptive automatic diskcache;
immediate imagery replacement for minimum GPU work. Saved regions and original
six overview ZIPs remain separate and preserved. Capacitor stays untouched.

## Actual source reference

Ridgeline0.18source `d880143418ac46ece98adff0b3b2fbaedf044d46` packages MapLibre
GL JS6.14.0, confirmed by its `public/assets/maplibre-provenance.json` and published
APK records. Refreshed `android-preview-download` remains39431da. Read:

- [6.14 inertia](https://github.com/maplibre/maplibre-gl-js/blob/v6.14.0/src/ui/handler_inertia.ts)
- [6.14 two-finger gesture handlers](https://github.com/maplibre/maplibre-gl-js/blob/v6.14.0/src/ui/handler/two_fingers_touch.ts)
- [6.14 camera and terrain clearance](https://github.com/maplibre/maplibre-gl-js/blob/v6.14.0/src/ui/camera.ts)
- [6.14 terrain mesh/pipeline](https://github.com/maplibre/maplibre-gl-js/blob/v6.14.0/src/render/terrain.ts)
- [Cache retention implementation](https://github.com/maplibre/maplibre-gl-js/blob/62247a734e635e2b7cb2584fb99fc0d201128c57/src/tile/tile_cache.ts)

The MapLibre BSD notice is in `licenses/maplibre-gl-js.txt` and copied into APK
assets. This adapts algorithms/behavior; the engine is still Kotlin/Rust/wgpu.

## Implemented behavior and deliberate differences

| Area | Current Ridgeline implementation |
|---|---|
| Drag/pinch anchor | One actual terrain pick at gesture start, fixed geographic anchor/height, CPU ray-plane solves; no depth readbacks. |
| Pan momentum | Reference60ms velocity window,160ms retention,0.3linearity,2500deceleration,1400logicalpx/s cap and reference easing. Holding before release stops it. |
| Pinch/rotate/tilt | Reference0.1zoom activation,25px rotation activation,correct bearing direction,-0.5degree/logicalpx parallel-finger tilt,maxpitch85. Quick final pointer releases retain momentum without treating centroid changes as a pan. |
| Controls | Interruptible300ms zoom/doubletap/north easing; terrain anchor and held elevation retained. |
| Terrain collision | CPU queries at eye and3x3near-plane samples, against actual resident triangles; lifts before render. No invented ground when terrain is unavailable. |
| Centre height | Actual rendered geometry, gated on finest requested centre source; survives decoded-DEM cache eviction. Safety results cached until camera,viewport,demand or geometry changes. |
| Automatic caching | Up to1,000,000,000bytes, limited to one-eighth free cache storage; normal HTTP freshness/conditional requests retained. Explicit offline regions are not automatic-cache entries. |
| Work sharing | Shared body requests across DEM,imagery,profiles,downloads; offline-only/network policies remain distinct; saved regions attach shared original bytes individually. |
| Residency | CPU ready cache prefers evicting off-demand entries. GPU cache recency follows actual rendered use. Requests/uploads remain bounded. View selection pauses in background. |
| Terrain GPU representation | One shared full129x129surface/skirt grid; each tile uploads originalf32height samples in a129x129R32Float texture. Same diagonals,skirts,CPU picks and source detail. |
| Imagery | Direct sampling of original full-resolution imagery/mips. Immediate replacement, no fade loop, no intermediate terrain composition. |

The shared-grid change reduces raw per-tile terrain GPU payload from399384to66564
bytes, plusone266256byte shared grid. These are resource payload sizes, excluding
GPU allocator overhead. Texture-fetch cost differs from the previous vertex-buffer
path; no phone FPS/power claim is made without measurement.

## Focused checks and remaining differences

Three motion/gesture tests pass; Kotlin integrations compile. GPU fixture passes
Vulkan and GLES after the shared grid change, retaining exact reference grade,
independent source cover, mip filtering and mixed-LOD skirts. Dedicated real-height
CPU clearance/payload assertions pass on the updated fixture. All three optimized
Android JNI ABIs and the normal Base APK are built separately from local trust
profiles. No emulator/lint/full-suite cycles are repeated for this checkpoint.

No claim of complete MapLibre equivalence: the viewport selector still uses the
bounded distance/focus heuristic rather than the complete GL JS elevation-aware
quadtree. Idle camera correction uses a held-elevation lift instead of the entire
reference pitch/zoom re-solve. Full physical-device interaction/performance and
real-terrain transition acceptance remain open. Future work should prioritize
measured frame-time/loading bottlenecks and visible behavior, not recreating
unused style/vector/render-to-texture infrastructure.
