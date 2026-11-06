# Ridgeline native migration — in progress

[Application-wide performance audit, 1 November 2026](research/FULL-APP-PERFORMANCE-AUDIT-2026-11-01.md):
23 ranked findings, pinned code evidence and current research. Report only;
the audited APK was 0.1.5. Main next candidates are raw local-I/O isolation,
GPU upload acknowledgement, classified recovery and per-tile critical-path tracing.

Latest APK: **[0.1.13 Full Audit Base](checkpoints/README.md)** — separate app
`nz.ridgeline.nativeapp.fullaudit`, new signing key, installs alongside 0.1.12.
Non-debuggable optimized build, still-camera refinement uploads, direct opaque
imagery packing and WebP seam masks. Renderer libraries byte-identical to 0.1.12;
no phone measurement. [Audit and limits](research/FULL-AUDIT-2026-11-06.md).

Previous APK: **[0.1.12 Additional Latency Audit Base](checkpoints/README.md)**,
source `6052065`, version code13; same Native Performance package/signing identity.
Late camera latching, completion-driven draping, coherent incremental GPU overlays,
active-refresh budgets and immediate first-qualified adaptive terrain. Capacitor
route colours/nominal widths, screen-space antialiasing, layer order and Open Sans.
Original imagery/terrain assets and error thresholds remain unchanged. Focused
Vulkan/GLES and JVM checks pass; handset latency is unmeasured.
[Implementation and validation limits](research/ADDITIONAL-AUDIT-2026-11-05.md).

Previous APK: **[0.1.11 Imagery Border Recovery Base](checkpoints/README.md)**,
source`aab3c12`, versioncode12. Fixes accepted/cached LINZ-only failure fallbacks
at survey seams and missing stationary retry wakeups after repeated transient
failures. Prepared compositions are invalidated; original HTTP caches, saved maps,
all621assets and3optimized renderer libraries remain unchanged. Four focused JVM
checks and APK packaging checks pass; no physical-device failure trace/run.
[Mount Cook border findings and scope](research/IMAGERY-BORDER-RECOVERY-2026-11-03.md).

Previous APK: **[0.1.10 Native Latency Audit Base](checkpoints/README.md)**,
source `655de14`, version code11, same Native Performance package/signing key.
Independent submitted-scene input picking, camera-first bounded uploads,
indexed/cached boundary clipping, per-terrain cartography fragments, route-only
invalidation, stable label slots/dirty-row updates and shared unshaded 2D resources.
Experimental measured focus interior is off by default; unsupported measured
parent/skirt joins use normal complete-patch loading. Original rasterisation and
full imagery/terrain detail are retained. Focused JVM/Rust and production
Vulkan/GLES fixtures pass; no handset speed claim.
[Audit implementation and rollout limits](research/NATIVE-LATENCY-AUDIT-2026-11-03.md).

Previous APK: **[0.1.9 UI and Layers Base](checkpoints/README.md)**, source `d258866`.
Capacitor-style map controls, route drawer and sheet, direct waypoint editing,
ordered route combination, chart inspection, GPX sharing and every map-layer
group now use the native renderer. Aerial, Satellite and NZ Topo, names, peaks,
roads, tracks, huts, contours, imagery boundary and Advanced controls are included.

Unshaded 2D avoids DEM/halo downloads and uses a planar viewport cover. Cached
native font atlases avoid remote glyph requests; disabled vector geometry is
skipped. Shared background source loading and useful parent/child tiles preserve
imagery priority. Instanced route/layer lines use one sixth of the equivalent
expanded upload bytes with identical pixels in the controlled fixture.

Eleven focused JVM checks, Kotlin and three Android release builds, plus Vulkan
and GLES graphics fixtures pass. Same Performance package/key; original assets
preserved. No physical-device interaction or speed benchmark. Native font/line
placement and offline paper-frame/PDF differences are documented in the
[implementation and performance report](research/UI-LAYERS-MIGRATION-2026-11-02.md).

Previous 0.1.8 HTTP Latency Base, source `9b7e82f`:
Cold HTTP responses now reach decoding before their full-size disk-cache writes.
OkHttp 5.3.2 races alternative connection addresses after 250 ms, so an
unresponsive first address need not block a working route. Loading diagnostics
separates DNS, connection/TLS, provider headers, wire reads and cache processing,
and shows observed body throughput. Existing cache entries remain readable.

Controlled real-HTTP host fixtures save **503 ms** under a 500 ms cache hold and
**400 ms** with an unresponsive first address. These do not establish the cause
of the phone's five-second waits. Eight focused JVM checks pass; same package/key
for updates, all 618 original assets and three optimized native libraries
unchanged. No broad suite/lint or phone benchmark.
[Implementation, results and remaining HTTP limits](research/HTTP-LATENCY-2026-11-01.md).

Previous 0.1.7 Latency Base, source `36f9477`:
Original file I/O yields before CPU decoding; revalidated late prepared-cache hits
can beat source rebuilding; saved WAL readers bypass both application and inherited
helper locks. Offline source lookahead and independent region queries remove
serialization. Uploads retain ownership until typed JNI acknowledgement; actual
capacity releases wake retries. Vulkan mip/frame commands share a submission;
GLES keeps the prior path. HTTP disk-cache close runs in the cleanup thread.

Controlled host cases avoid 321 ms of fallback wait and 250 ms of writer-monitor
wait. These are not phone results. Eleven focused JVM checks and three graphics
configurations pass; original pixels/mips/assets preserved. All3optimized ABIs
rebuilt; signed in-place update over0.1.6. No full suite/lint.
[Implementation, measurements, tradeoffs and remaining work](research/ASYNCHRONOUS-PIPELINE-2026-11-01.md).

Previous 0.1.6 Download Controls Base, source `f6f5ea0`:
**Offline / imagery → Download jobs** includes saved 1–50 sliders for moving,
stationary and additional background transfers (defaults 8/12/2). Changes apply
immediately, with a reset button; existing downloads finish when reducing limits.
Both HTTP paths support the selected range. Two focused checks pass, including
50 simultaneous real HTTP transfers per path. APK signature/alignment verified;
all 618 assets and three optimized native libraries are byte-identical to 0.1.5.
[Scope and validation](research/DOWNLOAD-CONTROLS-2026-11-01.md). No phone benchmark.

Previous 0.1.5 Surface Focus Base, source `b0d76de`:
Both source plans now prioritize the actual centre-ray terrain intersection,
separately from the navigation pivot. CPU picking is reused; unavailable or
stale hits preserve the old policy. In the focused fixture the visible face
moves from request rank33 to0 with the same72-source budget. All3optimized ABIs
rebuilt;2Rust/2Kotlin checks and packaging verification pass. No phone latency
claim. [Implementation and scope](research/SURFACE-FOCUS-2026-11-01.md).

Previous 0.1.4 Current View Base, source `db36617`:
Canceled off-screen calls could retain underlying OkHttp slots after logical
cancellation. The escape path now has an independent bounded Dispatcher, with
retired-call routing and real underlying queue counts in Loading diagnostics.
One focused real-HTTP regression passes: 253.3 → 2.0 ms in the controlled cache
lock replay; no phone timing claim. See the [structural audit](research/RETIRED-HTTP-2026-11-01.md).

Previous 0.1.3 Cold Loading Base, source `8ac2e3d`:
Reserves existing foreground network capacity for imagery refinement, removes
derived disk/shared-decode waits from CPU workers, adds local cold coverage and
cache-free response persistence. **Offline / imagery → Loading diagnostics**
shows current queue and HTTP-stage waits. See the
[refinement investigation and controlled results](research/COLD-REFINEMENT-2026-11-01.md).
Seven focused checks and package verification pass; no phone/Wi-Fi result claimed.

Previous APK: 0.1.2 Loading Base, source `d831d2f`.
Includes the foreground-priority and latency work below, with optimized ARM64,
ARMv7 and x86_64 libraries. Uses a new key and separate application ID
`nz.ridgeline.nativeapp.performance` to preserve the previously installed app.
Build/package verification passed; one earlier Android HTTP fixture timed out,
and further tests/lint were stopped at the user's request. No phone result claimed.

Latency follow-up: online foreground requests can escape a stalled disk lookup
through a bounded, shared HTTP path; nearby DEM work now decodes early and starts
missing-data parent requests before other inputs finish. Controlled host fixtures
measure 404 → 43 ms under a 400 ms cache lock and 375 → 197 ms for a
parent-dependent complete mesh, with identical mesh bytes and request counts. Healthy cache hits
remain local. See [scope, tradeoffs and reproduction](research/LATENCY-BARRIERS-2026-11-01.md).
Camera-generation batching also removes obsolete download admissions during
cancellation; one deadline wake covers a 200-request stall fixture.
Included in 0.1.2; not measured on a phone.

Foreground-loading pass: close tiles now retain priority through Rust selection,
CPU/cache queues, transport and GPU upload pressure. Fresh decoded DEMs bypass
redundant encoded requests; obsolete dependency fan-out is bounded; coverage and
immutable seam proofs are reused. **Offline / imagery** now includes separate
imagery/terrain detail sliders with the existing default quality. See the
[audit, host measurements and limitations](research/VIEWPORT-LOADING-AUDIT-2026-10-31.md).
Included in the 0.1.2 APK linked above.

31 October performance pass (checkpoint version 0.1.1): exact DEM halo/axis reuse, an ordered seam sweep,
compatible array-instance grouping, an exact CPU mip fallback and a mesh-bounded fragment program are
implemented. Drawable cover construction now merge-joins geographically ordered regions
instead of testing the full terrain/imagery cross-product (up to 13.3× in the
fragmented-source host fixture). Additional fixes start idle demand immediately, reject stale derived entries
before decompression, and share overlapping original DEM decodes. The host
local-file DEM mesh fixture improves 4.2% in the final recorded run; this is not
a phone or network-loading result. See the [audit, individual measurements,
tradeoffs and follow-up candidates](research/PERFORMANCE-AUDIT-2026-10-31.md).

Current checkpoint: selective MapLibre-style terrain skirts restored by request; independent cache-hit
admission, shared cancellable transfers, freshness-aware encoded RAM, sharper
resident cover, finished imagery/plan reuse and bounded predictive warming are
implemented. **Offline / imagery → Prepare nearby terrain while idle** enables
optional GPU preparation (off by default). See [scope/evidence](validation/cache-revisit/).
Selective skirts protect unproven terrain joins and omit proven continuous shared
edges/photo subdivisions. Neighbour DEM halos, parent fallback and exact imagery
filtering are retained. See [seam protection evidence](validation/seam-protection/).
No A20 FPS/interaction acceptance is claimed.

The audit checkpoint adds bounded **GPU texture arrays**, shared bindings/batching,
reusable GLES mip scratch, stable index suballocations and bulk pixel packing.
**Offline / imagery → GPU textures** selects Automatic, Compatibility or Texture
arrays. Automatic uses arrays on Vulkan and pooled separate textures on GLES:
arrays reduced allocations/draws but were slower in the host GLES fixture.
A **0–1000 MiB automatic disk-cache slider** applies after app restart; saved
regions and attached ZIPs remain separate. A separate **combined RAM tile-cache
slider (0–1000 MiB)** applies immediately across encoded, decoded terrain and
prepared tile payloads. Zero disables their retention. Working memory and GPU
resources remain separate; high settings can exhaust phone memory. See [measurements and limitations](research/COMPREHENSIVE-AUDIT-IMPLEMENTATION.md).

Parent fallback now keeps a cached ancestor behind ready fine tiles during camera
movement, restores prepared RAM ancestors across zoom jumps, and admits/prioritizes
those uploads. Cache eviction protects useful fallback ownership.
[Regression scope](validation/parent-fallback/) includes pixel-identical full-frame
coverage under fine-only demand on Vulkan/GLES arrays.

Separate Kotlin/Android UI + Rust/wgpu app. Authoritative reference: Capacitor
0.18.0 source `d880143418ac46ece98adff0b3b2fbaedf044d46`, latest
`android-preview-download` checked at `39431dad230945919a7b7d9d0ef2e86db8492ead`.
The existing Capacitor and earlier Android projects are preserved.

**This is a working renderer prototype, not a feature-complete Ridgeline release.**
See `../GOAL.md` and `../NATIVE_PROGRESS.md` before resuming or presenting results.
No phone speedup or real-terrain visual parity has been established.

## Reproduce on Linux x86_64

Install JDK21, Node22+, Python3, Rust1.99.1, SDK platform36, build-tools35.0.0,
NDK29.0.14206865. Cargo.lock pins Rust dependencies; Gradle8.13 wrapper pins the
Gradle archive checksum. Kotlin2.2.21 / Android Gradle Plugin8.13.2 are pinned.
The Android package is `nz.ridgeline.nativeapp`, with separate private data.

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi
export JAVA_HOME=/path/to/jdk21
export ANDROID_HOME=/path/to/android-sdk
export RIDGELINE_OVERVIEW_ZIPS=/path/to/original-six-overview-zips
```

Obtain the six original `overview-packs/*.zip` from the authoritative preview
branch. Do not copy old 0.19 experiment assets or modify the original application.
The packaging step validates the original ZIP hashes and their inner payloads against the 0.18 source catalog:

```sh
native-wgpu/tools/build-android.sh
```

The default builds optimized ARM64 and ARMv7 native libraries inside a signed debuggable
APK, bundling all six original overview ZIPs and their payloads (364,762,502 unchanged bytes).
It bundles the six exact original ZIPs, checked against `overview-archives.json`.
Their payloads are already STORED, so Android reads WebP byte ranges directly
inside the ZIP assets. No extraction or duplicate .bin files are needed. All 67
coarse images and gap overlays are preserved. Website-only Cook substitutions
are excluded, matching the packaged0.18Capacitor APK.
Generated packs, libraries, build outputs and debug keystores are not committed.

Output: `native-wgpu/artifacts/Ridgeline-Native-Prototype-Debug.apk` plus checksum.
Debug signing uses the local Android debug keystore, so byte-identical signed
APKs across machines require the same locally managed key. Never commit it.
The checked-in LINZ client key is the existing authorized public application key.

For a separately installed performance checkpoint, pass
`-PridgelineSideBySide=true` to Gradle. This uses
`nz.ridgeline.nativeapp.performance` and the label **Ridgeline Native Performance**.
Set `RIDGELINE_SIGNING_KEYSTORE` to an external Android debug-format key to reuse
its signer. The original native package remains the default. Keep signing keys
and their private backups outside Git.

For emulator ABI also install `x86_64-linux-android`, then set
`RIDGELINE_ABIS=arm64-v8a,armeabi-v7a,x86_64`. `RIDGELINE_PROFILE=debug` makes an unoptimized
native build for debugging. The final device artifact should use `release`.
`RIDGELINE_GRADLE` and `RIDGELINE_CARGO` can point to existing installed binaries.
Keep normal environment proxy/CA trust settings where network policy requires it.

## Validation

```sh
cargo test --locked --manifest-path native-wgpu/Cargo.toml --workspace
node native-wgpu/tools/source-goldens.mjs
node native-wgpu/tools/camera-goldens.mjs
# Software Vulkan is a correctness fixture, not a phone benchmark:
VK_ICD_FILENAMES=/path/to/software-vulkan-icd.json \
  cargo run --locked --manifest-path native-wgpu/Cargo.toml -p ridgeline-renderer --example gpu_smoke
```

`build-android.sh` runs Android unit tests, lint and assembly. Unit tests compare
2,520 source-routing cases and 360 exact masks to the original algorithms, plus
holes, budgeted source/fallback demand, independent upload coalescing and byte limits. Rust tests cover DEM encoding, shared halo
samples, parent cover, bounded viewport selection and 1,712 camera projections.
GPU proof checks exact colour grading, parent/child ownership, fallback coverage
independent full-resolution image/geometry LOD with exact UV crops and bounded
residency on controlled synthetic geometry. Source selection uses logical pixels;
terrain (431px target) and photo detail (256px / 2^.25) have separate plans,
LRU pools and upload jobs. Rendering still uses the full surface resolution.

Normal emulator testing uses `adb install -r APK`, then
`adb shell am start -n nz.ridgeline.nativeapp/.MainActivity`. Debug-only
`.ProofActivity` explicitly labels its synthetic flat elevation while rendering
original bundled overview imagery and exercising one range/bitmap decode from each overview pack.
It forbids network requests and is not a terrain preview or fallback mechanism.
For a platform-only automated integration run:

```sh
adb shell setprop debug.hwui.drawing_enabled 1 # Android ATD images disable UI drawing by default
adb shell am instrument -w nz.ridgeline.nativeapp/.ProofInstrumentation
```

This checks independent geometry/photo uploads, native frames, six original ZIP range reads, exact terrain picking on
the controlled flat fixture, surface release/recreation, activity rotation and
native SQLite/GPX data behavior.
It explicitly excludes real-terrain parity and physical-phone performance.

`tools/emulator-adb.py` implements standard direct local ADB for the restricted
host where adb's default home is read-only; it is never included in the APK.

## Open acceptance gates

The current published environment permits genuine LINZ DEM/imagery requests;
host GPU rendering/picking and same-LOD edges pass. Android30 production-loader integration and terrain picking pass;
cross-LOD/visual parity still need verification. Terrain collision/gesture anchoring/inertia, real LOD stitching/fades,
lighting/fog checks, route/planner/profile migration, overlays/labels/settings,
location/offline management and physical-phone benchmarks remain incomplete.
Do not confuse build success or subpixel numeric camera agreement with complete
visual or interaction parity. See the durable progress log for the exact next step.

## Genuine LINZ host proof (network now available)

With Pillow installed, run `python native-wgpu/tools/fetch-live-proof.py /workspace/native-provider-fixtures/live-proof`.
This fetches actual 256px LINZ elevation/aerial inputs using the existing authorized
client configuration without logging the key. It fails on transport/nodata instead
of synthesizing a replacement. It emits source hashes and two adjacent129meshes.
Then run `VK_ICD_FILENAMES=/usr/lib/chromium/vk_swiftshader_icd.json cargo run --locked --manifest-path native-wgpu/Cargo.toml -p ridgeline-renderer --example live_terrain -- /workspace/native-provider-fixtures/live-proof`.
The example validates exact shared edges, executes native GPU draw/readback and
checks the center pick against the real mesh. `native-live.ppm` is a host capture.
This does not establish Android loader execution, cross-LOD seams or visual parity.


For opt-in production-loader integration on Android, use
`adb shell am instrument -w nz.ridgeline.nativeapp/.LiveProofInstrumentation`.
Unlike ProofInstrumentation, this launches MainActivity and requires genuine
geometry/image source residency and a terrain pick; it has no synthetic fallback.
A passing run is Android integration evidence, not visual or gesture parity.

The preinstalled system Java may be a JRE without javac. Use a full JDK21 (the
current session verified Temurin21.0.13 against official Adoptium metadata).
Keep caches writable, e.g. GRADLE_USER_HOME=/workspace/android-tools/gradle-cache
and ANDROID_USER_HOME=/workspace/android-tools/android-user. Proxy Java downloads
must use the inherited HTTP proxy and managed CA trust; this session used the
system Java truststore /etc/ssl/certs/java/cacerts, without disabling TLS checks.
Use Gradle --max-workers=2 during concurrent software emulator testing.

Native skirts now use the reference logical-zoom depth rule and preserve every
surface sample. Geometry vertex storage is399384bytes/source; source budgets
remain bounded, but combined device memory and visual parity are still open.
`gpu_smoke` includes a visible mixed-LOD gap regression. Latest frozen local build:
`artifacts/Ridgeline-Native-Skirt-Proof-Debug.apk`; audit/hash in validation/skirts.
This session's software emulator repeatedly lost Android package/activity services
before installation, so its new instrumentation checks remain unverified.

Latest quality-preserving work-removal checkpoint: cached viewport demand plans,
exact DEM footprint/integer sampling, duplicate pending upload suppression, and
unchanged GPU scene/instance transfer suppression. Tests compare complete height
buffers against the pre-optimization algorithm and genuine LINZ pixels against
the previous GPU checkpoint. All25Android tests and19Rust tests pass; no device
speedup is inferred. Details/hashes: validation/quality-work/. Latest local APK:
`artifacts/Ridgeline-Native-Quality-Work-Debug.apk`. Live Android/visual gates remain open.


## Latest Android compatibility checkpoint

Android30ATD software-emulator runs now pass controlled lifecycle/rotation,
SQLite/GPX, genuine production loading/picking, and exact nine-level mip readback.
See `validation/android30/`. This supersedes earlier installation-blocker notes;
real-view/reference visual acceptance and phone performance remain open.
The latest normal local artifact is
`artifacts/Ridgeline-Native-Validated-Mips-Debug.apk` (SHA in the validation audit).
All three optimized ABIs and all six unchanged original ZIPs are included.

The renderer attempts one native graphics backend at a time. GLES initialization
checks actual mip pixels once; broken GPU mip generation switches to a validated
CPU chain with unchanged original256pxlevel0, all9levels and existing filtering.
Working adapters retain GPU mip generation. This check is never in the frame loop.
Explicit device content regression:

```sh
adb shell am instrument -w nz.ridgeline.nativeapp/.MipmapProofInstrumentation
```

For managed HTTPS proxy tests only, set `RIDGELINE_DEBUG_CA_PEM` to the configured
public CA PEM when building a separate local test APK. Generated debug resources
restrict that anchor to the three source domains; no CA is stored in source and
TLS/hostname verification stays enabled. Unset the variable and rebuild to obtain
the normal system-trust APK. Verify no `res/raw/ridgeline_session_ca.crt` is present.
Release does not use this debug configuration. Do not distribute the local trust
profile as the normal artifact.

Official adb works with writable `ANDROID_USER_HOME`; this session used
`adb -P 5038 connect 127.0.0.1:5555` and
`adb -P 5038 -s 127.0.0.1:5555 install --no-streaming -r APK`.
Large streaming installs stalled in the minimal helper, so use official adb for
APK transfer. The minimal transport remains a read-only diagnostic alternative.


## Supported-host capture of the exact reference APK

```sh
python native-wgpu/tools/capture-reference.py /path/to/Ridgeline-3D-0.18.0-Performance-Full.apk /path/to/external-capture-output
```

Requires Python Playwright and current Chromium (`--chromium` overrides its path).
The tool rejects any APK whose hash differs from the published0.18Fullbinary.
It serves unchanged packaged assets and original overview byte ranges, reproduces
only the native HTTP transport, and fetches genuine providers through inherited
proxy/CA trust. No certificate checks are disabled and no source URLs/client keys
are logged. Cached fixture bodies expire after6hours. Saved body hashes, exact
camera/viewport, loading state, errors and transport failures accompany captures.
Unsettled/error runs fail. The map canvas is read inside its render callback,
without altering production shaders, quality policy or preserveDrawingBuffer.

`validation/reference-host/` records a settled320x640default view with no script
or transport errors. Reference center elevation is3648.731912109524m, clamped to
terrain; the earlier native default held3724m. Host reference capture is now
available; Android reference execution, matched native pixels and acceptance of
real LOD/gesture/sky behavior remain separate open gates.


## Smaller Base build and native map controls

The user-requested Base checkpoint omits the six large overview ZIPs and retains
67coarse imagery files. Missing bundled ranges use the existing full-resolution
online source chain. Terrain detail and imagery filtering are unchanged. Original
ZIPs and the earlier Full checkpoint remain available separately.

Build with `RIDGELINE_BUNDLE_OVERVIEWS=0 native-wgpu/tools/build-android.sh`.
The wrapper recreates APK packaging state so switching Full to Base removes stale
payload bytes as well as ZIP directory entries. Existing compile/download caches
are preserved. Base cannot run the controlled six-pack instrumentation fixture;
that fixture requires the Full build. Latest downloads: [checkpoints](checkpoints/).

The native prototype now has zoom-in, zoom-out and north-up buttons, and clickable
LINZ/Esri attribution. It preserves the full rendering surface underneath native
controls. Terrain ownership now matches the reference parent DEM mapping; exact
LOD cover, gestures/collision, transitions and full route/planner UI remain open.

## Anchored gestures and current-view loading checkpoint

Pan/pinch/rotate/tilt now hold a geographic terrain anchor under the finger or
pinch centroid. Center elevation stays fixed through the gesture and eases after
release. A native Android controlled drag run measured 0.0235m terrain-anchor drift;
this proves the test gesture, not real-terrain visual acceptance or phone speed.

GPU cache residency is separate from the current ideal HLOD cover. Cached children
no longer force detail on a view that requests a parent; missing ideals retain
real loaded fallback coverage. Detail requests keep their slots, downloads have
separate bounded terrain/imagery workers, and transient failures retry with backoff.
Full mesh/image resolution, filtering, source ceilings and six source ZIPs remain
unchanged. See [research and remaining transitions](research/MAPLIBRE-GESTURES-HLOD.md).


## MapLibre 6.14 behavior with a focused terrain pipeline

Latest implementation adds reference-style momentum, intentional pinch/rotation/
tilt thresholds and directions, smooth zoom controls, eye/near-plane terrain
clearance and grounding from actual rendered geometry. The terrain uses one shared
full-detail grid with exact per-tile float heights. Automatic HTTP cache is up to
1GB, adapted to free storage, with deduplicated body requests and retained GPU
cache recency. User selected immediate imagery replacement; there is no fade loop.

See [reference code, decisions and remaining differences](research/MAPLIBRE-614-ADAPTATION.md).
This is an implementation checkpoint, not a phone-performance or full-MapLibre
acceptance claim. Full source imagery/mesh detail, six original ZIPs and Capacitor
remain preserved; normal downloadable APK stays the smaller Base variant.


## Attach existing overview imagery and adaptive terrain

Open **Offline / imagery → Add imagery ZIPs** and select the original overview ZIPs
already on your phone. Multiple selection supports all six; files remain in place
with persisted read permission and are validated against original archive hashes.
No extraction or duplicate pack storage. Detach never deletes the original ZIP.
Imagery packs do not contain terrain; use network or a saved region for elevation.

The renderer now carries0.18's bounded adaptive interior meshes, retains more
recent terrain on the GPU, handles actual memory pressure, reuses frame storage
and bindings, and uses16bit indices. See
[research, bounds and limitations](research/ADAPTIVE-TERRAIN-AND-RESIDENCY.md).
