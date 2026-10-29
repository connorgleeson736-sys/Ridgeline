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
