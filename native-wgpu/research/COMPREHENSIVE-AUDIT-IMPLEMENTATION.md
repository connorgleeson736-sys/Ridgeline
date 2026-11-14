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


## Implemented resource architecture

NAT-04: Both full-resolution imagery (256² RGBA8, all nine mip levels) and
terrain height fields (129² R32Float) now support lazy eight-layer texture-array
pages. Generation-owned leases release slots on eviction; writes, mip generation
and draws share one ordered queue. Pages share bindings, and adjacent compatible
instances share indexed draws when topology also matches. No cross-tile filtering
or image packing resampling is introduced. Existing adaptive errors and original
heights remain. Whole-page payload bytes are reported separately from logical
resident tile bytes, including unused slots; driver padding is unknown. Pools
have at most 25 pages each (200 slots), with existing resident tile caps retained.
Empty pages release on memory pressure. This is bounded page pooling, not a
claim of a unified physical-memory allocator; sparse occupancy can cost memory.

Automatic mode selects arrays on Vulkan and separate textures on GLES. Explicit
Texture arrays and Compatibility modes are in Offline / imagery > GPU textures.
Changing the mode recreates GPU resources and reuses existing CPU/disk caches.
Arrays are tested on both APIs; they are not enabled automatically on GLES
because measured host GLES total time regressed. A20 driver timing is unknown.

GLES emulates some texture views: sampling a D2 view of an array layer during mip
generation produced incorrect minified imagery in the initial fixture. Fixed
by copying base pixels into a reusable D2 staging texture, then copying each
finished mip back to its correct layer. This costs one extra full-level GPU copy
per array image. Startup validates both distinct array layers and all nine levels
before map uploads; exact CPU mip generation and separate-texture fallback remain.
The startup probe is excluded from the benchmark below and adds startup work.

NAT-10: GLES mip-generation intermediates and downstream bindings are retained
instead of creating eight textures for every image. Separate textures pool
87,380 bytes; arrays add the 262,144-byte D2 base staging texture, totalling
349,524 bytes. Queue submission ordering preserves scratch contents without
per-tile CPU fences. The Vulkan compute chain remains direct-to-destination.

NAT-09: The fixed-size index atlas now coalesces freed ranges and evicts only
least-recently-used patterns outside the current frame. Current-frame keys remain
protected. Exact overflow buffers handle working sets larger than the atlas.
A deterministic 30-frame churn trace reduces pattern uploads 45 -> 31 and hot
pattern uploads 15 -> 1 (1,080 -> 744 uploaded bytes for that tiny trace).
This is allocator work evidence, not a measured phone-frame improvement.

The requested automatic-cache slider covers 0–1000 **MiB**, in one-MiB steps.
Its total includes HTTP bytes plus lossless prepared terrain and finished imagery;
it preserves the existing free-storage cap and separate saved regions/ZIPs. The
setting persists and applies after restarting the app; the UI shows configured
and currently active limits. Zero disables automatic disk reads/writes and removes
those three disposable cache directories in the background at the next launch.
RAM/GPU residency remains managed independently. Restart is explicit because the
pinned public OkHttp Cache API cannot resize its open journal; switching a live
cache while transfers own responses would introduce avoidable races.

## Measured evidence

Five alternating trials per mode, using genuine LINZ image/DEM fixture payloads
with small diagnostic layer tags/height offsets, 16 visible tiles, 512² output.
Measurements include GPU drain, exclude startup and subsequent recycle checks.
Raw JSONL and exact scope: ../validation/comprehensive-audit/.

| Host median | Former separate | Pooled separate | Pooled arrays |
| --- | ---: | ---: | ---: |
| Vulkan elapsed including drain (ms) | 334.076 | 330.903 | 329.711 |
| GLES elapsed including drain (ms) | 139.671 | 128.088 | 148.593 |
| GLES upload-submit wall time (ms) | 19.759 | 12.342 | 13.478 |
| Map image/height texture creations | 32 | 32 | 4 |
| Terrain draw calls | 16 | 16 | 2 |
| Terrain texture binding changes | 32 | 32 | 4 |
| New GLES mip scratch textures after warmup | 128 | 0 | 0 |
| New GLES mip bind groups after warmup | 128 | 16 | 0 |

The elapsed samples are small and noisy; Vulkan differences do not establish a
meaningful speedup. API-submit wall time can include software-driver/GPU waits and
is not pure CPU time. Arrays use 6,657,408 bytes of allocated page payload in this
full-occupancy fixture; partial pages can use more bytes per live tile. Reusable
scratch is allocated during startup/warmup, not magically free. Array allocations
count page textures, not underlying driver heap allocations. The 87.5% reduction
in draw calls/allocation calls is real for this compatible fixture, not guaranteed
for arbitrary mixed LOD/topology/page layouts.

All RGBA pixels match within each API across the three modes. Each mode/trial
also performs 80 cache-churn uploads and checks the submitted first view and the
return view without a CPU fence before slot reuse. This exercises queue ordering,
eviction, slot recycling and empty-page release. Synthetic geometry GPU smoke
also passes on Vulkan automatic arrays and GLES automatic separate textures,
including exact grading, parent/child coverage, minified imagery and no skirts.
These are host correctness/work measurements, not A20 FPS or visual acceptance.

## Upstream research

Reviewed on 2026-10-30:
- MapLibre GL JS src/webgl/texture.ts: same-size updates use texSubImage2D;
  immutable texStorage2D allocates the full mip chain. src/render/painter.ts
  saveTileTexture/getTileTexture retains up to 50 reusable textures per size.
  Ridgeline carries over bounded resource reuse; arrays additionally share bindings.
  https://github.com/maplibre/maplibre-gl-js/blob/main/src/webgl/texture.ts
  https://github.com/maplibre/maplibre-gl-js/blob/main/src/render/painter.ts
- wgpu 30 Queue::write_texture/write_buffer: writes begin on the next submit,
  before submitted command buffers, after previously submitted work. This is why
  a shared mip scratch chain preserves an upload submission boundary. Combining
  all writes ahead of all scratch passes would overwrite earlier inputs.
  https://docs.rs/wgpu/30.0.0/wgpu/struct.Queue.html
- Pinned local wgpu 30 texture-view/array layer descriptors and WGSL array
  textureLoad/textureSampleGrad requirements informed layer-specific reads/writes.

The older API-26 back callback triggered new Android-36 lint despite an existing
API-33+ OnBackInvokedDispatcher callback. Documented/suppressed that specific
version-split warning; no navigation behavior was changed.
