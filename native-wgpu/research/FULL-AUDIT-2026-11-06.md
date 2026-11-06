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

## 0.1.14 movement and overlays

Follow-up checkpoint, same day, `Ridgeline-Native-Motion-Overlays-0.1.14-Base.apk`.

### Refinement during movement

While moving, a non-urgent tile was blocked twice: by camera-first deferral
(repeating 64 ms windows during a gesture) and by a spare-time allowance that is
usually zero on 90/120 Hz displays. Sharper terrain and imagery therefore
appeared only after the camera stopped.

`UploadAdmission.movementMinimum` now admits a bounded minimum while moving:
- up to two terrain-only uploads per frame within 2 ms of measured upload time;
- one imagery upload per frame within 4 ms;
- where a single imagery upload is known to cost more than 4 ms, one every 100 ms.

GPU backlog (fewer than 2 outstanding submissions), 4 uploads and 650 KiB per
frame still apply. Urgent missing coverage and stationary behaviour are unchanged.

In a 120 Hz gesture replay (7 ms render, 0.4 ms terrain, 1.5 ms imagery uploads),
the previous rule admitted no non-urgent upload; the new rule completes 240 per
second, one terrain plus one imagery upload per frame. Up to about 6 ms of uploads
can now land in a movement frame; this trades some frame time for visible
detail during gestures and has not been measured on a phone.

### Labels inside mountains

Labels were screen-facing quads that took their anchor's depth and were
depth-tested per pixel, so slopes closer to the camera cut into the text.
MapLibre (the Capacitor app) instead decides per symbol whether terrain hides
its anchor and draws visible symbols whole.

The label pipeline now draws over terrain (`CompareFunction::Always`).
`project_points` marches the straight line from the eye to each anchor over
resident terrain, with 48 samples, a 2 m tolerance, and the last 2% beside the
anchor ignored. Occluded labels are dropped by the existing placement step.
`terrain_height_near` checks the previous sample's cover row first, so a march
costs about one bounds test per sample.

### Crawling dotted lines

Dash cycles were fixed in screen pixels, while the position along the line was a
ground distance multiplied by `512·2^zoom`. Every dash boundary therefore slid
along the line as the zoom changed. The pattern is now fixed to the ground at
whole logical zoom levels, scales with the map between them, and crossfades to
the next level, which is MapLibre's line-dasharray behaviour. Routes have no
dashes and are unchanged.

`renderer/examples/overlay_parity.rs`, on actual Vulkan and GLES execution:
- a label behind a 3,000 m ridge is hidden while one in front stays visible;
- a 0.02 zoom step moves dash transitions near the screen centre by at most
  0.20 px.

Against the previous code the same checks fail: the label is not hidden and the
dashes drift 3.15 px.

## 0.1.15 fast coverage

Follow-up for blank or slow-loading ground after fast movement. The causes,
from the code:

1. The drawable surface is built only from resident terrain, so arrived
   imagery cannot be drawn where no terrain exists yet.
2. A terrain tile at zoom *z* samples elevation at *z−1* with a half-pixel
   border, needing up to four elevation tiles; the tile waits for the slowest.
3. Each 50 ms replan cancels work that left the plan; at most two started jobs
   were parked for 600 ms.
4. After a long move there is often no coarser terrain resident for new ground.

Capacitor/MapLibre draws a photo as soon as it arrives, on available or parent
elevation, and keeps large fallback caches.

**Coarse backdrop (`SourceDemand.backdrop`).** For terrain and imagery
separately, the plan adds the ancestors of every planned tile at about four
levels above the focus leaf, clamped to zoom 5–10. The level climbs until there
are at most six tiles. They take rank 0, so they load first after a move.
`finest_sources` already excludes ancestors from the drawing targets, so finer
resident sources always win and stale fine tiles are not pulled in.

**Imagery without waiting for fine terrain.** `render_sources` selects the
nearest resident terrain ancestor, and `surface_cover` splits a coarse terrain
region by finer photo regions. With the backdrop resident, arrived imagery is
drawn on it immediately. No renderer change was needed beyond item 3.

**Terrain from its own elevation (multi-tile measured interiors).** The
renderer holds up to 16 disjoint interiors instead of one. Each keeps the
existing rules:
- the missing rim stays with a complete parent;
- downward drops must fit within the skirt;
- the parent must have drawn the whole patch in the last frame;
- staging is refused while a complete descendant exists;
- an interior is cleared when its parent changes.

Overlapping stages are refused. On the Kotlin side, early elevation decoding and
staging cover the four focus leaves plus the next twelve nearest
(`ProgressiveTerrain.eligibleRank`). Interior uploads use the terrain admission
rules, including the movement minimum. The setting is on by default as
"Progressive terrain".

The 5 November notes asked for phone seam checks before enabling it by default.
Those still have not been possible here; the GPU check below is synthetic.

**Parked work.** A started job that leaves the plan may now finish for 1.5 s
(was 0.6 s). Up to the background transfer limit can be parked (was a fixed 2).
The transport still refuses parking beyond its background cap, so parked
transfers never take foreground slots.

**Validation.** `renderer/examples/progressive_multi.rs` runs on Vulkan and GLES:
- four concurrent quadrant interiors, each owning its measured centre;
- each rim keeps the parent height;
- an overlapping stage is refused;
- 12 draw calls in total;
- promotion is pixel-identical to complete-only rendering.

It fails on the previous single-interior renderer. `BackdropTest` covers backdrop
selection and eligibility ranks. The older `layer_smoke`/`gpu_smoke` examples fail
at the same lines on untouched 0.1.12 with both lavapipe and SwiftShader, before
reaching their progressive sections. They are stale and were not used as evidence.
