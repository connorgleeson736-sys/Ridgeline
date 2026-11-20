# Terrain stability, background coverage and presentation recovery

Reported target: Samsung A20. User clarified the differing up/down gesture means
**two-finger tilt**. No A20 execution or measured performance claim in this change.

## Reference and source reading

Refreshed android-preview-download (39431da); authoritative 0.18.0 source remains
[d880143](https://github.com/connorgleeson736-sys/Ridgeline/tree/d880143418ac46ece98adff0b3b2fbaedf044d46).
Read the corresponding MapLibre GL JS 6.14.0 implementation:

- [two_fingers_touch.ts](https://github.com/maplibre/maplibre-gl-js/blob/v6.14.0/src/ui/handler/two_fingers_touch.ts): deliberate two-finger pitch, logical-pixel sensitivity and exclusive handling.
- [handler_manager.ts](https://github.com/maplibre/maplibre-gl-js/blob/v6.14.0/src/ui/handler_manager.ts): pitch uses the map centre; terrain elevation held for manipulation and pivot restored afterwards.
- [transform_helper.ts](https://github.com/maplibre/maplibre-gl-js/blob/v6.14.0/src/geo/transform_helper.ts): recalculateZoomAndCenter preserves physical eye/pitch while changing centre elevation and zoom; collision correction can belong to the rendered transform rather than requested camera state.
- [wgpu 30 CurrentSurfaceTexture](https://docs.rs/wgpu/30.0.0/wgpu/enum.CurrentSurfaceTexture.html): timeout/occlusion are retry conditions, outdated surfaces need reconfiguration, lost surfaces need recreation.

The exact 6.14 handlers and transform helper were downloaded for inspection;
current upstream source was also consulted. This is a targeted adaptation, not a
claim of full MapLibre camera equivalence.

## Changes

Removed the 350ms automatic centre-height animation. Refining terrain now updates
the pivot along the view direction and adjusts zoom while retaining physical eye
and pitch. Terrain clearance outside manipulation adjusts the presented camera
without repeatedly rewriting requested state. During gestures, elevation has a
stable baseline, temporarily lifted for collision clearance; pitch is exclusive
and anchored to the map centre rather than the moving finger centroid. Planner
selection uses the actually presented collision-corrected pose.

Conservative limit: if recentering would cross zoom/geographic bounds, it is
skipped. The complete upstream max-zoom pivot search is not ported. Physical
acceptance over steep terrain, including this limit, remains open.

Tile demand now includes a bounded parent cover across the entire requested
region. The focus parent/fine tile start early; useful background parents then
precede remaining fine leaves. The previous priority placed many fallback parents
behind distant fine leaves. Source ceilings and full input resolution are unchanged.

Per-frame upload count is one during manipulation and two otherwise, with the
existing 650KiB cap. After a failed presentation, uploads pause while presentation
retries with bounded backoff. Unlike the previous eight-attempt cutoff, temporary
failures do not permanently stop the viewer. Lost surfaces are recreated while
retaining renderer resources; device loss/repeated hard errors recreate the
renderer and request CPU-cached data again. Retries do not hold up immediate input
vsyncs. This addresses observed failure paths, not a reproduced A20 device-loss
root cause; driver-specific issues may remain.

Skirts are omitted only on completely covered adjoining edges whose original
resident height fields agree exactly, or whose geometry source is shared. Missing,
off-grid or discontinuous joins retain skirts. Fine boundary vertices remain.
The edge mask is recomputed on cover/geometry changes, not every camera frame or
imagery arrival. Unused fallback geometry no longer forces route tessellation.

## Evidence and next measurements

Focused CameraMath tests (5) and SourceDemand tests (4) pass. Host Vulkan and
GLES fixtures pass: flat parent/child coverage drops from 16 to 8 skirt edges;
the discontinuity regression still repairs 36,092/36,093 exposed pixels. These
are synthetic correctness fixtures, not phone timings. Android target compiles.
No broad repeated test/lint/emulator cycle was run.

Next useful evidence is an A20 run around the same peak: tilt feel, stability,
background detail latency and logcat if a GPU recovery persists. Only measured
bottlenecks should justify larger changes such as texture arrays, indirect draws
or a new mesh representation. Source-quality reductions are not an optimization.
