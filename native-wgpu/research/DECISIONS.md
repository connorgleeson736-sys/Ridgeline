# Research and architecture decisions — 2026-10-29

Official sources inspected (downloaded snapshots alongside this file):
- https://raw.githubusercontent.com/gfx-rs/wgpu/master/Cargo.toml
- https://raw.githubusercontent.com/gfx-rs/wgpu/master/wgpu/src/api/surface.rs
- https://raw.githubusercontent.com/gfx-rs/wgpu/master/wgpu/src/api/instance.rs
- https://raw.githubusercontent.com/rust-mobile/ndk/master/ndk/src/native_window.rs
- https://raw.githubusercontent.com/rust-windowing/winit/master/Cargo.toml
- Official crates index https://index.crates.io (wgpu, glam, ndk, jni, bytemuck, pollster).

GitHub REST API access is denied by the environment proxy. Raw official upstream
sources and the crates index are accessible; no policy bypass used. wgpu master
advertises 30.0.0; crates index supplies released non-yanked 30.0.1. Pin 30.0.1
instead of tracking master. Rust stable installed is 1.99.1. NDK installation
and dependency resolution are in progress; architecture is not proven yet.

Candidate: Compose native controls over an Android SurfaceView, rendered by a
Rust cdylib using wgpu Vulkan/GLES backends. JNI passes lifecycle/input/commands
and native buffers, never a browser runtime. Surface lifetime must be owned and
released correctly, pause stops frame scheduling, and recreation reconfigures
surface resources. Rust pure core remains independently host-testable.

Reference facts verified in source: terrain exaggeration defaults to 1; camera
3D pitch defaults to 58 degrees (45 low power). Mist sky/background is #bdcbd0.
LINZ natural grade saturation -0.25, contrast 0.26, brightness minimum 0.058,
maximum 1; Esri differs and gap compensation in imagery.js is mandatory.
Source selection lives in gap-imagery.js, gap atlas, regional-imagery.js,
south-overviews.js and overview-catalog.json. The six binary packs total
364,762,502 bytes; retain their hashes/range mapping and original WebP bytes.
The six ZIPs in the download branch contain these same packs; final APK will
bundle the verified packs for direct range access rather than need ZIP import.

Existing android/ planner has divergent GPX limits and interactions. Reusing it
without reconciling against Capacitor would violate parity. No completed parity
claim is permitted until reference fixtures and captures establish it.
