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

## Camera equivalence and bounded upload scheduling

Generated camera fixtures directly from the authoritative bundled MapLibre engine:
1,712 screen projections across NZ positions, zoom5/11.9/16/19, bearings, pitches
and phone/landscape sizes. Native Rust passes with worst screen deviation
0.00390625px. Near/far and horizon policy also match numerically (unpadded, zero
roll, default minimum elevation). This exposed and fixed prototype camera-up sign
and altitude scaling: MapLibre uses mean Earth circumference 40,030,228.88407185m,
not the EPSG:3857 circumference. Rebase altitude as well as horizontal position
before Float32 GPU transforms to keep close high-elevation views stable.

This does NOT prove terrain collision, gesture anchor/inertia, padding/fitRoute,
real DEM shape or visual parity. Those remain explicit acceptance items.

Android GPU uploads now coalesce by tile identity, bound pending ownership and
upload at most two complete tile payloads per frame. Device memory class sets
GPU residency; paused apps stop frames. Added queue burst/dedup unit test (must
rerun latest build). GPU smoke additionally verifies exact graded pixel, one fine
child plus parent siblings without uncovered pixels and a 24-tile residency cap.

Software Android emulator reached boot_completed=1 and package service is ready.
Direct standard local ADB protocol tooling avoids changing read-only host HOME.
First APK install before package service was ready failed with Broken pipe; retry
with full x86_64 APK is running. System image had a system-server restart during
slow software boot; these logs are not app crashes. Need actual app launch, GPU
surface and lifecycle evidence before claiming emulator validation.

## Viewport selection, filtering and actual Android integration attempts

Repository audit now durable in native-wgpu/research/AUDIT-018.md, including
route/profile contracts, original JSON trace limits, exact source chain and
remaining acceptance gates. Latest preview branch independently remains39431dad.
Additional current official-source snapshots/hashes recorded in research/.

Replaced temporary3×3 loader requests with native conservative viewport quadtree,
bounded96-target test plans, prioritized refinement heap, actual parent requests
and retained ready data. Phone density is included in the native projection;
initial camera now uses exact Aoraki coordinates/elevation3724m. Plan tests verify
center coverage and no ancestor overlap at multiple pitches/zooms. Unknown DEM
extrema are conservatively0..4000m for visibility only; actual terrain is never
synthesized. Real per-tile extrema, stitching and movement policy remain open.

GPU cover recomputes once per changed residency batch; duplicate JNI uploads touch
resident content without rebuilding height arrays. Image resources now close on
all success/error paths. Dedicated priority workers, bounded streams and HTTP
cache cleanup are present; combined peak memory still needs measured budgets.
Added GPU9-level imagery mips and supported anisotropy; GPU checkerboard
minification readback passes while full-resolution level0 remains unchanged.

Rust optimized libraries compile for ARM64, ARMv7 and x86_64. Added reproducible
build script, pinned/checksummed Gradle wrapper, overview ZIP extractor and APK
verifier. Full x86_64 packaging verifies6unchanged packs/364,762,502bytes,
5,277unique packed photo ranges (plus67coarse) and no web assets/WebView/Capacitor
DEX references. This is packaging evidence, not feature completion.

Emulator first boot took≈397s without KVM. Full debug fixture APK installed and
activity launched, but the host emulator segfaulted in SwiftShader after logging
unsupported WGSL debug OpSource metadata. Disabled optional InstanceFlags::DEBUG,
retaining validation. Second boot≈210s; optimized APK installs successfully and
ProofActivity launches (am start statusok). Vulkan device creation and first
sync export are logged. Screenshot initially black; inspecting warm UI/capture
before claiming surface success. Debug fixture is explicitly labeled synthetic
flat heights/genuine Cook imagery; it never appears as live-source fallback.

Cloud selection benchmark:500 warmed calls, median33.42µs/p9583.024µs with95–96
leaves/96budget. This is isolated optimized cloud CPU selection only, not a phone
speed estimate, full frame time, or a comparison with the trace's nested timings.

Exact next step: finish optimized emulator fixture capture and six-pack range-read
check; test surface recreation/background. If emulator rendering fails, diagnose
native/backend compatibility with actual logs. Rebuild ARM optimized device APK
with latest source (mips/selection/JNI changes), run all tests/lint/package checks,
commit. Live DEM/visual acceptance still needs the pending network draft publish;
then complete parity before migrating the full planner/UI. Do not stop at a plan.
