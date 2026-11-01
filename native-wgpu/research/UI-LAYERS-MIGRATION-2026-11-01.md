# Capacitor UI, route editing and map-layer migration — work in progress

Reference refreshed: android-preview-download, released Capacitor 0.18.0 source
`d880143418ac46ece98adff0b3b2fbaedf044d46`. Native baseline 0.1.8 publication
`91aaa7b`. This document is a working ledger, not a claim of completed/device
parity or an available 0.1.9 APK.

Implemented source:

- Existing RouteController/editor/storage retained. Explicit Edit gates waypoint
  selection, append/insertion/delete and immediate touch dragging. Three-dp drag
  threshold; preview does not save; completed drag has one undo step; cancel rolls
  back. Editing taps bypass GestureDetector's double-tap waiting, and double-tap
  zoom is disabled during editing.
- Capacitor paper/green/orange controls, route sidebar with previews, ordered
  combination selection and per-route reversal; show/close multiple routes and
  close-all. Compact/expanded route sheet uses the exact two reference stop rules.
  Sheet movement transforms/clips rather than resizing per pointer event.
- Four route statistics, native chart inspection and range selection with exact
  endpoint interpolation/missing-gap behavior. Profiles cancel during point drags.
- Layers/Advanced panel, Aerial/Satellite/NZ Topo, names, peaks, roads, tracks, huts,
  contours, imagery boundary; natural colour, native Igor-style shading, sky,
  relief and existing detail/download/cache controls. Original HTTP fixes retained.
- Bounded native MVT decoder; exact reference style JSON generated from the
  pinned Capacitor source. Decode only source layers which are enabled at this
  zoom. One background source transfer; only current source tiles retained;
  cancellation on motion/pause. Existing shared transport/persistent cache/offline
  access reused, without an independent request pool.
- Cached depth-tested geographic line batch and native-font atlas; screen-grid
  collision placement, no per-label Android View, no GPU readback, cached geometry
  stays independent of camera motion. Shared route/cartography pipeline and
  reusable vertex-buffer capacities avoid duplicate compilation/allocation.
- Raw Topo pixels bypass aerial grade and use nearest mip sampling. Relief
  propagates through rendering, picking, clearance, bounds and adaptive error
  projection; route/profile source heights remain unexaggerated.

First focused verification: eight JVM checks pass (route controller plus MVT,
reference style and profile sections). Actual LINZ z12/3983/2600 protobuf fixture
is 41,926 decompressed bytes. Names-only enabled output is unchanged when skipped
source layers are omitted: 135 total features versus 20 relevant features. Five
alternating host batches measure decode median 3.513 ms → 0.088 ms per tile.
This is a host subsystem result, not phone FPS or network throughput. Android
Kotlin compilation and host Rust checking pass. GPU runtime checks are pending.

Remaining before release: complete linked profile marker/GPX sharing and direct
editing edge checks; review route styling; finish pause/teardown/error bounds;
compile JNI for Android; run focused GPU layer/relief/Topo fixture; check original
assets, build/sign/verify all three native ABIs and APK; document layer limits and
remaining reference differences. No physical device or emulator is connected.

Source/reference records and targeted results are in
`../validation/ui-layers-2026-11-01/`.
