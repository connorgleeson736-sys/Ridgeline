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
