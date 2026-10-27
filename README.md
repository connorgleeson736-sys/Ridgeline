> Startup viewer: **Original MapLibre**. Three.js GPU terrain remains an optional renderer. See [BASE_VIEWER_STATUS.md](BASE_VIEWER_STATUS.md) and [THREE_VIEWER_STATUS.md](THREE_VIEWER_STATUS.md).

# Ridgeline

The active website is the main New Zealand map and route planner. On 2026-10-15
the user retired all custom terrain meshes, the Alpine Model, comparison pages
and the LiDAR pilot. Do not restore those viewers or regenerate their assets as
part of ordinary maintenance.

Read [BASE_VIEWER_STATUS.md](BASE_VIEWER_STATUS.md) for the current architecture,
validation, publishing and recovery instructions. Earlier terrain-processing
documents are historical records. Processing checkpoints remain recoverable.

## Native Android app

The Kotlin / Jetpack Compose app is in [`android/`](android/README.md). It uses one
MapLibre Native imagery/hillshade renderer, with route planning, GPX, foreground
GPS, elevation profiles, and explicit offline region downloads. See its README
for building, installing, provider setup, and validation limitations.

## Capacitor Android app with original 3D terrain

The current 3D mobile version is in [`capacitor/`](capacitor/README.md). It packages
the website's **Original + Direct** renderer and interface, omits Cook reference
imagery changes, and adds persistent Android offline regions, foreground GPS and
native GPX file dialogs. Layers keeps terrain and imagery detail accessible, with
other tuning under Advanced. It installs as **Ridgeline 3D**, alongside the earlier
native app. See [validation results](capacitor/VALIDATION.md) before field use.

Version 0.3.0 adds verified South Island overview data packs (six ZIP imports), a
centred GPS SVG and a dedicated sharpness policy for printed Topo maps. The website
source shares the Topo fix; deployment to the original live Site is separate.

Version 0.4.0 bundles the complete verified zoom 0–12 overview imagery in one
full APK, with native range reads directly from uncompressed APK assets. No
separate imagery imports or first-launch extraction are needed.

Version 0.6.0 adds draggable bottom sheets with compact route metrics, larger elevation profiles, saved regions in the logo sidebar and resizable region rectangles. The smaller base APK supports optional import of the six prepared overview ZIPs.
