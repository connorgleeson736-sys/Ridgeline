# Ridgeline 3D for Android

This Capacitor app packages the website's own patched MapLibre GL JS 6.14.0 renderer, UI, route editor, and elevation chart. **Original + Direct is the only renderer.** Terrain is a real elevation mesh rendered with WebGL inside Android WebView. This is a web renderer in a native Android shell, rather than MapLibre Native. The existing native app in `../android` remains a separate application.

The Cook reference imagery replacement is excluded. The website's general imagery shader, genuine LINZ photos, regional imagery boundaries, and available static overviews remain. Verified South Island overview packs supply the original precomputed imagery when imported; missing packs fall back to provider tiles. At offline survey seams, missing fine-survey veto masks fall back to the saved base imagery; exact pixel parity at those seams is not promised.

## Use

- Android 8.0+ with an up-to-date **Android System WebView/Chrome** and WebGL support. Update those through Google Play before testing. WebView performs rendering; no website server or sign-in is required.
- Open **Layers** for imagery, terrain detail, imagery detail, labels and shading. Less-used loading, cache, colour, sky, performance and API-key controls are under **Advanced**. Printed Topo maps use a dedicated native-resolution 2D policy, omit photo motion/detail reductions, and cap excessive raster enlargement. The GPS icon is an explicitly centred SVG.
- Open the kea menu and choose **＋ Create new** below Import GPX to sketch a route. Route and offline panels have a pill-shaped drag handle with compact and 64% screen-height positions; tap the handle or use arrow keys as an alternative to dragging. Compact routes show metrics and editing/export/undo/delete actions; larger positions also show the elevation profile. Saved regions and the offline download button live in the logo sidebar; region coordinates are inside Advanced. Offline selection uses a page-shaped frame: choose page size and orientation, move the map beneath it, then Save current view to open download settings. Use Open/Close beside saved routes to show or unload them. Several routes can remain visible; select their checkboxes in join order, optionally reverse a route, then choose Combine selected routes. The original saved routes remain intact. Use the GPS button for foreground location. GPS stops requesting positions in the background.
- Import GPX through Android's document picker; Export GPX uses Android's Save dialog. Limit: 2 MB and 2,000 points, one continuous track/route. GPX exports the route coordinates. Profiles are sampled from elevation tiles; imported GPX height values are not used.
- The website's public LINZ client key is the default, as approved by the owner. It is extractable from the APK and shares the website's provider quota. Override it in Advanced for the current session. Keys never appear in region-list responses or download logs.

## Bundled South Island imagery (0.4.0)

The full APK includes **all supplied precomputed South Island/Rakiura imagery at tile zooms 0–12**. Install the single full APK; no separate ZIP downloads, import steps or first-launch extraction are required. Zooms 0–8 contribute 67 verified files / 3,681,702 bytes. The remaining 5,277 images contribute 364,762,502 bytes, stored in six uncompressed content-addressed APK assets. Total precomputed image payload is 368,444,204 bytes.

The native renderer gateway reads individual WebP ranges directly from APK asset file descriptors, including each asset's nonzero ZIP offset. It allocates only the requested image bytes. Data is not copied into private storage at startup. Existing imported copies from 0.3.0 are ignored in favour of bundled assets; the offline panel offers to remove those redundant imported copies without touching the APK, routes or downloaded regions.

The imagery and catalogue retain the 0.3.0 verification chain: all 5,344 images are SHA256/format/dimension checked; the reconstructed authoritative index matches the pinned original hash. Original WebP pixels and raw/natural variants are unchanged. Separate Cook-reference corrections remain excluded. The native APK build verifies every binary pack before copying it and marks `.bin` files uncompressed so range reads work immediately.

These numbers are image-tile zooms; the viewer can request finer tiles than its camera zoom. The archive is imagery only. Download selected regions for elevation/3D terrain and profiles, and for detailed imagery beyond zoom 12. The existing offline region download limit remains zoom 17; online imagery sources extend through zoom 19 where data is available.

The old six-ZIP import workflow remains compatible for development/light builds, but is hidden in the full APK. Those data ZIPs can also supply inputs for reproducing a build. Extract their six `.bin` files into the ignored `capacitor/overview-data/` directory, or set `RIDGELINE_OVERVIEW_DATA` to a directory containing those files before `npm run sync`. Full bundling is the default and missing/incorrect data causes the build to fail. `RIDGELINE_BUNDLE_OVERVIEWS=0 npm run sync` builds the smaller base APK instead. In the base app, open Logo menu → Download offline region → Save current view → Included imagery → Import overview ZIPs and import all six prepared ZIPs. The base APK keeps the zoom 0–8 images; the ZIPs add zooms 9–12.

Recreate the binary packs from the original 15 exports with Python/Pillow:

```sh
python3 scripts/prepare-overviews.py --output /path/to/overview-data --catalog overview-catalog.json /path/to/Ridgeline_Imagery_*.zip
RIDGELINE_OVERVIEW_DATA=/path/to/overview-data npm run sync
```

Large input data remains outside the main source branch. The complete installer is published using Git LFS on the download branch, rather than splitting the installed app or asking users to import imagery.

## Offline regions

Choose **Logo menu → Download offline region**. Choose page size and orientation, pan/zoom the map underneath the fixed page frame, and optionally resize its corners. Tap Save current view to freeze the area and open download settings. Coordinates are under Advanced in that dialog. Regions are restricted to New Zealand's main-island map extent. Choose imagery/detail and optional NZ Topo plus mapped overlays, estimate size, then download.

- Saved regions include all ancestor zooms and neighbouring elevation tiles. LINZ aerial downloads also include satellite imagery for gap coverage. Elevation is included through zoom 16; imagery through the chosen zoom (13–17). New Topo raster downloads extend through zoom 18 and vector overlays through 14. Latin and Māori glyph ranges are included.
- Zooming closer offline enlarges saved imagery/elevation. Elevation interpolation respects the encoded height value, avoiding RGB channel rollover errors. This does not create additional survey detail.
- Android WorkManager runs a foreground download with a notification. Keep the app open until **complete**. Android may interrupt downloads; Pause/Resume preserves finished files and only retries missing ones. A file-count limit (12,000 per region), 30-region limit and storage floor bound downloads. Size estimates are rough, not guarantees.
- Downloaded files are in private persistent storage, separate from WebView's disposable cache. Removing one region does not remove another region's files. Version 0.13.0 uses indexed SQLite storage with shared identical payloads and separate region ownership; different source versions remain separate. Legacy tile files migrate in bounded, durable batches while remaining readable.
- **Use downloaded maps only** clears transient WebView/map caches and reloads the map so online cache hits cannot masquerade as downloaded coverage. Saved routes remain in the planner drawer. Camera position is retained. Outside coverage, detailed tiles remain unavailable; packaged regional overviews can still appear at distant zooms.
- Online mode normally requests missing tiles from providers. Offline mode and connection failures can use saved ancestor tiles. Route editing, bundled peak search, GPX and elevation profiles do not require a server inside downloaded coverage.
- Only GPS positioning is included: no background recording, trail routing, turn instructions or emergency navigation service.

## Build

Use Node 22+, JDK 21 and Android SDK 36. From `capacitor/`:

```sh
npm ci
npm run sync
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assemblePreview
```

`npm run sync` copies the original static website, applies checked integration changes, bundles the Capacitor bridge, and syncs Android assets. It never rewrites the original website files. Generated assets and npm dependencies are ignored by Git. Run sync before Gradle and **do not run them concurrently**.

In the prepared cloud workspace use `scripts/cloud-build.sh` instead of `./gradlew`; it activates the retained JDK/SDK/proxy-configured Gradle cache. No dev server is required on the phone. The Gradle 8.13 wrapper is pinned with the official SHA256. Debug and preview APKs use a development signing key; an owner-controlled release key is required for production distribution. The application ID is `nz.ridgeline.mobile`, so it can coexist with `nz.ridgeline.app`.

## Verification

```sh
npm test
node scripts/browser-check.mjs  # installed Chromium, or CHROMIUM_PATH
scripts/cloud-build.sh :app:testDebugUnitTest :app:lintDebug
scripts/cloud-build.sh :app:connectedDebugAndroidTest
```

The browser check uses a real WebGL engine and controlled imagery/elevation fixtures. It checks the direct draw path, 3D terrain, touch-sized Layers/Advanced controls, route editing, elevation sampling and rectangle selection. Fixture screenshots are diagnostics, not evidence of live provider access or a pixel comparison against the deployed website.

Android tests cover archive reads, cancellation generations, deletion during a request, encoded-elevation resampling, bounded input and an actual dedicated-worker tile fetch through Android WebView interception. Unit tests cover bounded region enumeration and provider URL validation. Live provider authentication, full downloads in airplane mode, GPS hardware, gestures and GPU performance require physical-device testing. This cloud's network policy has blocked map providers; passing fixture tests does not establish that a live LINZ key or endpoint is currently working.

See [third-party notices](THIRD_PARTY_NOTICES.md) before distributing.

## 0.7.0 refinements and Topo PDF export

Sheet headers are 24 px high, with smaller compact mode and rounded corners. During sheet movement the map renders into a fixed-size viewport and is clipped, then resizes once at the snap point. Rectangle editing switches to north-up 2D and uses a lightweight SVG outline and smaller corner visuals; dragging no longer rebuilds a GeoJSON worker source. Peak search has been removed. Topographic overlay controls are on the main Layers tab, while peak labels and hillshade are in Advanced. Closing a route unloads its geometry as well as its panel.

For printable offline maps, choose **Logo menu → Download offline region → Map content → NZ Topo only · maximum detail**. This downloads the current raster Topo source through native zoom 16 without aerial imagery or elevation. Including Topo in a mixed download also requests its zoom 16 tiles independently of the chosen aerial detail. Completed regions containing those tiles offer **Export Topo PDF** under Saved regions. Android's Save dialog chooses the PDF destination.

PDFs are one continuous, north-up map page, cropped to the selected bounds with LINZ attribution. All native 256 px image tiles are embedded losslessly. Page dimensions grow with the selected region at a nominal 192 dpi print density; PDF 1.6 UserUnit supports very long pages without downsampling the imagery. PDF generation streams one decoded tile at a time and requires no network. It uses saved raster cartography, without the route overlays. Older saved regions may lack zoom 16 Topo; the exporter explains that a new maximum-detail download is needed. The existing 12,000-file download limit still applies.

Size estimates stratify resources by source and zoom, use saved tile byte sizes when available, and sample up to 24 new tiles with four workers and a 20-second sampling deadline. The displayed point estimate and storage allowance are planning figures, not guaranteed file sizes. Estimates are cached for one minute; without usable samples they are explicitly labelled planning estimates. Overlapping region downloads reuse saved tiles.

## 0.8.0 saved routes and viewport frame

Saved-route cards include distance/waypoint summaries, north-up route-shape thumbnails with start/end markers, and inline Rename/Save/Cancel controls. Names are persisted to the same route store and active routes keep their identity. Opening additional routes fits the map to all open routes. Tapping the uncovered map area dismisses the sidebar without editing route points.

The grip area is slightly taller (30 px). A 24 px map underlap and clipped sheet contents let the actual map show through rounded top corners instead of revealing a square paper background. Peak labels start off, with explicit changes saved on the device.

Draw rectangle now creates a viewport-anchored frame immediately and keeps the download sheet open. Pan and zoom the map beneath the frame to choose coverage. Drag its corners to resize the frame. The geographic bounds update from the frame on every map movement. Changing the sheet height resizes the frame with the visible map area. Saved-region and Advanced-coordinate selections can still set exact geographic bounds.

## 0.9.0 interaction and route-list refinements

Route points can only be dragged or inserted while Edit is enabled. Layers is slightly narrower, and tapping the map outside closes it without editing the route. The mapped-feature warning and overlay-status prose are hidden in Layers.

Both sheets now have only compact and expanded stops. During dragging, the sheet uses a transform and the fixed map viewport uses clipping; map resizing and elevation chart rendering wait until the sheet settles. Region frames retain their pixel geometry through the gesture and refresh afterwards.

Saved route cards are keyed by route identity. Unchanged summaries and thumbnails are reused, renaming preserves geometry, hidden lists defer card updates until opened, and passive map routes only receive changed geometry. Persistence format, route limits, map detail and renderer are unchanged.

## 0.10.0 page selection and printable Topo maps

Download offline region now starts with Page size (A5, A4, A3, Letter or Legal), Orientation, and Save current view. Pan or zoom beneath the page frame, or resize its corners while keeping the printable page aspect. Saving freezes geographic bounds and opens a large settings dialog for name, content, detail, size estimate and download. Change area returns to the selection step. Saved-region Open and Export Topo PDF actions have separate spacing.

PDF export offers a chosen paper size or the original full-detail custom page, portrait/landscape, an optional NZTM2000 (EPSG:2193) coordinate grid with edge references, true north, magnetic north and a scale bar. Raster tiles remain embedded losslessly at their downloaded resolution on one page. Standard paper fits the entire saved region inside the page margins; the print scale depends on the selected area. The grid is projected onto the Web Mercator map; the map itself is not reprojected into NZTM. Scale is approximate at the map centre. Magnetic north uses Android's offline model at the region centre and the export date, or the user's declination override, and is labelled accordingly.

LINZ API peak names now default on with a separate persisted preference. The legacy bundled summit markers are disabled in the app. The Layers switch controls LINZ peaks independently of the general place/water name switch.

The Topo display requests additional source detail according to screen density, through zoom 18, while retaining the camera zoom-16 limit and nearest sampling. New Topo downloads include zoom 18; this can increase tile count and storage compared with earlier downloads. The 12,000-file limit remains. Existing zoom-16 regions still export at their original detail; re-download for finer offline tiles. Live LINZ sample requests at zooms 16, 17 and 18 returned valid 256 px WebP images during this update.

Preview APKs are provided in Base and Full variants. Full includes the same verified 364,762,502-byte extra overview pack; Base supports the existing ZIP import workflow. Neither build adds elevation to that imagery pack. No emulator validation is required.

## 0.11.0 website Direct rendering policy

Original + Direct remains enabled, as requested. The rendering engine, Direct sampling module, terrain adaptation, motion-detail policy and surface focus are copied unchanged from the website source. The shared performance policy matches the website, with the explicit exception of the retained high-density Topo enhancement: zoom-18 source tiles and 128 px source sizing on dense screens. New offline Topo downloads and PDF export still retain zoom-18 detail from 0.10.0.

A one-time migration resets saved rendering/detail/loading/filtering/sky preferences to website defaults. Routes, offline regions, imported imagery, GPS settings and the LINZ label preference are preserved. Subsequent rendering adjustments are saved normally. Offline access and native tile interception remain in place, so network behaviour and graphics-driver differences can still differ from a browser. The live website could not be fetched from this environment; code parity is verified against the repository website source. The previously excluded Cook reference replacement stays excluded. Both Base and Full previews contain the same Direct renderer code.

## 0.12.0 loading and caching

Native map requests now use a shared OkHttp connection pool, HTTP/2 where supported, a removable 256 MB HTTP disk cache, and shared in-flight map/download transfers. Provider freshness/revalidation rules are respected, and completed estimation samples can be reused. Saved-region tile locations/misses are memoised; file bodies and overview-pack reads no longer hold a global read lock. Status, settings and Pause use a separate queue from long imports/exports/estimates. Temporary enlarged ancestor tiles are non-cacheable.

Original + Direct, imagery bytes, terrain settings, the zoom-18 high-density Topo policy and lossless PDF export remain unchanged. Downloaded-only mode bypasses the online cache; saved regions retain their persistent storage. No phone speed percentage is claimed. See [the research, decisions and verification details](LOADING-OPTIMISATIONS.md).

## 0.13.0 native scheduling, streaming and indexed storage

The renderer's existing cancellation/priority signals now reach Android, including worker requests and foreground promotion of shared source inputs. Native transfers use per-consumer cancellation, foreground capacity and bounded background admission. Online bodies and trusted overview ranges stream earlier; validation still gates successful completion. The imagery worker reuses exact decoded inputs within a 2 MiB retained pool, and obsolete predictive warmups release their demand.

Offline regions use a shared content-addressed SQLite store with indexed coordinates and per-region ownership. Different source versions remain byte-exact. Existing files migrate in small committed/checkpointed batches; failures retain readable originals. Three bounded download producers share native admission and commit batches, with retry/backoff and generation checks. Once migrated, saved maps require 0.13.0 or later.

Original + Direct, highest implemented Topo detail, imagery pixels and PDF detail remain unchanged. See [implementation, safeguards, research and validation](LOADING-OPTIMISATIONS.md). Run `node scripts/transport-browser-check.mjs` for real-worker control and pixel-equivalence checks; `await RidgeMobile.loadingStats()` supplies native counters during manual phone profiling.

## 0.13.1 foreground loading

Removes the extra eight-transfer native cap. Foreground requests, including fresh disk-cache hits, no longer wait for unrelated network transfers. Existing renderer loading settings remain effective, while background transfers retain their two-transfer limit. Rendering, maximum Topo detail and the 256 MB online cache are unchanged. Only the Base preview APK is published for this update.
