# Ridgeline native migration — in progress

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
holes and upload queue burst behavior. Rust tests cover DEM encoding, shared halo
samples, parent cover, bounded viewport selection and 1,712 camera projections.
GPU proof checks exact colour grading, parent/child ownership, fallback coverage
and bounded residency on controlled synthetic geometry.

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

This checks native frames, six original ZIP range reads, exact terrain picking on
the controlled flat fixture, surface release/recreation, activity rotation and
native SQLite/GPX data behavior.
It explicitly excludes real-terrain parity and physical-phone performance.

`tools/emulator-adb.py` implements standard direct local ADB for the restricted
host where adb's default home is read-only; it is never included in the APK.

## Open acceptance gates

Live LINZ DEM/imagery checks currently need the environment network draft to be
published. Terrain collision/gesture anchoring/inertia, real LOD stitching/fades,
lighting/fog checks, route/planner/profile migration, overlays/labels/settings,
location/offline management and physical-phone benchmarks remain incomplete.
Do not confuse build success or subpixel numeric camera agreement with complete
visual or interaction parity. See the durable progress log for the exact next step.
