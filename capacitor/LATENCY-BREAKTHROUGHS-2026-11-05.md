# Capacitor latency findings — 5 November 2026

The strongest general finding is a mismatch between retaining useful cached
pixels and retaining the downloads that refresh those pixels. Existing textures
can exempt off-screen transfers from the configured pending limit. Those transfers
still consume image-delivery slots. This is a concrete starvation mechanism,
independent of whether the Wi-Fi itself is slow.

This investigation targets **Capacitor 0.20.3 Audit**, source
`f92dade7466859078d0779b537f5ae830788e0e7`, APK SHA256
`cb22610e28528e9f7eaeb1a29faac6a5da166aa2f480ed6add695e068a2d66de`.
The refreshed `android-preview-download` branch still advertises the separate
0.18.0 Performance app; its README, validation and linked source were checked.
Nine relevant generated modules used by these experiments were compared byte
for byte with the published 0.20.3 APK. Native Rust is outside this investigation.

These are saved findings and executable research prototypes. **No production
renderer changes or new APK are included.** Measurements below distinguish real
provider data, controlled HTTP bandwidth, injected waits and geometry counts.
There was no handset or emulator run. Savings overlap and must not be added.

| Priority | Finding | Evidence | Practical scope |
| --- | --- | --- | --- |
| 1 | Obsolete downloads can retain all delivery capacity | 12 off-screen reloads survived a pending limit of zero; controlled queue readiness fell from 1,200 ms to 202 ms when obsolete transfers were cancelled | Broad loading latency during reloads, recovery and camera changes; transfer delay was injected |
| 2 | Survey mask inputs download unnecessary RGB data | Eight live PNG inputs: 485,822 bytes; WebP: 58,066 bytes; all alpha identical. Paced browser replay: 792 ms to 144 ms | About 648 ms saved in the controlled 5 Mbps fixture; applies to these boundary inputs |
| 3 | Full-detail policy deliberately waits after movement | Actual controller retained a two-level reduction for 350 ms after gesture end | Removing the focused-area hold can advance full-detail requests by 350 ms; display gain remains unmeasured |
| 4 | Uniform per-tile mesh density wastes geometry | Local adaptive prototype: 834,664 to 452,066 triangles across 27 cases, a 45.8% reduction | Rendering opportunity; no measured phone FPS or 200 ms GPU saving |
| 5 | Aggressive HLOD needs cache-aware admission | 25% fewer initial IDs, but 267 additional cold parents across 54 scenes | Faster initial focus is plausible; blind coarsening can increase total traffic |

**1. Preserve cached imagery without preserving obsolete transfers.**

In [raster-residency.js](../public/raster-residency.js), `_removeTile` parks
unfinished tiles if `tile.texture || tile.dem || pendingRoom()` is true.
Consequently, the limit controls newly unfinished tiles without resident data,
but does not bound reloads of tiles that already have textures/DEMs. Reducing
the allowance also does not actively cancel existing parked transfers.

A reproduction using the shipped native JavaScript TileManager and residency
adapter parked **12 reloading tiles with pendingLimit=0**, retained their original
texture identities, and issued zero aborts. Tile loading/GPU objects were
controlled fixtures; the manager/cache lifecycle was the actual shipped code.

A separate reproduction extracted the actual generated image admission queue.
It admitted 12 old transfers while stationary, then switched to the default
moving limit of eight and submitted four visible requests. All four waited.
With an injected 1,000 ms old-transfer delay and 200 ms new-transfer delay,
visible readiness was **1,200.4 ms**. Aborting obsolete subscriptions reduced it
to **202.5 ms**. This demonstrates capacity starvation, not a measured one-second
improvement on the user's phone. The two component reproductions do not claim
an end-to-end Android replay.

Dynamic priority changes reorder queued work; they do not free a slot held by
an admitted transfer. The Java foreground dispatcher is already uncapped, so
increasing its concurrency cannot fix this JavaScript admission problem.

Recommended implementation: separate decoded-data retention from transfer
retention; enforce the off-screen allowance for reloads too; preempt obsolete
subscribers when visible demand is blocked. Preserve nearly completed useful
work where measured remaining cost is small. Preserve valid textures/DEMs,
fallback provenance and reload-on-return. Shared raw inputs must remain alive
while another visible subscriber needs them. The real raster source's abort
state and cache adoption need integration checks before shipping this change.

Evidence: [obsolete-slots.json](validation/breakthrough-audit-2026-11-05/obsolete-slots.json).

**2. Reduce mask traffic without changing displayed photographs.**

[gap-imagery.js](../public/gap-imagery.js) requests full survey PNGs.
[regional-blend.js](../public/regional-blend.js) draws them into a temporary
canvas and uses only their alpha to veto Esri. Their colour channels are discarded.

Eight real requests near Rolling Pin and Cook, spanning zooms 13–17 and two
survey sources, returned HTTP 200 in both PNG and WebP. WebP reduced encoded
bytes by **88.0%**, with zero alpha differences over 524,288 pixels. Chromium
independently confirmed all eight alpha comparisons and **56 identical final
RGBA composites**, including cropped ancestor masks at several scale factors.
This replaces only mask-input encoding; displayed LINZ/Esri photo bytes need
not change. It must never be applied to RGB-encoded elevation tiles.

A shared, paced **5,000,000 bit/s HTTP fixture** loaded and composed the eight
inputs three times per format, alternating order. Median image-ready time was
**791.7 ms PNG versus 144.2 ms WebP**. Original photo/Esri inputs were already
resident; CDN, Android bridge, worker messaging and handset GPU costs were not
measured. Pure byte arithmetic predicts 684.4 ms less transfer work. Live cloud
request timings are recorded but are not used as a controlled speed comparison.

Upstream [LINZ's encoder](https://github.com/linz/basemaps/blob/master/packages/tiler-sharp/src/index.ts)
uses Sharp's WebP output; [Sharp documents alpha quality separately from RGB](https://sharp.pixelplumbing.com/api-output/#webp),
with alpha quality defaulting to 100. This supports the observed result, but
eight samples do not certify every survey, zoom or future provider revision.
Production qualification should cover the remaining surveys and retain a PNG
fallback for unsupported/failed WebP responses.

This requires a coordinated Java transport change: `TileAddress` currently
accepts only `.png` for survey inputs and reconstructs PNG network URLs/MIME.
A JavaScript-only suffix substitution would fail locally. Format identity and
cache/archive compatibility must be handled explicitly without changing existing
PNG saved-map identifiers or elevation decoding.

A further server-side opportunity exists: replacing RGB with a constant colour
and preserving exact alpha produced only **8,779 lossless PNG bytes** for these
same masks. No public mask-only endpoint was verified, so this is an upstream
service opportunity rather than an immediately usable query parameter.

Evidence: [provider comparisons](validation/breakthrough-audit-2026-11-05/survey-masks.json),
[browser pixels and paced timings](validation/breakthrough-audit-2026-11-05/mask-browser.json).

**3. Motion detail misses rotation and delays sharpening.**

The actual shipped [motion controller](../public/motion-detail.js) measures
centre translation and zoom changes. A replay of a 120-degree orbit with pitch
changes, fixed centre and fixed zoom reported zero movement speed and no detail
reduction. Terrain can change substantially on screen during this gesture.

Conversely, a fast translation reached a two-level reduction and kept it until
exactly **350 ms after moveend**. The focus-aware LOD result also has this
reduction subtracted, so focused full-detail requests are deliberately delayed.
Selection, network and rendering can add further latency; 350 ms is the policy
delay, not an established 350 ms image-ready improvement.

Recommended implementation: include bearing/pitch-driven screen displacement;
restore focused detail immediately at gesture end; progressively admit outer
refinement under visible-load pressure. Keep motion hysteresis to avoid thrashing.

Evidence: [motion-policy.json](validation/breakthrough-audit-2026-11-05/motion-policy.json).

**4. Adapt within terrain tiles rather than only choosing one grid per tile.**

Current adaptive terrain already selects 32/64/128 grids. Its error is the worst
cell error over the entire tile; a small sharp feature can force the full grid.
Simply enabling adaptive terrain again would add nothing.

The research prototype builds a local quadtree over the original 129×129 vertex
grid. It uses conservative affine residual ranges to decide where to refine,
joins unequal cells through shared boundary vertices, and retains all 512 fine
outer edges plus the original 1,024 skirt triangles. It uses the same 0.6-pixel
height-error budget with explicitly supplied projection sensitivities.

Nine real LINZ DEM parent-quarter fields at Cook, Rolling Pin and Wellington
were evaluated at three sensitivities each. Total triangles fell **45.8%**;
regional reductions were 29.3%, 48.3% and 64.6% respectively. Every resulting
surface had correct winding, manifold internal edges, complete area and matching
fine borders. 54,000 independent sampled surface comparisons stayed within the
chosen height-error bounds. Input borders were duplicated, without neighbouring
DEM backfill. These are geometry/error checks, not rendered-pixel equivalence.

An initial allocation-heavy prototype was too expensive. Typed error arrays
and a shared grid of edge vertices reduced warmed index construction to a
median **1.59 ms**, with a **24.42 ms maximum** among the recorded host cases.
Metadata construction had a 2.63 ms median. These costs belong in a worker,
with indices cached by DEM revision and error band, not rebuilt per frame.
The Float64 error hierarchy itself is 174,760 bytes per profile, and index data
ranged from 9,216 to 202,752 bytes. Production needs explicit byte budgets,
hysteresis, cancellation, native fallback and context-loss ownership handling.
Actual shader, depth/picking and device rendering validation remains necessary.
This does not reduce DEM download bytes and introduces no terrain disk cache.

[MARTINI/RTIN](https://github.com/mapbox/martini) provides related prior art for
local error-driven triangulation. The prototype uses square-cell residual
bounds and native-grid border compatibility rather than claiming a new algorithm.

Evidence: [local-terrain.json](validation/breakthrough-audit-2026-11-05/local-terrain.json).

**5. Aggressive HLOD: useful only with a traffic budget.**

Using the shipped engine, 54 explicit flat-envelope scenes covered three
locations, three zooms, three pitches and both phone modes at 412×820 CSS pixels.
They use an ideal centre hit and are not full alpine-terrain reproductions.

A one-level reduction outside every existing surface/pivot influence region
reduced initial IDs only **5.4%** and introduced 102 extra cold parents. Some
nadir scenes requested more tiles. A stronger temporary two-level reduction
outside a smaller central cone reduced initial IDs **25%** (1,012 to 760),
retained all protected central IDs, and restored the original ordered selection
when disabled. The best scene fell from 27 to 11 initial IDs.

However, fully refining all scenes would require **267 additional parent
requests**, a 26.4% request-count increase if those parents were cold. Counts
are not byte sizes. These experiments support cache-aware coarse covers and
focus-first admission, rather than an unconditional lower LOD setting.
[Cesium's streaming approach](https://cesium.com/blog/2019/05/07/faster-3d-tiles/)
combines request ordering, movement culling and peripheral deferral; its published
speedups are not Ridgeline benchmarks.

Evidence: [focus-hlod.json](validation/breakthrough-audit-2026-11-05/focus-hlod.json).

**Other pipeline areas reviewed.**

The image queue already separates delivery from decode and bounds staged bytes;
boundary output already transfers owned bitmaps; original HTTP inputs already
have a disk cache; decoded/GPU source caches already preserve useful covers;
direct imagery sampling and worker terrain analysis already exist. Topographic
lines and boundary lines are already inserted before symbols to share a drape.
Their presence alone is not proof that Direct mode is disabled. No new measured
200 ms gain was established in those paths, and route planning was excluded.

Offline reads use indexed storage, a negative lookup cache, WAL and independent
body reads. Saved-map writes/checkpoints could still contend for device storage
during background downloads, but no handset storage bottleneck was measured;
weakening durability is not recommended on this evidence.

Cronet/Android HttpEngine is a research option: [Android documents HTTP/3,
priorities and caching](https://developer.android.com/develop/connectivity/cronet).
The two cloud header observations did not advertise Alt-Svc, but a proxied
response cannot establish actual Android protocol support. No HTTP/3 latency
benefit was demonstrated, so a transport replacement ranks below the reproduced
slot and byte problems. Shorter timeouts alone would turn slow detail into more
failures. More slots cannot increase a saturated 5 Mbps link's capacity.

Recommended order: fix obsolete-transfer pressure handling; qualify and integrate
WebP mask transport; restore focused detail immediately and handle orbit motion;
then integrate bounded worker-generated local terrain indices. Use cache-aware
HLOD as an admission policy rather than paying for unnecessary cold parents.

All seven `capacitor/scripts/probe-*` scripts for this investigation and sanitized
JSON evidence are saved alongside this report. Raw provider images, sampled DEM
arrays and API keys are not committed. Provider fixture scripts take `--key-file`
and `--output`; the browser/terrain probes take the fixture output directory.
The motion, obsolete-slot and HLOD probes run from the repository root against
`capacitor/www` by default. Only these focused experiments and source/asset
checks were used; no broad application test suite or APK build was run.
