# Native 0.1.9 audit implementation / 0.1.10

Scope: supplied native audit, not the separate Capacitor audit. Original findings
and standalone experiments are retained under `input/`. Their source reference is
0.1.9 (`d258866d31509174c01aa0022094015d687566c2` / release `e2741ae`).
Uploaded ZIP SHA-256:
`4d7760eb6358d7ad083826caab1b8fdee00f492c0edaf97465847df6f908ace3`.

Implementation: [report](../../research/NATIVE-LATENCY-AUDIT-2026-11-03.md).
Final APK identity/source/signature/digests: `release.json` (written at packaging).
Public download byte verification: `download-verification.json` (after push).

Focused proof: `NativeAuditTest.xml`, `jvm.log`, `rust-tests.log`, `gpu.log`,
`gles.log`. No broad tests/lint or physical-device execution.

Reproduction from repository root with the configured toolchains:

```sh
# Focused JVM checks only, never part of APK assembly:
bash native-wgpu/android/gradlew --max-workers=2 -p native-wgpu/android \
  -PridgelineSideBySide=true :app:testDebugUnitTest \
  --tests nz.ridgeline.nativeapp.NativeAuditTest
# Focused fragment-cache check:
cargo test --locked --manifest-path native-wgpu/Cargo.toml \
  -p ridgeline-renderer --release cartography::audit_tests::audit_cached_fragments_and_latest_worker
# Production graphics fixture; run with Vulkan/GLES software runtime available:
cargo run --locked --manifest-path native-wgpu/Cargo.toml \
  -p ridgeline-renderer --release --example layer_smoke
# Rust JNI: release builds for all three targets with Android NDK29 linker,
# Android API26, -C link-arg=-Wl,-z,max-page-size=16384; strip copied .so files.
# APK assembly only; signing key remains external to Git:
bash native-wgpu/android/gradlew --max-workers=2 -p native-wgpu/android \
  -PridgelineSideBySide=true -PridgelineAbis=arm64-v8a,armeabi-v7a,x86_64 \
  :app:assembleDebug
```

The final APK uses optimized stripped Rust (`opt-level=3`, thin LTO), despite the
Android debug variant used for the established externally signed checkpoints.
The three ABI logs and package/signature/alignment output are saved here. All original 621 APK assets are compared byte-for-byte to 0.1.9; no new
session CA, web runtime or large overview packs are allowed in this Base APK.

Native Rust builds started at source `c0a420d`; final Kotlin APK source is
`655de14`. The Rust/core/Cargo input trees are identical between these commits;
the latter adds the retired-mode queue fix and records focused JVM evidence.
The final Android assembly compiles that Kotlin fix. Final source is frozen;
subsequent commits add only artifacts, reports and validation records.
