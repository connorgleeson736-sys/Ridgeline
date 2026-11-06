# Native full latency audit — 6 November 2026

Baseline: native 0.1.12 Additional Latency Audit, source `6052065`, published at
`e4ba496` on `native-renderer-performance`. The user reports the native app is
slower than the Capacitor app. This pass looked for causes that apply to every
session, rather than tuning one subsystem. It was checked against the actual
build configuration, the upload policy and the image pipeline.

There is still no matched same-phone benchmark (see
[NATIVE-VS-CAPACITOR-2026-11-01.md](NATIVE-VS-CAPACITOR-2026-11-01.md)), and no
device or emulator was available here (no KVM). Every number below says where it
came from. No phone speedup is measured or claimed.

## Findings and changes

| # | Finding | Change | Evidence |
| --- | --- | --- | --- |
| 1 | **Every native checkpoint is debuggable; every Capacitor APK is not** | New non-debuggable `performance` build type; the build script now produces it by default | 0.1.12 `badging.txt` contains `application-debuggable`; Capacitor 0.20.3/0.21.0 badging does not |
| 2 | **Still-camera refinement could fall to ~4 tiles/s** | While still, uploads keep the 4 ms idle allowance and the first upload of each frame always proceeds | 120 Hz replay: ~4 → 240 refinement uploads/s |
| 3 | Every imagery tile passes through a 256 KiB int array and a per-pixel Kotlin loop | Opaque sRGB bitmaps are copied directly into the upload payload | Same bytes by Android's documented ARGB_8888 layout; other bitmaps unchanged |
| 4 | Survey seam masks download full PNGs, but only their alpha is used | Request WebP and fall back to the original PNG | Alpha identical on 1,064 live tiles across all 46 surveys; 89.3% fewer bytes |

### 1. Debuggable runtime

`assembleDebug` produced every published native APK. A debuggable Android app
runs with the ART runtime in debuggable mode and with CheckJNI enabled by the
system. Its own code gets neither profile-guided ahead-of-time compilation nor
the full optimising JIT. The native app's tile pipeline is Kotlin:

- scheduling and priority queues;
- transport and cache bookkeeping;
- PNG/WebP post-processing;
- DEM conversion;
- composition;
- gesture/camera code and every per-frame JNI call.

All of it ran under those restrictions, while the Capacitor app it was compared
with was built non-debuggable.

The `performance` build type is `initWith(release)` with `isDebuggable=false`.
R8 shrinking stays **off**, so the 43 JNI entry points, reflection and resource
names are exactly as before. It uses the same sources, assets and Rust libraries
as debug. The debug-only test activities and session-CA network configuration
live in the `debug` source set, so they are no longer shipped. Tests and lint
still run on debug.

The size of this effect cannot be measured without a phone. It applies to every
Kotlin path, not to the Rust renderer, which was already an optimised build.

### 2. Upload admission while the camera is still

`TerrainView.drawFrame` admitted uploads only within
`frame interval − render cost − callback lateness − 2 ms`, capped at 2 ms during
gestures and 4 ms otherwise. On a 90/120 Hz display, or with a heavy frame, that
slack is often zero; the existing test asserted exactly that for 120 Hz.

A non-urgent tile then waited for the 250 ms "starved idle" rule. The wait
restarted for every tile, because `deferredTileSince` reset after each upload.
Meanwhile the loop kept rendering full frames at display rate without uploading.
Refinement of an already-covered view could therefore be limited to about four
tiles per second, while Capacitor/MapLibre uploads textures as they arrive.

A still frame presents only refinement; there is no motion for an upload to
disturb. With no camera movement and no gesture:

- `UploadAdmission.allowance(..., stationary=true)` returns the idle allowance (4 ms);
- the first upload of a frame always proceeds, even a known over-budget tile.

These are unchanged:
- gesture and movement frames (2 ms gesture cap, camera-first 64 ms deferral, urgent-coverage exception);
- the 4-uploads and 650 KiB per-frame limits;
- the GPU backlog gate and typed upload acknowledgements.

The overlay-layer batch keeps its own 250 ms rule; it is not imagery.

`UploadAdmissionTest.stationaryRefinementNoLongerWaitsOnStarvationTimer`
replays 240 queued uploads at 120 Hz with a 7 ms render, 1 ms lateness and
1.2 ms per upload:

| Rule | Throughput |
| --- | --- |
| Old | ~4 tiles/s |
| New | 240 tiles/s, 2 per frame (limited by the 650 KiB frame cap) |

This is a deterministic policy replay, not a phone measurement. The real gain
depends on the device's render time, refresh rate and network supply.

### 3. Opaque imagery fast path

`TileLoader.loadImage` previously did the following for every tile:

1. Decode a `Bitmap`.
2. Copy it into a new 65,536-int array with `getPixels`.
3. Premultiply and swizzle every pixel in Kotlin (`PixelPacking.rgbaOwned`).
4. Copy the result into a direct buffer.

Android stores ARGB_8888 pixels as `R | G<<8 | B<<16 | A<<24`, which is the
renderer's premultiplied RGBA byte order. For an opaque bitmap, alpha is 255
and premultiplication is the identity.

`NativeImagery.loadRgba` therefore copies with one `copyPixelsToBuffer`
when the bitmap meets all of these conditions:
- `!hasAlpha()`;
- ARGB_8888;
- 256×256 with a 1,024-byte row stride;
- sRGB, because `getPixels` would convert other colour spaces.

Any other bitmap, including boundary compositions and fresh crop canvases, uses
the original path. LINZ aerial WebP, Esri JPEG and Topo tiles are normally opaque.

This removes a 256 KiB allocation and about 65k Kotlin iterations per tile from
the two image workers. `load()` keeps its old signature for offline downloads.
JVM tests cannot run Android's `Bitmap`; on-device byte equality has not been
executed.

### 4. WebP survey masks

Seam vetoes (`vetoFine`) use only `alpha > 0` of the survey tiles. LINZ was
queried live for 1,064 tiles covering all 46 catalogue surveys, from zoom 13 to
each survey's maximum:

- alpha was identical in every tile;
- every HTTP status matched;
- 37,797,239 PNG bytes against 4,028,922 WebP bytes, 89.3% less.

Evidence: `validation/full-audit-2026-11-06/survey-mask-qualification.json`.

`SurveyMasks` requests WebP and falls back to the original PNG address on a
provider/IO failure. This keeps regions saved before this change working
offline, since they hold PNG masks. Pending work (`ResourcePending`),
cancellation (`PreparationAborted`, `InterruptedIOException`) and aborted
preparation never start a fallback. Prefetch (`inputs`) names the WebP address.
Photos and elevation are unchanged. Five JVM tests cover these rules.

## Considered and not changed

- **Persistent Vulkan pipeline cache (F13).** The renderer has about six
  pipelines, so this only helps repeat-launch compilation. Cache validity is
  driver-specific and cannot be tested here.
- **More image/geometry workers.** Workers already yield while waiting for
  inputs (`ResourcePending`), so the two-worker pools are CPU-only. Without a
  profile there is no evidence they are saturated.
- **Terrain mesh CPU.** The earlier "186–197 ms" mesh figures include an injected
  180 ms network delay; the mesh work itself is a few milliseconds.
- **Selection bounds (F04), dependency-graph scheduling (F05), offline download
  planning (F12).** These remain the largest structural opportunities from the
  1 November audit. Each changes which tiles are requested and needs a device
  trace and visual checks before rollout.

## Validation

- Rust workspace: all tests pass, including the real Vulkan (lavapipe) and GLES
  (Mesa) renderer tests. The baseline ran on the same machine.
- Kotlin JVM: 145 tests (140 before + 5 new mask tests; 2 admission tests added
  to an existing class). Full-suite runs passed apart from the case below. One existing test,
  `PipelineWaitTest.realCacheFreeHttpWinnerSurvivesRamEvictionAndRestartWithoutAnotherRequest`,
  fails intermittently in full-suite runs before and after these changes, and
  passes when run alone.
- Lint: 0 errors.
- Published APK: not debuggable; signature and 16 KiB ZIP/ELF alignment verified;
  43 JNI exports per ABI. All three renderer libraries are **byte-identical to
  0.1.12**, rebuilt with Rust 1.99.1 and the 0.1.12 registry path. All 623
  original assets are identical. See `validation/full-audit-2026-11-06/release.json`.
- At the user's request the final full-suite Kotlin rerun before publishing was
  skipped. The intermittent `PipelineWaitTest` case above failed 1 of 6 times
  alone on both the untouched 0.1.12 checkout and this change.

No physical device or emulator run was possible, so no frame-rate or loading-time
claim is made.
