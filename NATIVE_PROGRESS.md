# Durable progress log

## 2026-10-29 — migration started

User explicitly replaced the Capacitor optimisation task with a custom native
Rust/wgpu Android migration. New branch native-rust-wgpu in /workspace/Ridgeline-native
starts from authoritative 0.18.0 source d880143. Original source and APKs preserved.

Initial findings: android/ is an older Compose + MapLibre Native 13.7.1 app. Its
README explicitly says flat raster/hillshade, not the Capacitor draped 3D terrain,
and excludes patches/grade. It is a source of reusable native planner/storage code,
not an accepted renderer or visual reference. Capacitor owns the current behavior.

Environment: JDK21/SDK36/Gradle8.13 present in /workspace/android-tools; Rust is not
on PATH. Need inspect/install Rust and Android NDK before wgpu cross-build.
Network policy restricted; inherit proxy and CA trust. No device execution yet.

Commands: git worktree add -b native-rust-wgpu /workspace/Ridgeline-native d880143...
Result: successful. No native implementation or APK yet.

Exact next step: fetch official wgpu current Cargo/example/docs and Android surface
lifecycle guidance; inspect Capacitor camera/terrain shaders/routing/native storage
and existing Compose planner; write concrete parity audit and architecture decision;
install Rust/NDK and implement the smallest terrain/imagery proof.

## Toolchain and initial pure core

Installed Rust stable 1.99.1 with CARGO_HOME=/workspace/android-tools/cargo and
RUSTUP_HOME=/workspace/android-tools/rustup. Android aarch64 target install started.
Official source snapshots fetched using raw.githubusercontent.com; GitHub REST API
is proxy-denied. Read research/DECISIONS.md for source URLs and provisional choices.
Rust workspace started: core geographic/height encoding primitives and renderer
crate pinned to wgpu 30.0.1. NDK 29.0.14206865 install started via SDK manager with
Java proxy/CA flags. No GPU implementation, Android native migration build or
parity validation exists yet. Cargo dependency resolution initially caught an
incorrect ndk feature name; corrected to rwh_06/nativewindow before compilation.

Running commands/logs: /tmp/ridge-native-core-test.log, /tmp/ridge-native-ndk-install.log,
/tmp/ridge-native-rust-target.log. Exact next step: finish these commands, run core
tests (fix any failures), then implement SurfaceView/JNI wgpu surface and textured
DEM mesh prototype. Do not confuse the stub renderer module with working rendering.

## Native GPU and Android prototype checkpoint

Host core: 3 tests pass. Real wgpu draw+readback passed using software Vulkan:
VK_ICD_FILENAMES=/usr/lib/chromium/vk_swiftshader_icd.json cargo run ... --example gpu_smoke.
Pixel center [36,113,74,255] from a synthetic flat 1000m field and uniform image.
This proves native pipeline/depth/grade/readback execution, NOT real terrain parity.

Rust ARM64 cdylib successfully cross-built against NDK29/API26. Android SurfaceView
JNI integration and native widget Activity compile; assembleDebug and lintDebug pass.
APK is native-wgpu/android/app/build/outputs/apk/debug/app-debug.apk. v2 signature
and 16KiB ZIP alignment checked. No emulator/physical device execution yet.

Prototype includes real-source LINZ loading, deduplicates per tile job, bounds
HTTP persistence and decoded-ready tile count, uses native Android bitmap decode,
and passes direct native buffers to Rust. Renderer work is scheduled on a dedicated
GPU thread and only on changed camera/data (no continuous idle render loop).
These are prototype paths: route UI, geographic gap selection, overview data,
proper LOD/fallback, halo seams/mips and robust memory budgets remain open.

External blocker: LINZ/S3/Esri and some official documentation hosts are denied by
the runtime proxy. Saved configuration draft 2018dde912308198977079bf4a502b5f adds
only needed hosts. Told user to review/save in environment settings and publish;
not active yet. No bypass attempted. Live DEM fetch failed without printing key.
Continue all independent work; do not claim a real-DEM or device parity test.

Exact next step: add deterministic neighbor-halo mesh/seam tests and parent-cover
selection, port genuine bundled overview routing/range reads and gap masks, then
run the Android native surface on an emulator if feasible (no /dev/kvm currently).
Native UI/route migration must wait for the critical renderer proof acceptance gate.

## Geographic imagery and bounded native residency checkpoint

Data-only native port now selects original LINZ, all 630 reviewed geographic gap
entries, coarse gap overlays, z18 Esri parent crops, fine-survey alpha veto, and
37 Cook reference coordinates. Six original binary overview payloads are verified
by SHA-256/length before packaging, preserving 364,762,502 bytes plus coarse images.
No JavaScript is packaged or executed by the native app. Build-time Node only
extracts reference data and generates independent algorithm outputs.

Validation: 2,520 native Kotlin routing cases and 360 masks exactly match executed
0.18 JavaScript reference outputs; polygon-hole test also passes. Android unit
suite (2 tests), assembleDebug and lintDebug passed before addition of Cook data.
Need rerun packaging/build after that addition. No live data or phone claim.

Tile loader now uses pipeline=terrain-rgb, deduplicated neighbor DEM downloads,
real parent fallback on 404/nodata, global pixel-centre halo sampling, bounded
memory caches based on Android memory class, three priority workers and removal
of obsolete queued jobs. GPU renderer now bounds tile residency and uses cached
non-overlapping ancestor/descendant coverage. Controlled GPU smoke still passes.
These are not a finished viewport LOD system: frustum selection, LOD stitching,
mips, fades, bounded upload scheduling and actual camera parity remain open.

Android emulator system image and Rust x86_64 target installed. Software emulator
started without KVM; boot is slow. x86_64 native library compiles. adb cannot create
its default auth directory under read-only /home/agent; investigating an explicit
writable config/transport. Logs in /tmp/ridge-native-{emulator,x86-build}.log.
Network policy still has no additional approved hosts; real DEM validation blocked
pending the previously requested environment draft publish.

Exact next step: run GPU parent/fine coverage and memory-bound tests; rebuild both
Android ABIs with the current library, package the verified imagery/reference data,
run Android surface/lifecycle proof on the emulator, and audit reference camera.
