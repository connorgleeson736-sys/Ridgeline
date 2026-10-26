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
