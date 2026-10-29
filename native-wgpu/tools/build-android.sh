#!/usr/bin/env bash
set -euo pipefail
RIDGELINE_NATIVE_ROOT="$(cd -- "$(dirname -- "$0")/.." && pwd)"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 21}"
RIDGELINE_NDK="$ANDROID_HOME/ndk/29.0.14206865"
RIDGELINE_LLVM="$RIDGELINE_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
RIDGELINE_CARGO="${RIDGELINE_CARGO:-cargo}"
RIDGELINE_ABIS="${RIDGELINE_ABIS:-arm64-v8a,armeabi-v7a}"
RIDGELINE_PROFILE="${RIDGELINE_PROFILE:-release}"
case "$RIDGELINE_PROFILE" in release) RIDGELINE_RELEASE_FLAG=(--release);; debug) RIDGELINE_RELEASE_FLAG=();; *) exit 2;; esac
node "$RIDGELINE_NATIVE_ROOT/tools/package-assets.mjs"
"$RIDGELINE_CARGO" test --locked --manifest-path "$RIDGELINE_NATIVE_ROOT/Cargo.toml" --workspace
IFS=',' read -ra RIDGELINE_ABI_LIST <<< "$RIDGELINE_ABIS"
for RIDGELINE_ABI in "${RIDGELINE_ABI_LIST[@]}"; do
 case "$RIDGELINE_ABI" in
  arm64-v8a) RIDGELINE_TARGET=aarch64-linux-android; export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$RIDGELINE_LLVM/aarch64-linux-android26-clang";;
  armeabi-v7a) RIDGELINE_TARGET=armv7-linux-androideabi; export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$RIDGELINE_LLVM/armv7a-linux-androideabi26-clang";;
  x86_64) RIDGELINE_TARGET=x86_64-linux-android; export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$RIDGELINE_LLVM/x86_64-linux-android26-clang";;
  *) echo 'Only tested ABI toolchains are supported: arm64-v8a, armeabi-v7a, x86_64' >&2; exit 2;;
 esac
 "$RIDGELINE_CARGO" build --locked --manifest-path "$RIDGELINE_NATIVE_ROOT/Cargo.toml" -p ridgeline-renderer --target "$RIDGELINE_TARGET" "${RIDGELINE_RELEASE_FLAG[@]}"
 RIDGELINE_LIB_DIR="$RIDGELINE_NATIVE_ROOT/android/app/src/main/jniLibs/$RIDGELINE_ABI"
 mkdir -p "$RIDGELINE_LIB_DIR"
 cp "$RIDGELINE_NATIVE_ROOT/target/$RIDGELINE_TARGET/$RIDGELINE_PROFILE/libridgeline_renderer.so" "$RIDGELINE_LIB_DIR/"
 "$RIDGELINE_LLVM/llvm-strip" --strip-unneeded "$RIDGELINE_LIB_DIR/libridgeline_renderer.so"
done
"${RIDGELINE_GRADLE:-$RIDGELINE_NATIVE_ROOT/android/gradlew}" -p "$RIDGELINE_NATIVE_ROOT/android" -PridgelineAbis="$RIDGELINE_ABIS" :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
# Freeze a reviewable artifact; never rebuild a file while it is being installed.
mkdir -p "$RIDGELINE_NATIVE_ROOT/artifacts"
cp "$RIDGELINE_NATIVE_ROOT/android/app/build/outputs/apk/debug/app-debug.apk" "$RIDGELINE_NATIVE_ROOT/artifacts/Ridgeline-Native-Prototype-Debug.apk"
sha256sum "$RIDGELINE_NATIVE_ROOT/artifacts/Ridgeline-Native-Prototype-Debug.apk"
