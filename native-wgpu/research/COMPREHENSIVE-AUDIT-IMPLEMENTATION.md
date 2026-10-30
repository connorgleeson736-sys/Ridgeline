# Comprehensive audit implementation

Audit source: RIDGELINE_COMPREHENSIVE_AUDIT_2026-10-30.md, commit5c29ea8; native
baseline6a84dab. Baseline Base APK frozen locally, SHA256
9c9405eb6c91bcd318bc2eead7fba102f1e7c8d5441f9c952db7deb54a307393.
Read GOAL, NATIVE_PROGRESS, native README and capacitor/PERFORMANCE-0.18.0.md.
Preserve existing Capacitor and old Android app, full pixels/heights, no skirts,
current gestures/caches, original six ZIPs and normal small Base packaging.

Selected exact-work improvements: NAT-11bulk RGBA packing; NAT-09stable index
suballocation instead of whole-atlas resets; NAT-10reusable GLES mip scratch.
User additionally explicitly requires investigation/implementation of NAT-04GPU
texture arrays. Implement bounded lazy pages, generation-safe layer leases,
layer-aware mip generation and compatible indexed batching, with the separate
texture renderer retained for A/B/fallback. Validate Vulkan/GLES and exact pixels.
Do not equate fewer allocations with measured phone FPS; no A20 access exists.

Worker/network concurrency and drawing resolution remain unchanged. Broader web
changes and resource-identity/background-download migrations are separate from
this native performance pass. Avoid combining speculative concurrency increases
with resource architecture changes.

Stage1: PixelPacking consumes NativeImagery's owned ARGB array, packs exact old
integer-premultiplied RGBA in place and bulk-writes a little-endian direct buffer.
Opaque pixels use exact red/blue bit exchange; transparent pixels keep original
rounding. No Bitmap/native channel-order assumption. Tests cover every alpha
value plus randomized opaque colours and cancellation. Host real-LINZ fixture,
60alternating samples: median0.576118->0.305270ms; p951.087018->0.715147ms.
This is packing/allocation only, not decode, whole-image pipeline or A20 FPS.
Per-tile direct-buffer writes262144->1, with no extra full image array in production.
