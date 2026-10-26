# Ridgeline for Android

A native Kotlin / Jetpack Compose app using **MapLibre Native 13.7.1**. No WebView or JavaScript runtime. The existing website is unchanged. One renderer draws satellite or topographic raster tiles, optional hillshade, routes, selected offline regions and GPS. It deliberately does not port the website's custom terrain engines, Mt Cook imagery patches or colour grading.

## Build and install

Requires JDK 17 or 21 and Android SDK platform 36 (Android Studio can install it). Minimum device: Android 8.0 / API 26, OpenGL ES 3.0. Open this `android` directory in Android Studio, or:

```sh
# Set ANDROID_HOME to your Android SDK, or set sdk.dir in an ignored local.properties.
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
# With an emulator/device connected:
./gradlew :app:connectedDebugAndroidTest
```

The Gradle wrapper version and distribution SHA-256 are pinned. Dependency versions are pinned in `app/build.gradle.kts`. ABI-specific APKs are also generated; `arm64-v8a` is suitable for most current Android phones and avoids bundling other processors' native libraries. The `preview` variant uses release dependencies without code shrinking and is signed with the local debug key for sideload testing (`./gradlew :app:assemblePreview`); it is not a Play Store release. Release builds enable R8 shrinking; use your own signing configuration for a distributable release. Do not commit keystores, keys, local.properties or SDK paths. Debug signing is for testing only.

## Using the app

- **New route** enters drawing mode. Tap to add waypoints; tap near a segment to insert; tap a point to select it, then long press the map to move it or delete it with the trash button. Undo restores previous geometry. Done ends editing. Routes save atomically on the device after every edit.
- Tap a route's title to rename it. **Routes** opens the saved list and GPX import. Export with the share/export button or route details. Android's document picker chooses the source/destination; no broad storage permission is required. GPX track segments import separately, never as invented connecting lines. Imports support UTF-8 GPX tracks/routes, up to 4 MiB / 2,000 points. Original imported altitude is not retained; ground elevations are recomputed.
- **Search** browses the bundled summit directory. **My location** requests foreground precise/approximate permission. Location updates stop when the activity pauses. This is not background route recording or turn-by-turn navigation.
- **Settings → LINZ API key** enables LINZ imagery, topo, elevation and offline downloads. The website’s existing public client key is bundled by explicit owner request, so first launch uses LINZ. It shares the website’s provider access/quota and is extractable from the APK. You can replace it on-device; overrides are stored in app-private preferences with backup disabled. Clearing the field disables LINZ. Without a key, the app uses online Esri imagery/topo and Mapzen hillshade, with no elevation profile or explicit downloads.
- **Offline → Draw region** takes two opposite map-corner taps to define a rectangle; **Enter bounds** accepts west/east longitude and south/north latitude. Regions are limited to New Zealand. Review the name, zoom and estimated size before downloading. Satellite, topo and DEM tiles (including low-zoom parents and a DEM halo) are saved. The limit is 8,000 tiles per region. Estimates are approximate; actual space can differ.
- WorkManager downloads with three concurrent requests and a visible progress notification. Allow notifications if you want progress outside the app. Downloads survive process death and wait for connectivity. **Pause downloads** cancels the queue; **Resume** skips files already saved. Do not assume incomplete downloads are usable at all zooms. Delete is disabled while work is active; shared tiles belonging to retained regions are preserved.
- Downloaded tiles live in internal persistent storage, not the disposable HTTP cache. Both the native map's OkHttp client and elevation profiler consult the same saved files. Downloads have finite maximum zoom; closer views reuse available parents. Tiles outside saved coverage need a connection. Removing a region prunes its unshared tiles. Uninstalling removes all local data.
- Elevation profiles use LINZ Terrain RGB, pixel-centre aligned bilinear interpolation, bounded decoded-tile memory, debounced/cancellable sampling, and the website's 5 m ascent/descent hysteresis. Missing measurements stay gaps; incomplete ascent/descent is shown as a lower bound. Elevations are estimates and are not a substitute for field navigation.

## Architecture and scope

`model/`: validated coordinates/routes, geodesic measurements, GPX, deterministic tile enumeration. `data/`: atomically replaced route/region JSON, provider/cache access, bounded elevation decoding, resumable workers. `map/`: MapLibre lifecycle, style and GeoJSON overlays. `ui/`: Compose planner, elevation chart, offline controls and foreground GPS.

The map uses native raster rendering and GPU hillshade, not the website's draped 3D terrain. Android MapLibre's exposed API does not provide equivalent terrain. This choice was explicitly selected for this version. No claim is made that hillshade reproduces 3D mountain silhouettes. The app has straight-segment route planning, not trail snapping or turn-by-turn guidance. No cloud account/sync, background tracking or Play Store publishing is included.

Performance controls: optional 30 fps mode, small shared HTTP cache (64 MiB), decoded elevation LRU (8 MiB), cancellation of obsolete elevation jobs, bounded downloads and route point limits. Downloaded regions are not evicted automatically. MapLibre manages its own GPU/native resource lifecycle.

## Providers, licensing and network

Build downloads use Google Maven, Maven Central and Gradle distribution servers. Runtime requests use `basemaps.linz.govt.nz`, `services.arcgisonline.com`, and `s3.amazonaws.com`. Provider attribution appears on the map and in Settings. Explicit offline downloads use LINZ only; Esri fallback is online only. Users must comply with LINZ provider download terms/quotas; the bundled website key may be replaced with their own. No key is needed to compile.

Peak directory: adapted from NZAC / ClimbNZ and Aoraki Tai Poutini (Rob Frost, 2018), **CC BY-NC-SA 3.0 NZ**, inherited from `public/alpine-data.js`. Its noncommercial/share-alike conditions apply to that dataset; do not assume it is cleared for a commercial release. LINZ imagery/elevation/topo: CC BY 4.0 plus contributor attribution. MapLibre Native: BSD. Third-party service terms still apply.

## Cloud development

Use the existing checkout; cloud tasks are already isolated. Do not create worktrees unless requested. In a restricted cloud runtime, Java/Gradle need the provided proxy and CA trust; never disable TLS checks. Put machine-specific proxy/trust settings in the Gradle user home, not this repository. Configure Android user/SDK paths in writable directories. Processes such as emulators must be restarted in each task; they are not retained with a filesystem snapshot.
