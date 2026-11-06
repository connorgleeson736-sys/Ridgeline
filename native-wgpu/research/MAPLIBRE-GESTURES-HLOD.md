# Gesture and HLOD implementation study

Read current upstream source on 2026-10-30 (Melbourne). This is a behavior study;
Ridgeline keeps its independent Kotlin/Rust/wgpu implementation and full sources.

## Sources actually inspected

MapLibre GL JS revision `62247a734e635e2b7cb2584fb99fc0d201128c57`:
- [Terrain gesture state and camera controls](https://github.com/maplibre/maplibre-gl-js/blob/62247a734e635e2b7cb2584fb99fc0d201128c57/src/ui/handler_manager.ts): hold center elevation through a gesture; sample the terrain anchor once; fallback stays fixed if terrain was missing; solve screen rays at that elevation before/after pan/zoom; reject near-camera/horizon degeneracies.
- [Terrain anchor regression cases](https://github.com/maplibre/maplibre-gl-js/blob/62247a734e635e2b7cb2584fb99fc0d201128c57/src/ui/handler_manager.test.ts): pitched drag, moving-centroid pinch, terrain loading during a gesture and elevated terrain under the pointer.
- [Inertia](https://github.com/maplibre/maplibre-gl-js/blob/62247a734e635e2b7cb2584fb99fc0d201128c57/src/ui/handler_inertia.ts): short recent velocity window, bounded speed/deceleration. Studied, not ported in this checkpoint.
- [Raster transitions](https://github.com/maplibre/maplibre-gl-js/blob/62247a734e635e2b7cb2584fb99fc0d201128c57/src/tile/tile_manager_raster.ts): explicit incoming/outgoing ownership and bounded crossfade lifetime. Studied; image crossfade/terrain geomorph remain open.

MapLibre Native revision `b7e4178974fd256e4684b2412263737199051123`:
- [Tile pyramid](https://github.com/maplibre/maplibre-native/blob/b7e4178974fd256e4684b2412263737199051123/src/mln/renderer/tile_pyramid.cpp): ideal cover is separate from retained/cache tiles. Reuse existing children and parents while ideals load; cache residence alone does not make a tile ideal. Priority and retain sets follow the current view.

Cesium (current main, retrieved 2026-10-30):
- [Tileset traversal](https://github.com/CesiumGS/cesium/blob/main/packages/engine/Source/Scene/Cesium3DTilesetTraversal.js): screen-space error and visibility drive hierarchical traversal. Readiness and selection are separate; a cache is not the desired cover. Ridgeline retains its existing projected-size targets and full mesh/image source ceilings.

## Applied in Ridgeline

One actual rendered-terrain ray pick at gesture start, CPU ray-plane solves after
that, with fixed geographic anchor/height. No GPU depth readbacks, repeated terrain
raycasts per move, or allocations of full meshes per gesture. Panning translates
the camera opposite the finger so map content follows it. Pinch centroid and
bearing/pitch changes share that solve. One-to-two-to-one pointer transitions
rebase the anchor; terrain arrival cannot change drag sensitivity mid-gesture.
Center-height correction waits until release and eases for 350ms, with no idle loop.

GPU residency and the current ideal source cover are now separate. Ready ideal
sources win over cached descendants. Unready ideals retain actual parent/child
coverage. Reversing LOD with the same demand/residency produces the same pixels,
rather than depending on which direction the camera arrived from. Original
256px imagery, 128-cell terrain, filtering and source ceilings are unchanged.

Visible leaves retain their detail requests instead of being collapsed to budget
an entire ancestor chain. A bounded part of the existing source pool loads
fallbacks. Two terrain and two imagery workers prevent DEM halos starving photos;
queued work is reprioritized/pruned against the latest demand, transient failures
get bounded backoff retries, and selection is throttled to 50ms during movement.
Resident bytes/counts and GPU upload-per-frame limits remain bounded.

## Close zoom follow-up

A focused zoom19 regression reproduced center geometry stuck at z16 (320x640,
pitch0, elevation3670m, geometry request budget36). The old projected-depth score
was equal for all tiles under a level camera, while conservative height boxes
made distant tiles appear eligible. This spent the hard budget before refining
the focus. Increasing the camera zoom limit would not address that defect; the
reference also caps the camera at19.

Read [MapLibre Native distance LOD and minimum focus radius](https://github.com/maplibre/maplibre-native/blob/b7e4178974fd256e4684b2412263737199051123/src/mln/util/tile_cover.cpp)
and [MapLibre JS variable-zoom cover](https://github.com/maplibre/maplibre-gl-js/blob/62247a734e635e2b7cb2584fb99fc0d201128c57/src/geo/projection/covering_tiles.ts).
Ridgeline now scores distance to tile bounds with MapLibre Native's square-root
pitch/area correction, normalized to preserve the existing center detail target.
The focus branch and one-tile focus neighborhood refine first. Remaining budget
serves the rest of the view. Conservative terrain visibility is retained so
unknown mountain heights cannot be silently culled.

At zoom19 the center now reaches geometry20/21 (full source DEM18) and imagery19
at pitches0,45,58,75,80, within the same request budgets. These are selection
results, not a claim that every geographic location has source data at that level.
The closest leaf also downloads immediately after coarse coverage, ahead of
intermediate fallback ancestors. No artificial zoom-limit increase or raster
upscaling is used to disguise missing source detail.

## Remaining work / limits

This does not port MapLibre or establish visual equivalence. HLOD geometry morphs,
imagery crossfades, collision response, inertia and phone performance still need
implementation/measurement. Source budget pressure can still choose coarser
visible cover; it never downsamples an individual image or terrain mesh. The native
selection heuristic still differs from MapLibre's full terrain-aware cover policy.
