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

## Additional official-source review / compatibility finding

Fetched current Android NDK native-activity lifecycle sample, MapLibre Native
Android documentation entrypoint, current MapLibre Mercator transform source and
style specification, and the pinned wgpu v30.0.1 surface implementation. URLs and
SHA-256 are in official-sources-2026-10-29.tsv. Android's sample separates window
ownership from resumed ticking and cleans window resources on detach; our
SurfaceView/GPU-owner lifecycle follows that separation. Pinned wgpu source
explicitly requires window ownership to outlive the surface; AndroidState stores
the owned NativeWindow after Surface. Current upstream source is research context;
0.18's actual bundled engine remains the numeric/visual reference.

Real Android integration test found emulator SwiftShader rejected SPIR-V source
language10 (WGSL) from debug source metadata and the host emulator segfaulted.
The Vulkan backend's official installed source sets this via naga debug_source;
InstanceFlags::DEBUG is optional diagnostics. Disable that flag for the Android
instance, retaining validation. Optimized retry installs/launches and gets past
that failure; rendered screenshot/lifecycle proof remains pending. Do not label
this as physical-phone validation or assume Vulkan bugs are solved universally.

Default image filtering was confirmed in filtering-controls.js/direct sampling:
bilinear, mipmaps, trilinear and anisotropy enabled. Added GPU-only downsample
chain (level0 remains original), linear trilinear sampler and16x anisotropy where
adapter capabilities support it. Minified checkerboard GPU readback matches the
expected affine graded average. Optional filtering toggles still need native UI.


## Correction: packaged Android source is authoritative

The website root includes Cook reference substitutions, but Capacitor build-web.mjs
explicitly strips their selector/import and excludes their assets. Confirmed in the
actual0.18Full APK: zero Cook entries and no ridge-reference selector. The initial
native port incorrectly carried website-only replacements; removed before UI
migration and release. Current native data path and debug fixture use the original
bundled South Island packs. Earlier Cook screenshots prove GPU mechanics only.
Build-web also changes Direct default, topo source limits/density, mobile waypoint
selection, fit padding, label visibility and provider-failure behavior. Always
inspect actual packaged assets plus build-time adaptations, not public/ alone.
