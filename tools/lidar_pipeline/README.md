# Measured 3D terrain pipeline

**Development in progress. Not a completed production terrain release.**

## Original-point pilot milestone

`/lidar-summit.html` is a separate100x100m summit pilot, with an independently
saved3D Tiles1.1 hierarchy:28,098 coarse /72,961 intermediate /815,390 finest
triangles.64 fine clusters total30,982,328B vs50,758,780B for equivalent Float32
exports. Full-precision reconstruction display bound<=0.484020mm; this is NOT
survey accuracy. Source arrays remain unchanged. Parent errors include the
reference Float32 rounding contribution. See `PILOT_BENCHMARKS-2026-10-14.json`.

The new viewer has no service credentials or external terrain subscription.
It uses the existing private same-origin asset API, bounded cache, parent-held
REPLACE refinement, frustum culling and Cesium worker decoding. Idle rendering
pauses. Its device benchmark collects actual rendered frame intervals when run;
automated controller tests do not establish WebGL/FPS performance. The close-up
preset is derived from an actual steep facet and checked with finest-mesh raycasts
for visibility/clearance. It is not a geological solid or mountaineering guarantee.
Original planner, profiles, studio and render comparison remain available.

Fast recovery: use the exact `run` and `only` list in `PILOT_RECOVERY.json` with
runner `restore`. Restore also retrieves the config. Then `tile-assets` with
`tile:189,delta:1.35,imagery:"source",workers:3` resumes/rebuilds the hierarchy;
completed SHA-valid leaves are reused. It requires both durable2m and0.6m LODs.
`python -m tools.lidar_pipeline.pilot_release` accepts hidden-stdin `base`,fresh
`token`,`root`,`checkout` and independently gates/saves config+benchmarks before
copying the small config into the existing public assets. Native compilation is
NOT required to recover/display this pilot. Full-region reconstruction still is.

Native imagery: `python -m tools.lidar_pipeline.source_photos` accepts hidden-stdin
`base`,`token`,`root`,optional `windows:[0..15]`. Four concurrent bounded invocations
can handle disjoint500m windows; `GDAL_CACHEMAX=32` bounds each GDAL block cache.
All16 native0.3m windows for the full2km ROI are saved. `photo_coverage` verifies
their union and exact overlapping source pixels. No resampling or AI is applied;
partial COG reads pin advertised source hashes without falsely claiming a
recomputed whole-file hash. The pilot GLBs still use the prior same-place source
photo crops; wiring the expanded native imagery into regional exports is pending.

Remaining production gates: regional parent coarsening, mixed-LOD boundaries,
single coplanar facet ownership, full400-patch reconstruction/completeness,
expanded photo integration, predictive loading/adaptive budgets, actual browser
and device visual/FPS/cold-warm latency checks, and unattended external execution.
No Colab runtime is connected or running. Do not call the full2km pipeline complete.

The authoritative inputs are original LINZ LAZ returns, not an upscaled DEM.
They remain immutable and separate from reconstructed and simplified meshes.
All valid returns must remain recoverable even where reconstruction cannot
connect them into a supported surface. Missing observations are not evidence
of a closed mountain surface or a measurable overhang.

## Recovery first

The automatic checkpoint API is the existing owner-private Site at
`https://ridgeline-nz.connorgleeson736.chatgpt.site/api/lidar/`.
Run identity: `f899852804c645c7c8834d25a9642ce1e55000f19ad15138be5b5bbb777b142e`.
The external recovery note with source backups is
https://docs.google.com/document/d/1yXm6dNZL8kgImmrJaZl4R-2ApB8YMBvcvT0uQ6YfdpU.

To recover after losing the complete local disk, obtain service access for the
SAME Site using `get_site`, then send one JSON line through hidden stdin to
`python -m tools.lidar_pipeline.runner`. Use `operation: "restore"`, `run` as
above, a new absolute `root`, the Site HTTPS `base`, and ephemeral `token`.
Optional `only` is an array of task names for selective recovery. Never write
this input to a file or shell command. `Remote.restore_run` enumerates the
immutable journal, validates record hashes/config identity, resumes ranged
archive downloads, prevalidates every artifact, and restores commit records
after artifact writes. Intact durable tasks are skipped. Configuration conflict
or ambiguous task generations fail explicitly.

External recovery folder: https://drive.google.com/drive/folders/1eY2tUhH2WGGSrQxWxRcv2IVc6zDgso4W

Read `PROJECT_STATUS.md` there and the repository `LIDAR_PROJECT_STATUS.md`.
Each pipeline stage has a hash of its configuration/inputs, checksums of all
outputs, and an immutable TAR recovery checkpoint. A stage is **staged** while
that checkpoint exists only locally. It becomes **durable** only after upload
and matching complete SHA-256 download readback from persistent storage. A local success is never
reported as an externally saved success.

Mutable `progress.json` is a convenience index. The immutable task archives
contain their own configurations, records, and artifact checksums. They can
rebuild state if the last manifest write was interrupted. Restore validates
archive SHA-256, safe paths, and every output checksum before reuse.

Do not store OAuth tokens, session credentials, notebook outputs containing
tokens, or signed download links in this repository or checkpoint archives.

## Source audit, 14 October 2026

Existing notebook `Aoraki_All_Points_Adaptive_3D_Mesh.ipynb` contains a logged
239,901,240-return run. It failed on core tile 009 because its halo increased
the job to 3,206,829 points over a 2,000,000-point cap. Its 200 m roughness
heuristic is not a local geometric error bound. Its /content output is not
persistent. Those results are evidence of the old attempt, not new benchmarks.

The Canterbury index selects 20 original LAZ files covering the exact square;
the West Coast index selects three overlapping deliveries with nearly equal
counts and slightly changed heights. Mixing them without provenance would
duplicate returns. The sampled Canterbury header records EPSG:2193 plus
NZVD2016 (EPSG:7839), XY scale 0.01 m and Z scale 0.001 m. Coordinate encoding
precision is not survey accuracy. Source server ETags contain zero hashes and
must not be treated as cryptographic verification; compute SHA-256 locally.

## Current reconstruction experiments and release gates

All 20 original crops and all 20 source partitions are now externally durable:
222,276,698 raw returns, 65,476,037 surface-selected returns. Full disk-loss
recovery has been verified. Current detailed results are in the repository
`LIDAR_PROJECT_STATUS.md`; older Drive progress files are historical snapshots.

AFS passed one local LOD test but FAILED independent regional seams. Do not
bulk-generate AFS regional assets. `local_trial.py` independently implements a
published local Voronoi-ball filter; four NW neighbours have exactly identical
original crossing facets. Nonmanifold sheets remain explicit. `certified_lod.py`
uses local feedback with a continuous bound in BOTH directions and preserves
geographic divider facets. `orient_geometry.py` duplicates indices/reverses
winding to separate sheets, independently checking exact triangle coordinates.
It never moves, deletes or fills geometry. See `RENDERER_CONTRACT.md` for the
planned Cesium 3D Tiles integration and mandatory coordinate/imagery gates.

Bounded jobs with halos; recursive subdivision of dense jobs; full-XYZ CGAL
advancing-front reconstruction retains original point coordinates and explicitly
records unsupported returns. The older BPA implementation in reconstruct.py is
comparison code and has not passed release gates. No projection to a single
height grid. `iterative_lod.py` proposes local QEM reductions, checks topology,
all finest vertices, exact open borders and a conservative continuous geometric
error bound in BOTH directions. Failing neighborhoods are locked and the
original mesh is simplified again. Each failed iteration checkpoints its lock
mask and complete progress independently; final acceptance is separate from a
candidate count. Rejected candidates fall back to finest geometry. A one-way
CGAL polyhedral envelope alone was experimentally insufficient and is rejected.
Quantization, seams, topology, unmeshed returns, and reconstruction error are
reported separately from LOD error. A failing tile cannot enter the published
tileset. Scene graph uses standards-based spatial bounding volumes and
screen-space geometric error, with original renderer fallback.

Before a production claim: quantify reconstruction coverage and point errors,
check shared boundaries at mixed LOD, inspect vertical/underside geometry,
validate compressed decode, compare identical overview/close cameras, measure
actual browser/device frame times, persist all outputs, and verify deployment.

## Coordinate and asset export

`coordinates.py` uses a hash-pinned official NZGeoid2016 grid, mandatory vertical
operation, independent bilinear sampling and inverse checks before ECEF/common
ENU conversion. Never substitute a constant vertical offset or NZTM translation.
See the current project status for tested results and datum-accuracy limits.

Install the pinned Khronos validator with `npm ci --prefix tools/lidar_pipeline`.
Runner `coordinates` saves the verified frame/grid; `export-glb` takes root-relative
`source`, `reference`, safe `label`, optional positive LOD `budget`, and `imagery`
(`source` or existing same-place `ai`). Export rechecks continuous geometric error
in the final common ENU frame. Source UVs reject missing geographic coverage.
Flat normals and complete-attribute welding preserve creases; GPU cache/fetch
remaps and all compressed attributes are independently verified. The website
codec decodes the actual GLB, then Khronos checks the decoded core representation
as well as the compressed structural contract. No production tileset is implied
by a valid standalone GLB. `LIDAR_PROJECT_STATUS.md` lists remaining release gates.

### Recovery after interrupted regional processing

Run `python -m tools.lidar_pipeline.recovery_inventory` with echo disabled. Send
hidden stdin `{base,token,root,run,prefixes:[...],restore:true}`. Prefixes are
explicit safe task-name prefixes; an inventory is persisted externally before
restoration. It selects only names with durable records, even when a later
attempt failed, and rejects conflicting equal-time generations. Archive and
artifact validation remains independent in `Remote.restore_run`. Inventory
counts describe only the selected scope. Credentials are never saved.

`runner` forwards `revision:"r2"` to local-trial and certified-lod. Native binary
checkpoint command `python -m tools.lidar_pipeline.native_checkpoint` takes
hidden `{base,token,root,built}`. Restore that task to reuse byte-identical native
executables after runtime loss; set executable permission only after validating
all artifacts, verify runtime library compatibility and rerun lidar-planar.py.
Do not rebuild a different native binary into an existing reconstruction recipe.

### Bounded regional execution

After the durable full capacity audit and exact native checkpoint, run
`python -m tools.lidar_pipeline.regional_batch` with hidden stdin
`{base,token,root,native_task,limit:16,max_seconds:1800}`. It saves a400-task
centre-first plan before execution and a status record before/after every job.
The limit counts newly executed jobs; reinvocation reuses verified durable r2
surfaces and retries unfinished jobs. Time limits stop only between jobs.
Restore all matching local-surface r2 records and the regional plan before
reinvocation after disk loss. Never count source tiles as rendered completeness.

`regional_benchmark` persists the common2m summit parent/child comparison.
`runner export-glb` accepts imagery:"native" for exact native NZTM photo crops
from durable source-photo windows/catalog/coverage. Inputs must have complete
authentic coverage; crop dimensions over2048 require spatial geometry splitting.
The original source/AI modes remain available; native mode does not synthesize
unphotographed cliffs or infer additional resolution along a vertical face.

`regional_batch limit:0` verifies all durable reconstruction recipes against
current source audit, contributing partition signatures, native bytes and code
without executing new geometry. Changed input generations fail closed instead
of silently reusing stale surfaces. `REGIONAL_RECOVERY.json` lists the regional
restore prefixes and exact native task.

`regional_seams` takes hidden `{base,token,root,tiles:[...]}` and checkpoints each
adjacent pair independently. It compares full original crossing-facet sets,
including multiplicity and topology, with no coordinate tolerance. Results
cover only the specified scope; manifoldness, coplanar ownership, holes from
missing source observations and rendered mixed-LOD transitions remain separate.

GLB reports retain `continuous_world_bound` against the Float32 common-ENU
reference for compatibility with hierarchy error accounting. The added
`continuous_full_precision_world_bound` explicitly adds full-precision reference
rounding, including its adjusted budget. Never add that rounding twice when
building a hierarchy. These are reconstruction-relative bounds, not survey
accuracy. Node/WASM validation remains distinct from browser/device performance.

`verify_recovery` accepts hidden `{base,token,root,recovered_root}` to save an
external comparison of independently restored checkpoint signatures/artifact
hashes against the verified originals. It requires distinct roots, recovered
durability receipts and identical run/configuration, and never remeshes data.
