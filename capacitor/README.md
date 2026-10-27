# Ridgeline 3D for Android

This Capacitor app packages the website's own patched MapLibre GL JS 6.14.0 renderer, UI, route editor, and elevation chart. **Original + Direct is the only renderer.** Terrain is a real elevation mesh rendered with WebGL inside Android WebView. This is a web renderer in a native Android shell, rather than MapLibre Native. The existing native app in `../android` remains a separate application.

The Cook reference imagery replacement is excluded. The website's general imagery shader, genuine LINZ photos, regional imagery boundaries, and available static overviews remain. Verified South Island overview packs supply the original precomputed imagery when imported; missing packs fall back to provider tiles. At offline survey seams, missing fine-survey veto masks fall back to the saved base imagery; exact pixel parity at those seams is not promised.

## Use

- Android 8.0+ with an up-to-date **Android System WebView/Chrome** and WebGL support. Update those through Google Play before testing. WebView performs rendering; no website server or sign-in is required.
- Open **Layers** for imagery, terrain detail, imagery detail, labels and shading. Less-used loading, cache, colour, sky, performance and API-key controls are under **Advanced**. Printed Topo maps use a dedicated native-resolution 2D policy, omit photo motion/detail reductions, and cap excessive raster enlargement. The GPS icon is an explicitly centred SVG.
- Open the kea menu and choose **＋ Create new** below Import GPX to sketch a route. Route and offline panels have a pill-shaped drag handle with compact, 40% and 64% screen-height positions; tap the handle or use arrow keys as an alternative to dragging. Compact routes show metrics and editing/export/undo/delete actions; larger positions also show the elevation profile. Saved regions and the offline download button live in the logo sidebar; region coordinates are inside Advanced. Rectangle selection previews the area after the first corner and can be cancelled without losing the previous bounds. Use Open/Close beside saved routes to show or unload them. Several routes can remain visible; select their checkboxes in join order, optionally reverse a route, then choose Combine selected routes. The original saved routes remain intact. Use the GPS button for foreground location. GPS stops requesting positions in the background.
- Import GPX through Android's document picker; Export GPX uses Android's Save dialog. Limit: 2 MB and 2,000 points, one continuous track/route. GPX exports the route coordinates. Profiles are sampled from elevation tiles; imported GPX height values are not used.
- The website's public LINZ client key is the default, as approved by the owner. It is extractable from the APK and shares the website's provider quota. Override it in Advanced for the current session. Keys never appear in region-list responses or download logs.

## Bundled South Island imagery (0.4.0)

The full APK includes **all supplied precomputed South Island/Rakiura imagery at tile zooms 0–12**. Install the single full APK; no separate ZIP downloads, import steps or first-launch extraction are required. Zooms 0–8 contribute 67 verified files / 3,681,702 bytes. The remaining 5,277 images contribute 364,762,502 bytes, stored in six uncompressed content-addressed APK assets. Total precomputed image payload is 368,444,204 bytes.

The native renderer gateway reads individual WebP ranges directly from APK asset file descriptors, including each asset's nonzero ZIP offset. It allocates only the requested image bytes. Data is not copied into private storage at startup. Existing imported copies from 0.3.0 are ignored in favour of bundled assets; the offline panel offers to remove those redundant imported copies without touching the APK, routes or downloaded regions.

The imagery and catalogue retain the 0.3.0 verification chain: all 5,344 images are SHA256/format/dimension checked; the reconstructed authoritative index matches the pinned original hash. Original WebP pixels and raw/natural variants are unchanged. Separate Cook-reference corrections remain excluded. The native APK build verifies every binary pack before copying it and marks `.bin` files uncompressed so range reads work immediately.

These numbers are image-tile zooms; the viewer can request finer tiles than its camera zoom. The archive is imagery only. Download selected regions for elevation/3D terrain and profiles, and for detailed imagery beyond zoom 12. The existing offline region download limit remains zoom 17; online imagery sources extend through zoom 19 where data is available.

The old six-ZIP import workflow remains compatible for development/light builds, but is hidden in the full APK. Those data ZIPs can also supply inputs for reproducing a build. Extract their six `.bin` files into the ignored `capacitor/overview-data/` directory, or set `RIDGELINE_OVERVIEW_DATA` to a directory containing those files before `npm run sync`. Full bundling is the default and missing/incorrect data causes the build to fail. `RIDGELINE_BUNDLE_OVERVIEWS=0 npm run sync` builds the smaller base APK instead. In the base app, open Logo menu → Download offline region → Included imagery → Import overview ZIPs and import all six prepared ZIPs. The base APK keeps the zoom 0–8 images; the ZIPs add zooms 9–12.

Recreate the binary packs from the original 15 exports with Python/Pillow:

```sh
python3 scripts/prepare-overviews.py --output /path/to/overview-data --catalog overview-catalog.json /path/to/Ridgeline_Imagery_*.zip
RIDGELINE_OVERVIEW_DATA=/path/to/overview-data npm run sync
```

Large input data remains outside the main source branch. The complete installer is published using Git LFS on the download branch, rather than splitting the installed app or asking users to import imagery.

## Offline regions

Choose **Logo menu → Download offline region**. Draw a rectangle using two map taps, drag any of its four corners, type bounds under Advanced, use the current view, or select an area around your route. Rectangles are restricted to New Zealand's main-island map extent. Choose imagery/detail and optional NZ Topo plus mapped overlays, estimate size, then download.

- Saved regions include all ancestor zooms and neighbouring elevation tiles. LINZ aerial downloads also include satellite imagery for gap coverage. Elevation is included through zoom 16; imagery through the chosen zoom (13–17). Topo raster stops at 16 and vector overlays at 14. Latin and Māori glyph ranges are included.
- Zooming closer offline enlarges saved imagery/elevation. Elevation interpolation respects the encoded height value, avoiding RGB channel rollover errors. This does not create additional survey detail.
- Android WorkManager runs a foreground download with a notification. Keep the app open until **complete**. Android may interrupt downloads; Pause/Resume preserves finished files and only retries missing ones. A file-count limit (12,000 per region), 30-region limit and storage floor bound downloads. Size estimates are rough, not guarantees.
- Downloaded files are in private persistent storage, separate from WebView's disposable cache. Removing one region does not remove another region's files. Overlapping regions currently store separate copies.
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

PDFs are north-up, A4 landscape atlases, eight by five native 256 px tiles per page (roughly 188 dpi), cropped to the selected bounds with LINZ attribution and page numbers. Map pixels are embedded losslessly; large regions span multiple pages rather than shrinking all detail onto one page. PDF generation streams one decoded tile at a time and requires no network. It uses saved raster cartography, without the route overlays. Older saved regions may lack zoom 16 Topo; the exporter explains that a new maximum-detail download is needed. The existing 12,000-file download limit and a 320-page PDF bound apply.

Size estimates stratify resources by source and zoom, use saved tile byte sizes when available, and sample up to 24 new tiles with four workers and a 20-second sampling deadline. The displayed point estimate and storage allowance are planning figures, not guaranteed file sizes. Estimates are cached for one minute; without usable samples they are explicitly labelled planning estimates. Overlapping region downloads reuse saved tiles.
