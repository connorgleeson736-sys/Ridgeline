# Ridgeline 3D for Android

This Capacitor app packages the website's own patched MapLibre GL JS 6.14.0 renderer, UI, route editor, and elevation chart. **Original + Direct is the only renderer.** Terrain is a real elevation mesh rendered with WebGL inside Android WebView. This is a web renderer in a native Android shell, rather than MapLibre Native. The existing native app in `../android` remains a separate application.

The Cook reference imagery replacement is excluded. The website's general imagery shader, genuine LINZ photos, regional imagery boundaries, and available static overviews remain. Verified South Island overview packs supply the original precomputed imagery when imported; missing packs fall back to provider tiles. At offline survey seams, missing fine-survey veto masks fall back to the saved base imagery; exact pixel parity at those seams is not promised.

## Use

- Android 8.0+ with an up-to-date **Android System WebView/Chrome** and WebGL support. Update those through Google Play before testing. WebView performs rendering; no website server or sign-in is required.
- Open **Layers** for imagery, terrain detail, imagery detail, labels and shading. Less-used loading, cache, colour, sky, performance and API-key controls are under **Advanced**. Printed Topo maps use a dedicated native-resolution 2D policy, omit photo motion/detail reductions, and cap excessive raster enlargement. The GPS icon is an explicitly centred SVG.
- Use the route button to sketch/edit, the kea button to reopen saved routes or find a peak, and the GPS button for foreground location. GPS stops requesting positions in the background.
- Import GPX through Android's document picker; Export GPX uses Android's Save dialog. Limit: 2 MB and 2,000 points, one continuous track/route. GPX exports the route coordinates. Profiles are sampled from elevation tiles; imported GPX height values are not used.
- The website's public LINZ client key is the default, as approved by the owner. It is extractable from the APK and shares the website's provider quota. Override it in Advanced for the current session. Keys never appear in region-list responses or download logs.

## South Island overview pack (0.3.0)

Zooms 0–8 are already bundled: 67 images / 3,681,702 bytes. All 67 have been byte-matched against the uploaded imagery export and the previous 0.2.0 APK. These are the supplied downsampled overviews, not substituted 10 m LINZ images. These numbers describe image-tile zooms: the viewer can request a finer tile than its camera zoom, so a missing zoom 9–12 tile in the old app could still fall back to ordinary LINZ imagery.

For zooms 9–12, download **all six prepared `Ridgeline-Overviews-0.3.0-*-of-6.zip` files** from the GitHub download branch's `overview-packs/` folder. In the app choose **Layers → Download offline region → Import overview ZIPs**. Android's picker supports multiple selection; imports can also be done one part at a time. Wait for **Ready for offline use · 6/6 packs**. Import the six prepared files rather than the 15 original source exports.

The additional data is exactly 364,762,502 bytes (364.8 MB). Allow about 750 MB for both ZIP downloads and imported copies temporarily; the ZIPs can be removed from Downloads after import is complete. Imported packs persist across app restarts/updates. Removal affects only overview imagery, not routes or region downloads.

The preparation script verifies all 5,344 original overview images, reconstructs the original authoritative index and matches its pinned SHA256, then losslessly packs the 5,277 missing images. Every resulting range has been independently compared with the original image hash. Six content-addressed files replace thousands of individual files; reads use native file ranges, and SHA256 verification streams through a 32 KB buffer. The app includes only the catalogue and rejects wrong, truncated, oversized or modified packs. No Cook-reference assets or intermediates are imported.

This pack covers South Island, Rakiura and nearby islands, and contains **imagery only**. Download selected regions for elevation needed by offline 3D terrain and profiles. Outside the saved coverage normal online providers can still be used.

Reproduce the data pack from the 15 user-provided imagery exports with Python/Pillow:

```sh
python3 scripts/prepare-overviews.py --output /path/to/output --catalog overview-catalog.json /path/to/Ridgeline_Imagery_*.zip
```

Large data ZIPs remain on the download branch, separately from application source and the APK. The website itself continues using its existing hosted archive; the native import mechanism is for the Capacitor app.

## Offline regions

Choose **Layers → Download offline region**. Draw a rectangle using two map taps, type bounds, use the current view, or select an area around your route. Rectangles are restricted to New Zealand's main-island map extent. Choose imagery/detail and optional NZ Topo plus mapped overlays, estimate size, then download.

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
