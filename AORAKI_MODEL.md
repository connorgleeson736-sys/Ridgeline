> Archived on 2026-10-15: the user retired all experimental terrain viewers. The active website is the main NZ planner only. See `BASE_VIEWER_STATUS.md`. These historical processing and recovery records are retained; they do not describe the current published UI.

# Aoraki — source-specific close-up terrain

The summit model uses the actual aerial imagery at each location. Generic cliff materials and the former grey coating are not used. **Enhanced landscape** combines offline AI image restoration with restrained terrain lighting, soft contact shading, clear atmospheric depth and a quiet sky backdrop. **AI-enhanced imagery** can be switched off to inspect the original photograph.

Open `/cook-model.html`, or use **Layers → Aoraki rendering → Enhanced landscape** in the existing planner. The original renderer, route planning, elevation profiles and navigation remain available. Experimental rendering is confined to the existing approximately **1.77 × 1.77 km summit patch**; this is not the whole national park.

## Full-region 4×-pixel imagery update — completed

The new detail tier covers the entire existing 1.77 km Alpine Model at a logical
24576 × 24576 pixels, versus 12288 × 12288 in the previous regular AI imagery:
2× width and height, and exactly 4× total core pixels. This is direct native 4×
Real-ESRGAN inference from the authentic 6144² source, avoiding cascaded inference.
The recovered original encoded source exactly matches SHA
`0f0195e25e13376107f6d9021dffe3b499dd65f82f9139642817e5428f83977d`.

The output is split into 1024 pages, each 1024² with a 768² core and 128 px gutters.
The existing regular imagery remains as a smooth lower-detail fallback. The viewer
loads only 3 or 5 nearby detail pages; dimensions and GPU allocation per page are
unchanged from the former 16 summit-only close-up pages. Higher detail begins
prefetching at zoom 19.2. Existing mipmaps, anisotropic filtering and 900 ms fades
remain. The entire set is streamed rather than downloaded at once.

Preparation uses the existing pinned Real-ESRGAN weights, CPU FP32 and 40 source
pixels of context. The input-context check reports 0.0 maximum float difference.
Source colours and 65% of the high-frequency neural residual are retained. No
generic cliff material, geographic reprojection change or DEM modification is
introduced. AI detail is visual only and does not recover unseen cliff photographs.

Persistence uses the separate immutable run
`050b89796ef2ecda13d0e08d3929a5b71f956beabbc24a065e5e692683fc403d`,
with a verified source checkpoint and independently durable 64-page batches.
Scripts: `tools/prepare-alpine-upscale-source.py` and
`tools/upscale-alpine-imagery.py`. Both accept hidden-stdin JSON containing fresh
`base`, `token`, `root` and `checkout`; source preparation also takes `native_root`,
and inference takes `weights`. The replaceable processing root is
`/workspace/alpine-imagery-run`; weights are under `/workspace/cook-upscale/weights`.
Recipe- and hash-valid batches and parent receipts resume without unnecessary
inference. Final assets copy to `model/upscale` only after the complete provenance
manifest is externally durable. For a lost local root, first call
`Remote(base, token).restore_run(run, new_root)` from
`tools.lidar_pipeline.remote`, then rerun inference against that restored root.
Retrieve the two pinned official weights before inference; tokens stay in memory.

All 1024 pages are generated and externally durable. Encoded imagery totals
77,626,588 bytes (77.63 MB); the run took 1064 seconds including
incremental external checkpointing. Every file hash, dimension and grid location
passes validation, with complete 32 × 32 page coverage. All 1,984 neighbouring
overlap pairs pass: mean RGB difference 0.389/255, largest pair mean
1.155/255 after lossy WebP encoding. The authoritative DEM hash is
unchanged. The controller loads the actual new page paths at extreme zoom and
passes source/AI switching, fallback, refinement and cleanup checks. Regional
preservation checks also pass. Results are saved in
`ALPINE_UPSCALE_VALIDATION.json`; full browser and device FPS QA is unavailable.
The completed source and assets are packaged and published through Sites. The production build omits 31,295,990 bytes of unused preparation textures and
Node-only Cesium test bundles and disabled Cesium sky/water effects; these remain in Git for reproduction and checks.
The browser's Cesium bundle, original imagery, regular expanded imagery, all new
detail pages and terrain geometry remain. Production output is 264,993,864 bytes,
with a further 3 MiB reserved for archive metadata under the host's 256 MiB limit. After every framework build,
run `node scripts/prune-static-build.mjs` before packaging. The script checks all
active image tiers and rejects an oversized output; use it as the second ordered
command after the standard Sites build helper.

This update concerns the original Alpine Model, not the original-point
LiDAR reconstruction pilot or national-map terrain. The following sections
record retained baseline components; the new full-region detail tier replaces
the earlier summit-only 16-page limitation.

## Authentic geography

The native source is **Canterbury LiDAR 1m DEM (2020–2025)**, directly read from LINZ EPSG:2193 COGs `BX15_10000_0305` and `BX15_10000_0405`. Both files match STAC checksums. Every one of the 4,198,401 display-grid nodes has source coverage. **NZVD2016** heights are retained. Bilinear reprojection to the approximately 0.865 m Mercator display grid does not add finer measured resolution. The exact summit flight date is not established within the collection period.

The DEM SHA256 remains `00e4b14878f9f9c78afa04c5ead2d8970c51844567ce5f6be090dbecbac25f1d`. The authoritative DEM remains unchanged. AI enhancement remains image-only; optional visual DEM interpolation affects display meshes, never route elevations or profiles. A 1 m heightfield cannot represent overhangs or establish per-cliff survey accuracy.

Imagery is genuine **Canterbury 0.3 m Rural Aerial Photos (2015–2016)**. Four source COGs are hash-verified and cover the entire patch. Newer West Coast 0.2 m imagery does not cover it. The photographs do not establish current snow conditions.

The earlier API-mosaic comparison is retained at `/cook-comparison.html`. The direct-source grid differs from that old snapshot by median absolute 0.0637 m, RMS 0.2293 m, maximum 17.4617 m. Main planner profiles and route draping retain their original live LINZ pipeline. The regional overlay composites independently of native depth, so boundary views and original route draping can differ slightly from the direct-source model; the separate studio isolates the patch for comparison.

Elevation attribution: LINZ / Environment Canterbury / Aerial Surveys Ltd. Imagery: LINZ / Canterbury Aerial Imagery Consortium / AAM NZ. CC BY 4.0. Public elevation, imagery and AI provenance JSONs accompany the assets.

## AI enhancement actually implemented

All **256 imagery tiles** have been processed through **Real-ESRGAN**, not merely resized or sharpened with a conventional filter. This is offline preparation: no neural model, AI API call or inference workload runs in the browser.

| Property | Implementation |
| --- | --- |
| Source | Lossless 6144 × 6144 rendition of the authentic 0.3 m imagery |
| Network | `realesr-general-x4v3` with the matching weak-denoise model |
| Architecture | SRVGGNetCompact, 64 features, 32 body convolutions, PReLU, native 4× PixelShuffle |
| Code | Official Real-ESRGAN commit `a4abfb2979a7bbff3f69f58f58ae324608821e27` |
| Weights | Official release v0.2.5.0; both SHA256 values pinned in the preparation script |
| Denoising | Conservative DNI strength 0.25 |
| Inference | CPU FP32, evaluation mode and inference mode; 40 source pixels of context on each tile edge |
| Served scale | Native 4× neural output reduced to 2× with Lanczos |
| Source preservation | Broad source colours retained; 65% of the neural high-frequency residual applied |
| Tiles | 776 × 776 pixels, including four output pixels of gutter on each side |
| Transfer | Entire optional fine-image set: 17.62 MB; only nearby tiles load |
| Preparation time | Approximately 392 seconds in this environment |

The independent tiled/full-image inference check had maximum float difference **0.0** for the tested interior crop. All served tile hashes and dimensions pass validation. The overlapping gutters differ by mean **0.79/255** RGB after conventional lossy WebP compression; the largest pair mean is **1.53/255**.

Enhanced pixels are visual restoration, **not new measured resolution or recovered geographic facts**. The aerial photograph's horizontal georeferencing remains fixed. AI and filtering cannot supply unseen vertical cliff photographs; stretched imagery on near-vertical faces remains a limitation of orthophotography. Verified face-specific photographs would require oblique imagery or photogrammetry. The rejected procedural cliff replacement has not been reintroduced.

## Source-specific cliff reconstruction

The user's reference shows long vertical bands from top-down aerial imagery stretched over steep geometry. Simply increasing image dimensions cannot remove that projection distortion. This update reconstructs **visual fine detail from the actual upscaled imagery around each geographic point**, using the measured local terrain normal and small overlapping surface patches.

Two altitude-anchored patches are blended over a 6 m vertical interval. Their tangent-plane metric traverses approximately one metre of image detail per metre up the face instead of stretching a few horizontal pixels over the whole cliff. Each sample remains within **6 m in plan** of the original geographic point. A colour guide from the authentic overview stays at the original coordinates; only local fine detail is reprojected. Contrast correction compensates for overlapping-patch averaging. Source and geometric normals identify steep faces, while flatter terrain keeps ordinary aerial projection. There is **no shared rock texture, material library, procedural cliff colour or generated cliff image** in the current shader.

This is source-conditioned appearance reconstruction, not recovery of the actual unseen vertical face. Fine details can move within their small source neighbourhood and the broad colour guide is filtered; it does not establish exact cliff markings. Original and Adaptive remain selectable references. **Repair stretched cliffs** switches the reconstruction off at the same view. Turning AI imagery off also disables reconstruction. All geography, elevation calculations and route profiles retain their original data.

### Approaches considered

| Approach | Decision |
| --- | --- |
| Resize or sharpen the original overlay | Insufficient: projection still stretches pixels vertically |
| Generic rock/ice triplanar material | Rejected, as requested |
| Slope-aware local image reconstruction | Implemented, using each place's real enhanced imagery |
| Stronger source-specific neural upscaling | Implemented offline for the close-up inspection area |
| Oblique imagery / photogrammetry | Needed for verified face-specific photographs; not present in the current source data |
| Diffusion-generated cliff appearance | Not used: it would invent fine geographic markings |

### Stronger close-up imagery

All 256 source-specific 2× AI tiles now have **128-pixel gutters built from adjacent real enhanced tiles**. Each served tile is 1024²: a 768² core with approximately 18.45 m of source context on each side. The entire optional expanded set is 29.45 MB. Recompression changes sampled core colours by at most mean 0.84/255; it is not a new cliff material.

**Sixteen quarter-tiles** around source tiles 137, 138, 153 and 154 have additionally been processed through native **4× Real-ESRGAN**, directly from the same hash-verified 6144² authentic photo. This covers the approximately 221 × 221 m close-up inspection area. No 2× output is merely resized to claim 4×. The same conservative DNI and source-preserving residual process is used. A quarter-tile covers 64 source-grid cells, with a 768² core and 128-pixel gutters; output is again 1024². The complete optional 4× set is **2.22 MB**, generated in approximately 16 seconds here. Source photography remains native 0.3 m: enhanced pixel spacing is appearance, not new measured resolution. The rest of the summit patch retains 2× enhancement.

Fine textures fade from their **currently displayed parent image**, using the correct UV mapping for both images. Reconstructed detail follows the same fade. Coverage guards fall back to the parent wherever a triangle extends beyond a fine tile's supported neighbourhood. Only one image/mipmap upload occurs per frame. Six nearby expanded tiles are prioritised with a 40 MiB cache budget; at most five 4× quarter-tiles are active, or three on reported low-memory devices. Mipmaps and supported anisotropic filtering up to 16× limit shimmering.

The model waits for its source camera DEM and overview before its first reveal. Mesh LOD changes use 700 ms morphs. LOD hysteresis was corrected in both directions: zooming in can no longer freeze at a coarse mesh, and zooming out can release unnecessary detail.

## Choose close-up DEM refinement

The studio now offers **Off, 2×, 4× and 8×** at the same camera. The 4× option is the default. These labels describe interpolation density relative to the approximately 0.865 m display grid; authoritative measurements remain the native 1 m DEM.

| Choice | Nominal dense-grid spacing nearby | Subdivision passes | Plan-radius of refinement |
| --- | --- | --- | --- |
| Off | Prepared measured mesh | None | None |
| 2× — gentle | Approximately 0.43 m | One | Approximately 17.3 m |
| 4× — balanced | Approximately 0.22 m | Two | Approximately 17.3 m |
| 8× — finest | Approximately 0.11 m | Three | Approximately 10.4 m |

Refinement runs in the existing worker on at most two nearby tiles. The finest mode uses a smaller area to limit its triangle cost. Bicubic interpolation is bounded to **0.30 m total** from the measured triangle surface in every mode; the bound is divided across the selected passes. Measured vertices and common tile edges remain exact. Refined meshes preserve the four cullable triangle ranges, and the same 4× imagery remains usable on them. Mode changes morph the outgoing refinement back before introducing the replacement, avoiding an abrupt geometry swap.

At the fixed ~26 m native-render close-up, submitted triangles were 1,152,341 with refinement off, 1,159,071 at 2×, 1,185,649 at 4× and 1,203,545 at 8×. The increase is approximately 0.58%, 2.89% and 4.44% respectively in this scene. These are geometry counts, not device FPS measurements.

These options create a smoother visual model, not finer measured terrain or generated geological features. They apply in the Terrain Studio only; planner route elevations and profiles do not use them. Controller tests exercise all four actual choices and require real worker-produced geometry plus loaded 4× imagery. Subdivision tests check each offered density, boundaries, winding, index groups, finite heights and bounded offsets.

## Rendering and optimisation

The five source-checked RTIN levels remain at 8, 2, 0.5, 0.1 and 0.025 m simplification error. These are rendering error limits, not survey accuracy. Every tile boundary retains the same measured nodes, with 700 ms smoothstep transitions through the preceding terrain surface.

Screen-space selection still uses projected geometric error, camera distance, six-plane frustum culling and hysteresis. Each indexed tile now has **four independently bounded triangle ranges**. Bounds include both source and morph-parent heights, with conservative conformal-height padding. This lets the renderer discard offscreen sections without reducing mesh detail. Tiles are skipped only when all prepared levels are outside the refined bounds.

Meshoptimizer remains lossless. Vertices are reordered, GPU indices use 16 bits, and immutable buffers/VAOs avoid repeated geometry uploads. SHA-based URL versions prevent stale cached geometry from failing against a new manifest. The worker CPU cache stays bounded to 24 MiB; non-visible geometry is evicted above 32 MiB, while visible geometry can exceed the limit to preserve detail.

The whole-model shader retains source colour and measured normals. The shader samples local source photographs, not generic materials. The steady steep-face path uses nine texture lookups; parent-detail fades can temporarily use thirteen. Flatter terrain retains the lighter source-photo path. Gentle directional light respects the photograph's existing shadows instead of heavily darkening them again. Snow colour is retained, with restrained dielectric sheen and subtle ambient/contact shading.

Expanded 2× imagery prioritises six visible tiles by 3D camera proximity, with recent offscreen retention under a 40 MiB budget. Up to five additional 4× quarter-tiles serve extreme close-ups, reduced to three on reported low-memory devices. Source imagery uses up to 36 smaller tiles. Two image jobs run concurrently, and at most one texture/mipmap upload occurs per rendered frame. Inactive views pause work. Hardware-supported anisotropic filtering remains capped at 16×. Original imagery is retained as an immediate selectable reference.

Clustering slightly increases the complete encoded mesh set from approximately **95.5 MB to 102.1 MB** because triangle ordering now preserves draw ranges. It improves submitted geometry but is not a download-size improvement. The complete prepared set is streamed and never loaded in full.

## Verification and comparison

All **1,280** mesh variants pass checksum, source-vertex and shared-boundary validation. **8,679,310** decoded vertices retain exact source Float32 heights. Every cluster range covers the expected indices and encloses both source and morph heights; representative meshes are independently checked against the full source grid. AI tile provenance, dimensions, original-source hash and gutter tests pass.

Controller tests cover actual worker decode, AI/source switching, original fallback, deep zoom, same-view benchmarking, cancellation and cleanup. Regional tests verify native terrain preservation, framebuffer restoration and inactivity outside Aoraki. Existing route/profile and main planner tests pass.

Native OpenGL ES checks render the actual shipped shaders with the authentic source data and bundled MapLibre camera math, including the overview and an approximately 26 m close-up. These are software shader/geometry validations, **not laptop or Chromebook FPS measurements**. Full browser QA is unavailable in this environment.

| Fixed-camera scene, 960 × 640, 1.5× LOD target | Previous submitted triangles | Current submitted triangles | Reduction |
| --- | ---: | ---: | ---: |
| Summit, zoom 15.4 / 58° tilt / ~1,259 m range | 2,197,892 | 2,069,979 | 5.8% |
| Rock face, zoom 21 / 72° tilt / ~26 m range | 1,317,244 | 1,152,341 | 12.5% |

These baseline counts have visual DEM refinement disabled and preserve the same error target and source heights. They are not claimed FPS gains. Use the built-in three-way benchmark to measure original, adaptive and enhanced rendering on your device. It holds camera/pixel ratio fixed, settles loading, warms 15 frames and measures 60 rendered intervals per view. Imagery and material detail differ; display refresh can limit timings. The overlay exposes submitted triangles, active-frame cadence, known model GPU allocations, browser heap when exposed, payload/queue statistics and supported GPU timer measurements.

AI detail adds some image bandwidth and texture memory, so smoother FPS on every Chromebook has not been established. The original renderer and lighter screen-space setting remain available. The source DEM and authentic imagery remain separate from enhanced appearance.

## Remaining investigations

| Technique | Current decision |
| --- | --- |
| AI imagery enhancement | Implemented offline, with the original source toggle |
| Cliff detail | Implemented using each location’s actual upscaled imagery and measured slope; no generic material |
| Close-up DEM refinement | Implemented as bounded bicubic subdivision, not neural elevation generation; visual-only toggle |
| FSR 1 | Not enabled. A WebGL2 EASU + RCAS port and real device GPU benchmarks are needed to establish whether two extra passes pay off |
| Cesium quantized-mesh | Still an architectural option; current streaming uses lossless meshoptimizer without replacing the existing engine |

Primary references: [LINZ DEM](https://data.linz.govt.nz/layer/111133-canterbury-lidar-1m-dem-2020-2025/), [imagery collection](https://nz-imagery.s3-ap-southeast-2.amazonaws.com/canterbury/canterbury_2015-2016_0.3m/rgb/2193/collection.json), [Real-ESRGAN inference](https://github.com/xinntao/Real-ESRGAN/blob/master/inference_realesrgan.py), [official architecture](https://github.com/xinntao/Real-ESRGAN/blob/master/realesrgan/archs/srvgg_arch.py), [meshoptimizer](https://github.com/zeux/meshoptimizer/blob/master/js/README.md), [AMD FSR 1](https://gpuopen.com/fidelityfx-superresolution/).

## Reproduce

Offline preparation requires Python with NumPy, Pillow, SciPy, rasterio, pyproj and a CPU PyTorch installation. Real-ESRGAN's two hash-pinned weight files come from the official v0.2.5.0 release; weights are not shipped to browsers. Vendored architecture code includes the BSD-3-Clause licence. There are no new frontend dependencies or runtime AI costs.

```sh
python tools/prepare-cook-elevation.py /tmp/cook-source
python tools/prepare-cook-imagery.py /tmp/cook-source
node tools/precompute-cook.mjs /tmp/cook-source/cook-authentic-dem.f32 dist/assets/mt-cook/model/region.json
python tools/cook-materials.py /tmp/cook-source/cook-authentic-dem.f32 dist/assets/mt-cook/model/region.json /tmp/cook-source/cook-authentic-aerial-6144.webp
python tools/enhance-cook-imagery.py /tmp/cook-source/cook-authentic-aerial-6144.webp /tmp/real-esrgan-weights
python checks/cook-ai-assets.py
node checks/cook-model-data.cjs
python tools/prepare-cook-local-imagery.py
python tools/enhance-cook-close-imagery.py /tmp/cook-source/cook-authentic-aerial-6144.webp /tmp/real-esrgan-weights
python checks/cook-local-assets.py
node checks/cook-lod.cjs
node checks/cook-refine.cjs
node checks/cook-model-controller.cjs
node checks/cook-regional.cjs
```
