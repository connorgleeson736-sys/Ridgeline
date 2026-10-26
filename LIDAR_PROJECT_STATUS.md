> Archived on 2026-10-15: the user retired all experimental terrain viewers. The active website is the main NZ planner only. See `BASE_VIEWER_STATUS.md`. These historical processing and recovery records are retained; they do not describe the current published UI.

# Ridgeline original-point LiDAR — current status

Updated2026-10-14 UTC. **Working100m pilot; full2km production pipeline incomplete.**
Historical experiments and prior recovery records: `tools/lidar_pipeline/HISTORY_2026-10-14.md`.

## Regional continuation —16 surfaces and24 neighbour checks durable

Fresh-runtime recovery restored76 selected regional checkpoints with complete
journal/archive/artifact hashes, without raw LAZ downloads. Inventory proof:
`events/recovery-inventory-73018755c7c7922e/17edcc1637c04739296226c837161ca5b1a193c1e01831844de5c9acbaa22ff5`.
All18 native-photo checkpoints and the coordinate frame were also restored.

All20 per-source capacity counts plus `regional-preflight-r2` are durable.
65,476,037 selected records; maximum inclusive core377,932 /8m halo517,618;
all400 native jobs below the1.2M cap, zero dense jobs, no source sampling.
Receipt: `tools/lidar_pipeline/REGIONAL_CAPACITY-2026-10-14.json`.

All four legacy summit child2m LODs are now durable:28,098 /49,139 /54,974 /
26,615 triangles (sum158,826). The merged200m parent's136,154 triangles save
22,672 (14.2747%) versus the independent children, with both passing the same
continuous symmetric2m error limit. Exact union/source witnesses are checked.
`regional-parent-comparison-2m` is externally durable; committed benchmark JSON
records all source signatures and limits. No browser speed, GPU, transfer-size
or end-to-end preprocessing-time benefit is claimed.

Exact native toolchain task `native-toolchain-9a26235b2e48fbd8` is durable.
After restoring into a fresh empty directory its real planar binary tests pass
3/3. Full signature9a26235b2e48fbd8c2a34eb67850c0a482f829d37f5c715d63a2df0b9328248e;
local-ballmerge SHA66326eb51a8181701bf725e28ddde09e77d92028405c16edc5217b157a2f69d6.
Reuse the exact saved binary for r2 recipes; do not rebuild a different generation.

`regional_batch` saved the full400-patch centre-first plan, then completed16
patches covering400x400m (4% of requested area):11,092,985 finest triangles.
All16 surfaces are independently R2-durable;384 remain pending. The invocation
stopped cleanly. `limit:0` independently revalidated every completed recipe
against current audited source/partition/native/code generations and reused all
16, executing zero new jobs. Changed source generations fail closed. Crash/resume
and generation-change rejection tests pass. No geometry sampling/downsampling.

The four overlapping legacy summit meshes and r2 vertices/faces/source-point
arrays are exactly identical, confirming3D compatibility of the native recovery.
All24 adjacent crossing-facet comparisons pass and are independently durable,
with an additional durable16-patch scope summary. This proves finest measured
triangle agreement only; source coverage, manifold sheets, coplanar ownership
and mixed-LOD rendered seams remain independent production gates.

Native imagery export now works end-to-end: the200m coarse parent uses671x671
exact native0.3m NZTM pixels from four durable source windows (no resampling/AI or
extrapolation). Compressed GLB6,580,464B; asset SHA
483d11d60e92d308bf887a34e933faf8de0c3f83c555e2be75be84689f347920.
Actual vendored WASM decoder and compressed/decoded Khronos validation pass with
zero errors/warnings. Full-precision transformed reference error bound2.005026920m
explicitly includes0.000061231m reference Float32 rounding; separate comparison
certificate remains2.004965689m. Survey/datum uncertainty is separate.
Receipt: `NATIVE_PARENT_EXPORT-2026-10-14.json`. Source image pixels remain
plan-view samples; hidden cliffs have no invented photographic evidence.
The new regional export is NOT wired into the public viewer yet; live100m pilot,
planner, routes/profiles and fallback remain unchanged.

Current replaceable runtime: `/workspace/ridgeline-env/bin/python`, data root
`/workspace/ridgeline-run`, native build `/workspace/ridgeline-native`; canonical
saved executables are inside data/toolchains/<full signature>. No Colab runtime.
Fresh empty-directory recovery independently verified four new checkpoints
(finest0231, native GLB, complete capacity audit and parent comparison),13,782,677
artifact bytes matching originals. External proof event:
`events/verified-regional-recovery-33592ca741e2273b/c2457885df2eca92f8f46a68ed8111bb9127dfae946e42c09c474e8f5f764b42`.
Committed receipts include REGIONAL_SEAMS and REGIONAL_RECOVERY_PROOF JSON.
No processing jobs are intentionally left running. Full2km production release
is incomplete; remaining384 surfaces/LODs, sheet/ownership validation, regional
streaming/texturing and actual browser/device measurements are outstanding.

Pinned dependencies and framework build pass. Recovery/capacity/union/native
pixel/dispatcher tests and original viewer/controller regressions pass.

## Current result

`/lidar-summit.html` is a separate Cook-only original-point pilot, linked from the
existing planner layers and mountain studio. It preserves all815,390 reconstructed
triangles of summit patch0189 in64 spatial clusters. Existing planner, routes,
profiles, studio and original comparison renderers are preserved. The visible
pilot is nominally100x100m, NOT the complete2x2km region. No generic cliff material.

| Level | Triangles | GLB bytes | Bound to full-precision reconstructed geometry |
| --- | ---: | ---: | ---: |
| Coarse |28,098|1,310,724|2.004764023m|
| Intermediate |72,961|3,184,872|0.602258934m|
| Finest64 clusters |815,390|30,982,328|0.000484020m display rounding|

Equivalent finest Float32 exports total50,758,780B; compact GLBs are38.96% smaller.
Fine attributes use20B/vertex vs32B (37.5% less); total fine geometry GPU buffers
58,680,280B, excluding textures/engine memory. Shared positions use one binary
0.48828125mm grid; decoded shared coordinates align across chunk origins. Source
NPZs remain unchanged. Encoding precision is NOT survey/geological accuracy.
Parent SSE errors explicitly add full-precision reference conversion rounding
(max0.000061189m) and a1e-5m margin. See `PILOT_BENCHMARKS-2026-10-14.json`.

All66 GLBs and the explicit3D Tiles1.1 REPLACE hierarchy are R2-durable. The
corrected hierarchy asset SHA is
`fb0318fe588e6a7099b5cb1298acafaa3e48f38b399d71d33031c3772f6e46b7`.
Asset path `/api/lidar/files/<sha>.json`; client contains no service credential.
The configuration is independently gated and saved by `pilot_release.py` before
copying to public assets. CPU raycasting selects an actual71.2deg facet for the
28.6m close-up camera, with27.5m nearest-surface clearance and checked visibility.
This does not establish a solid geological surface or mountaineering safety.

Viewer: native Cesium orbit/zoom, adaptive/coarse/fine choices,128+64MiB cache,
frustum culling, worker decoding and parent-held replacement. Full processing-patch
levels switch together; spatial mixed-LOD seams/predictive motion loading remain
future work. Idle rendering pauses; benchmark cancels on movement, hidden tabs,
resize or errors. Overlay shows actual visible content triangles, estimated tile
GPU memory, loading and rendered frame intervals. Benchmark JSON export measures
90 intervals each at overview and close-up when run by a real browser.

## Durable inputs and imagery

Exact ROI `[1368289,5168127,1370289,5170127]`, EPSG2193+NZVD2016(7839).
All20 original Canterbury LAZ crops:222,276,698 raw returns, immutable externally.
All20 selected partitions:65,476,037 returns. Source audits, classifications and
withheld/noise exclusions are preserved; no raw observations were overwritten.
Source-only reconstruction recovery uses `source-audit`+selected shards without
re-downloading raw LAZ. Source SHA correspondence is mandatory.

Four summit finest meshes0189/0190/0209/0210 are saved; all four neighbouring
original crossing-facet sets match exactly. The reconstruction remains a
nonmanifold triangle union. AFS failed seams and must not be bulk released.
Local BallMerge criterion implementation is independent of research-only code.

All16 native500m photo windows covering the FULL2km ROI are R2-durable:74,244,285B
of lossless PNG,44,448,889 unique opaque0.3m pixels. Audit proves complete union
and exact agreement in24 overlaps/26,684 common pixels. No reprojection, AI or
extrapolation. Pinned original Canterbury2015–2016 COG/STAC identities; partial
reads do NOT claim a recomputed entire source COG hash. Output SHA readback is
verified. `NATIVE_PHOTO_COVERAGE-2026-10-14.json` preserves receipts/coverage.
The current pilot GLBs still use the previous same-place source photo crops;
expanded native photo integration into regional exports is pending. Top-down
imagery cannot supply unobserved cliff photography.

## Persistence, source and deployment

- Existing project: `appgprj_6acc83afb6588191aff43dc542d98b5f`.
- Owner-private Site: https://ridgeline-nz.connorgleeson736.chatgpt.site
- Durable API: same origin `/api/lidar/`.
- Immutable run: `f899852804c645c7c8834d25a9642ce1e55000f19ad15138be5b5bbb777b142e`.
- External recovery note: https://docs.google.com/document/d/1yXm6dNZL8kgImmrJaZl4R-2ApB8YMBvcvT0uQ6YfdpU
- Recovery folder: https://drive.google.com/drive/folders/1eY2tUhH2WGGSrQxWxRcv2IVc6zDgso4W
- Authoritative checkout: `/workspace/sites/ridgeline-lidar-recovery`.
- Current replaceable runtime: `/workspace/ridgeline-env/bin/python`;
  data `/workspace/ridgeline-run`.

Source milestones51f1a912 and73eb8283 are externally pushed; subsequent pilot
publication is recorded by native Sites deployment results and the external note.
Version16/source4546bdf5 deploymentappgdep_6acf2d6b1794819187ed01e820bf1b25 previously
succeeded. The pilot publication above succeeded; later processing-only milestones preserve it. Preserve audience. Use current remote Git HEAD, never a stale checkout.

State uses atomic fsync/replace writes, per-task locks, hash-keyed recipes,
immutable archives/journals, validated staged->durable barriers and resumable
range downloads. Complete file/chunk hashes are checked server-side before
manifest acceptance. Histories remain per-task; cached compact progress prevents
quadratic manifests. Selected local counts are a recovery subset and must never be presented as whole-region completion. No Colab runtime exists.

## Exact recovery

1. Open the SAME existing Site with Sites source helper and fresh credential;
   recover current remote source. Do not create a replacement project.
2. Install Python3.12+ requirements with `CXX=g++ CC=gcc`; install pinned validator
   with `npm ci --prefix tools/lidar_pipeline`. No native build needed for pilot
   restore/export; full reconstruction uses `build_native`.
3. Start `python -m tools.lidar_pipeline.runner` in a terminal with echo disabled.
   Send ONE hidden-stdin JSON with `base`,fresh ephemeral `token`,new absolute
   `root`, `operation:"restore"`, and the exact `run`/`only` list from
   `PILOT_RECOVERY.json` (92 tasks). Never persist credentials. Restore verifies
   every journal/archive/artifact and writes commit records last. No LiDAR
   ingestion or reconstruction needs repeating for the saved pilot.
4. `operation:"tile-assets",tile:189,delta:1.35,imagery:"source",workers:3`
   resumes64 fine chunks plus2m/.6m parents. SHA-valid completed work is reused.
   Then `python -m tools.lidar_pipeline.pilot_release` accepts hidden-stdin
   `base`,`token`,`root`,`checkout`; it validates and saves config/benchmarks.
5. Native images: `python -m tools.lidar_pipeline.source_photos` accepts hidden
   `base`,`token`,`root`,optional `windows:[0..15]`; use disjoint bounded jobs and
   `GDAL_CACHEMAX=32`. `photo_coverage` audits the full union/shared pixels.
6. Use Sites helper for source milestones/build/package/push; save/deploy the
   exact SHA/archive privately and verify terminal deployment result. Update
   this status and external note. Raw sources/immutable journals remain intact.

7. Regional restore: use `REGIONAL_RECOVERY.json` prefixes with
   `python -m tools.lidar_pipeline.recovery_inventory`, hidden base/token/root/run,
   restore:true. This discovers durable names instead of guessing unfinished jobs.
   Restore native-toolchain-9a26235b2e48fbd8 and all current r2 surfaces/plan.
   Run `regional_batch` with that native_task and limit:0 to validate existing
   source generations before continuing; then limit:16,max_seconds:1800 for the
   next bounded invocation. Pass fresh authorization only through hidden stdin.
   `regional_seams` accepts hidden base/token/root/explicit tiles list; it saves
   each pair independently and reuses verified completed comparisons.

## Tests and remaining gates

Passed: actual vendored meshopt decoder and decoded-core Khronos checks for every
GLB; geometry/coverage/quantization; coordinate/geoid/frame; hierarchy error
accounting; checkpoint/recovery/compact manifests; native photo pixel/alpha/grid
and real full-ROI overlap audit; camera visibility/clearance; controller idle,
cancellation, duplicate-loop, failure and disposal checks; original regional and
studio controller regressions; framework build. These are NOT browser/GPU tests.
No control-browser capability is installed, so actual visual/device QA was skipped.

Next: real browser inspection and device FPS/cold-warm latency; regional parents
that release internal divider constraints; mixed-LOD seams and coplanar ownership;
remaining400-patch reconstruction/completeness and dense/degenerate jobs; expanded
native photo integration; measured adaptive budgets/predictive streaming;
unattended external processing with renewable external authorization. No paid
infrastructure was provisioned. This project is NOT complete or production-ready.
