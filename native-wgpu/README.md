# Ridgeline native migration — in progress

Current checkpoint: skirt-free terrain by explicit request; independent cache-hit
admission, shared cancellable transfers, freshness-aware encoded RAM, sharper
resident cover, finished imagery/plan reuse and bounded predictive warming are
implemented. **Offline / imagery → Prepare nearby terrain while idle** enables
optional GPU preparation (off by default). See [scope/evidence](validation/cache-revisit/).
Historical skirt fixtures below describe earlier builds; cross-LOD cracks may
be visible without skirts. No A20 FPS/interaction acceptance is claimed.

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
