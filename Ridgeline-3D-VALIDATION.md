# Capacitor preview 0.2.0 validation

Validated 26 October 2026. This is a development-signed preview, not a Play Store release.

## Passed

- Android debug and preview builds, targeting SDK 36 with minimum SDK 26.
- Android lint: no errors. Nonfatal dependency-update and intentional compatibility warnings remain.
- 10 JVM tests: bounded offline plans, ancestor zooms, DEM neighbours, imagery gap coverage, optional overlays, provider path/origin validation, coordinate validation and key encoding. No failures or skips.
- 5 web packaging tests: only Direct is selectable, no Cook reference assets or alternate renderer, requested Layers/Advanced layout, shared native tile routes, and preserved imagery routing.
- Chromium integration check with the actual WebGL renderer and controlled provider fixtures: terrain enabled; Direct supported/enabled and drawing terrain patches using source textures; route add/edit, elevation profile sampling, drawn offline rectangle, and Layers/Advanced interaction. No page or console errors in the successful run.
- Preview APK: ZIP CRC and APK Signature Scheme v2 verified. Asset inspection confirms Cook reference and alternate renderer bundles are absent.
- Removing old imagery versions and repackaging reduced the APK to approximately 25.1 MiB (26.3 MB).

## Device checks still pending

The software-emulated Android device did not answer its basic property queries reliably. Gradle could not establish a compatible API level, so the seven Android archive/worker tests **did not execute**. This is an environment failure, not a passing Android test result. Emulator troubleshooting was stopped at the user's request.

The test sources and instrumented APK are provided. They check persistent archive reads, restart/resume generations, deletion during an in-flight write, RGB elevation interpolation, bounded reads/settings and an actual worker fetch through WebView interception. Run them on a compatible disposable test device before treating those behaviours as device-verified.

Also test on a phone:

1. Update Android System WebView/Chrome; confirm 3D appearance, terrain rotation and gesture responsiveness.
2. Confirm live LINZ imagery/elevation authorization. The cloud environment's provider access has been blocked, so fixture tests do not verify current upstream availability or the owner's key.
3. Download a small region until complete, enable downloaded-only mode (which reloads and clears transient caches), and check the selected imagery, 3D terrain, profiles and optional Topo at multiple zooms. Repeat in airplane mode after restarting the app.
4. Check pause/resume, region removal and coverage gaps on real storage/network conditions.
5. Verify GPS permission/accuracy, native GPX import and Save dialogs, and route persistence after closing/reopening.

Browser fixtures use an authentic website image tile repeatedly with a controlled constant-height DEM. They demonstrate rendering and integration, not geographic correctness, visual parity with the live site, hardware GPS or a performance benchmark.
