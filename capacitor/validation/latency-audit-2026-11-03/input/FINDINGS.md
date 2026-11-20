# Ridgeline Android 0.18.0 latency audit

Audited the Capacitor Original + Direct renderer at [d880143](https://github.com/connorgleeson736-sys/Ridgeline/commit/d880143418ac46ece98adff0b3b2fbaedf044d46). The [Full release validation](https://github.com/connorgleeson736-sys/Ridgeline/blob/android-preview-download/Ridgeline-3D-0.18.0-validation.json) points to this commit. The local MapLibre module matched the branch's SHA-256. Three isolated experiments ran in Node. No Android frame or GPU times were captured for this audit.

The earlier device report recorded 100.7 ms mean and 183.8 ms p95 movement frames. Its LINZ selection scope alone measured 22 ms mean and 50.5 ms p95. The report predates 0.18.0, its scopes overlap, and its 442 ms p95 decode interval includes scheduling. Use those numbers to choose what to investigate. [Branch performance record](https://github.com/connorgleeson736-sys/Ridgeline/blob/d880143418ac46ece98adff0b3b2fbaedf044d46/capacitor/PERFORMANCE-0.18.0.md#L43-L49)

## 1. Separate showing the latest camera from selecting new tiles

The default selection interval is zero. An explicit interval wraps source/terrain manager updates, but any source content or metadata event sets a map-wide force flag. The next eligible render allows all wrapped updates that the engine calls. This couples imagery arrivals to the cost of moving the camera. The engine can draw an existing cover with a live camera. It also needs to update which loaded children or parents represent that cover, even when camera-based tile demand is unchanged. [Cadence controller](https://github.com/connorgleeson736-sys/Ridgeline/blob/d880143418ac46ece98adff0b3b2fbaedf044d46/public/selection-interval.js#L8-L26) · [Default settings](https://github.com/connorgleeson736-sys/Ridgeline/blob/d880143418ac46ece98adff0b3b2fbaedf044d46/public/loading-controls.js#L4-L9)

I replayed 60 camera-dirty render calls at 60 Hz through the shipped controller and selected its 100 ms setting. The quiet case drew 60 frames and updated the source 10 times. Delivering one LINZ content event before each frame produced 60 draws and 60 updates. This verifies the bypass. Real tile arrival rates determine how often it matters.

Give the engine separate reasons to repaint, reconcile loaded coverage and recompute ideal tile IDs. On a raster arrival, make its texture eligible to draw immediately and reconcile the affected cover from the last ideal IDs. Re-run geometric selection when the camera approaches the edge of resident cover, terrain bounds widen, or selection inputs change. Coalesce camera changes to the newest state while an older selection is in flight. Force a final exact selection at gesture end. Keep DEM arrival, style or source replacement, camera ground elevation changes and context restoration explicit. Simply suppressing all source events can leave holes or stale geometry. A longer timer alone also offers little relief when one frame already takes as long as the timer.

## 2. Reuse terrain bounds across imagery and terrain traversals

Every candidate in the Mercator quadtree asks for a 3D bounding volume. The same geographic nodes recur across source and surface traversals. Ridgeline's conservative visibility wrapper calls native getMinMaxElevation and then resolves the same source DEM again for the uncertainty margin. The first method already performs that lookup. The 0.18.0 patch sped up the lookup but both calls still occur. [Visibility wrapper](https://github.com/connorgleeson736-sys/Ridgeline/blob/d880143418ac46ece98adff0b3b2fbaedf044d46/public/terrain-visibility.js#L37-L55) · [Pinned engine](https://github.com/connorgleeson736-sys/Ridgeline/blob/d880143418ac46ece98adff0b3b2fbaedf044d46/public/assets/maplibre-gl.mjs) · [Residency reads touch eviction order](https://github.com/connorgleeson736-sys/Ridgeline/blob/d880143418ac46ece98adff0b3b2fbaedf044d46/public/raster-residency.js#L73-L84)

I replayed 96 changing Aoraki camera positions through the branch's Mercator traversal, visibility adapter and LOD functions. The viewport was 390 × 844 CSS pixels. Three passes represented imagery, the requested DEM cover and rendered terrain. The fixture used synthetic stable DEM extrema across eight zoom levels, a fixed set of available DEM tiles and a modelled focus point. It excludes source retention, network, GL and presentation. Counts are for the full replay. Times are medians of 12 warmed, interleaved rounds per implementation, divided by 96; they include camera setup and all three traversals.

| Variant | Bounding volumes built | DEM resolutions | ms per camera position |
| --- | ---: | ---: | ---: |
| 0.18.0 | 37,824 | 75,648 | 0.540 |
| Reuse within one camera frame | 17,564 | 35,128 | 0.411 |
| Reuse across frames with fixed DEM state | 289 | 578 | 0.195 |

All three returned the same 6,520 tile IDs in the same order, checked separately on every pass. The last variant saved about 64% of the time in this fixture. The absolute difference was 0.345 ms on the host. The result cannot be scaled directly to the historical phone selection time.

Resolve each source DEM once and reuse that result for both its extrema and margin. Cache the final conservative Mercator bound for the duration of an unchanged terrain revision. Key on projection, terrain/source identity, tile coordinates/wrap and relevant fallback inputs. Invalidate when the set or identity of eligible source tiles changes, when indexed extrema or exaggeration change, or when detail changes the relationship between a source tile and a rendered patch. In production, a cache hit must also respect intentional LRU touches. Start by ending reuse at every mutation boundary, even within a frame. Test new higher peaks, parent eviction, unloaded/newly arrived DEMs, map wrap, source/style resets and ordered tile IDs.

## 3. Release image delivery capacity while a bitmap decodes

The moving cap defaults to eight. The image scheduler increments its active count before byte delivery and decrements it after awaited decode finishes. Its separate decode cap only controls work inside that outer limit. The same slot can be occupied while the network is idle or while the bitmap waits to start decoding. [Shipped scheduler template](https://github.com/connorgleeson736-sys/Ridgeline/blob/d880143418ac46ece98adff0b3b2fbaedf044d46/scripts/maplibre-image-queue.js#L51-L76)

I ran the exact scheduler template with immediate controlled deliveries, a moving limit of eight, no ready-byte reserve and manually released decoders. With 24 submitted tiles, eight bodies had arrived, all eight decodes were held, and the remaining 16 deliveries had never started. Releasing one decoder immediately started the ninth transfer. This demonstrates a dependency in the scheduler itself. It does not measure a device decode duration.

Separate in-flight deliveries, buffered encoded bytes, and active decodes. Return the delivery token when validated bytes arrive, subject to a cap on both staged item count and total bytes, including data promised by existing transfers. Retain original request priority and cancellation through both queues, reprioritise queued decodes toward currently visible/focal tiles, and release decoded bitmaps once ownership ends. Preserve independent ownership for shared or transferable buffers. Tune the three limits independently under real device memory pressure. Measure time to the first sharp centre tile and to a fixed percentage of target-detail screen coverage. Increasing the existing outer cap allows more decode pressure as well.

## 4. Make adaptive mesh metadata available during motion

The existing implementation pauses profile generation while the map is moving, clears outstanding work at gesture start and chooses the full 128 grid for patches without a valid profile. A long pan into fresh terrain can therefore use the densest mesh precisely while smooth motion matters. Existing profiled patches can still simplify during a gesture. [Scheduling and fallback](https://github.com/connorgleeson736-sys/Ridgeline/blob/d880143418ac46ece98adff0b3b2fbaedf044d46/public/adaptive-terrain.js#L83-L105) · [Frame selection and movement hooks](https://github.com/connorgleeson736-sys/Ridgeline/blob/d880143418ac46ece98adff0b3b2fbaedf044d46/public/adaptive-terrain.js#L126-L172)

The branch's index generator produces 33,792 triangles with skirts at 128, 9,976 at 64 and 3,704 at 32. An eligible patch therefore removes 70% or 89% of submitted triangles per terrain pass. This says nothing about how many patches qualify on a steep face or which part of a phone frame dominates.

Use the current error-profile algorithm in a bounded worker job keyed to the DEM, its revision and the rendered patch's subregion. Schedule metadata for visible new patches first. Publish heights to the renderer immediately and let profiling finish later. Keep the existing screen-error, cell-span and hysteresis tests, native edges and skirts, and full-grid fallback. A worker may no longer own the source pixels after transfer. Avoid cloning every resident DEM just to calculate metadata and reject stale results after neighbour-border backfill. Compare per-frame grid counts, submitted triangles and GPU timings while replaying the same fresh ridge.

## Two cases worth checking against the user's actual settings

- **Aggressive coverage.** This opt-in mode scales each viewport dimension by up to three and supplies that expanded transform to both source managers and the rendered-terrain manager. It also requests a repaint every 33 ms while active. With the option enabled, off-screen preloading can therefore enlarge the terrain draw list and keep a stationary view rendering. Use an expanded set for request/retention and the real camera's cover for depth and colour submission. Wake again only when more preparation can progress. Preserve a warm ring for a pan back. [Implementation](https://github.com/connorgleeson736-sys/Ridgeline/blob/d880143418ac46ece98adff0b3b2fbaedf044d46/public/continuous-coverage.js#L5-L34)
- **Several open routes.** Most draped lines are grouped before symbols and circles. The Android passive-route line is appended without a before-layer ID. When visible after non-draped layers, it can create an additional terrain drape stack. The native stack flush draws the terrain patches again, and Direct's empty-overlay shortcut only applies to stack zero. Insert the passive line next to the active route in the existing line group, after checking label, active-route and waypoint ordering. Count terrain draws on the same camera with one and two open routes. [Android route layer](https://github.com/connorgleeson736-sys/Ridgeline/blob/d880143418ac46ece98adff0b3b2fbaedf044d46/capacitor/mobile/routes.js#L23-L30) · [Original overlay ordering](https://github.com/connorgleeson736-sys/Ridgeline/blob/d880143418ac46ece98adff0b3b2fbaedf044d46/public/topographic-overlays.js#L79-L87) · [Direct stack handling](https://github.com/connorgleeson736-sys/Ridgeline/blob/d880143418ac46ece98adff0b3b2fbaedf044d46/public/maplibre-direct-sampling.js#L90-L117)

## Device acceptance

Compare one change at a time on the same handset and WebView, with matching camera path, data pack, layer visibility, gesture speed, image resolution and terrain detail. Use a warm sweep for input-to-present and p95 frame interval. Use a pan into fresh terrain for centre-tile and target-screen-coverage latency. Match starting cache state for both sides of each comparison and repeat in alternating order. Record submitted GPU work separately from displayed frames. Check peak memory and sustained performance after several minutes, when device heat and memory reclaim can erase an initial gain.

## Reproduce

Use Node 22+ from the existing Ridgeline repository root checked out at the linked source commit. Extract the three scripts into a separate directory and run them from the repository root. They read the checkout and print to the terminal. No npm install, API key or APK build is required.

```sh
node /path/to/check-selection-cadence.mjs
node /path/to/check-bounds.mjs
node /path/to/check-image-queue.mjs
```
