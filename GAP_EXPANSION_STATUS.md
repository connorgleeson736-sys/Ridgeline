# Ridgeline audited imagery-gap replacement and sky colours

Existing project: `appgprj_6acc83afb6588191aff43dc542d98b5f`. Preserve its current audience (public when checked). This update extends regional satellite imagery; it does not change elevation, terrain geometry, imagery LOD sliders or route planning.

## User requirements and implemented architecture

The attached `LINZ_10m_Coverage_Audit_2026-10-20(1).zip` supplies 14 sample-confirmed terrain target polygons. Manukau Harbour water candidate 081 and the other 107 unconfirmed candidates are excluded. The original Cook patch is included as a fifteenth processing region so its old fractional edge is removed.

**Latest explicit instruction: no smoothing between edges.** Use hard source boundaries: native masks contain only alpha 0 or 255 and use nearest mask sampling. No feathering, overlap transition band, Gaussian colour matching, or generated geographic detail is active. Different source capture colours can remain visible across a hard cut. Normal photo filtering and area downsampling for smaller zoom levels remain necessary and are not an artificial geographic blend band.

- `scripts/imagery/expand-gaps.py` reads the committed audit targets and source guard metadata in `qa/gap-expansion/`. Current live source IDs were checked against the attached audit and matched.
- Early RGB-equality draft was rejected: low-zoom basemap background layers prevent robust equality against the raw national 10m mosaic. Final masks subtract actual PNG transparency of relevant fine LINZ surveys at z13 from the target regions (expanded by 200m to account for approximate audit boundaries). Source footprints only prune which survey tiles need querying; their rectangles do not define valid imagery.
- Source photos and fine-survey PNGs are downloaded atomically and reused from `qa/gap-expansion/source/`. `progress.json` records completed regions, source hashes and counts. Inputs contain no credentials. Original LINZ key remains in its existing app configuration and can be overridden via `LINZ_API_KEY` for preprocessing.
- Protect an 1100m radius around Aoraki's summit. Keep good LINZ imagery elsewhere; native masks are about 14m ground spacing and do not claim subpixel survey-cutline precision.
- `public/gap-imagery-atlas.js` indexes small 256px masks and sparse coarse overlays. Only 8 small masks stay decoded per worker (about 2MiB), with LRU eviction. No large national image needs decoding in the browser.
- `public/gap-imagery.js` runs ahead of existing regional routing. Unaffected tiles use the original path, interior gap tiles generally use one Esri request, mixed tiles compose a hard mask off the main thread. Failed gap-provider requests become retryable 503s rather than falsely invalidating the LINZ API key and switching the whole map. Zoom19 crops the correct z18 parent. Esri z18 samples for all 14 targets were fetched and inspected; evidence is under `qa/gap-expansion/esri-z18/`.
- Coarse overlays are area-downsampled from the same z13 satellite snapshots and saved as immutable content-addressed assets. They overlay existing Cook/South Island overviews without replacing the existing R2 corpus. New native source key `ridge-gap=gap-v1` prevents old processed imagery from being reused; private flags are stripped before LINZ requests.
- Existing cancellation-aware composition scheduler and shared byte/GPU caches are retained. Deterministic cache-return test verifies concurrent requests deduplicate and a revisit does not download or recompose again. No additional GPU imagery layer or per-frame mask scan is added. No new device FPS benchmark is claimed.

## Sky control

Map layers → Sky colour: Mist, Alpine blue, Clear blue, Warm haze, Slate, Night and a custom colour picker. The preference is validated and saved under `ridgeline-sky-v1`. `public/sky-colours.js` applies native MapLibre sky/background properties and the Three.js scene background; no imagery/DEM modification. Three.js temporal history resets on a sky change. Original default background hue retained. UI listeners are cleaned up on final page exit.

## Validation and recovery

- `python scripts/imagery/check-expanded-gaps.py`: verify binary mask pixels, all 14 sample locations, fine/water controls, Cook protection, old patch replacement and asset inventory; prune obsolete generated content hashes.
- `node checks/gap-imagery.cjs`: actual atlas routing, current shared cache dedup/return, 503 recovery semantics, cancellation, coarse pyramid, DEM pass-through and correct overzoom crop.
- `node checks/sky-colours.cjs`: saved presets/custom values, native sky style schema, both renderer hooks, bad-input rejection and cleanup.
- `node checks/regional-blend.cjs`: original pixels retained outside mask, hard mask interpolation disabled, sparse overlay composition and image cleanup.
- `node checks/audit-fast.cjs`: expanded 32-gate regression suite. Actual ESM bootstrap and worker-failure planner fixture also used. Final result reports recorded alongside this document.
- Browser visual QA remains unverified: managed preview previously failed because an unrelated Vinext server already holds the port/lock. Do not kill that server or substitute unsupported browser infrastructure. Authentic imagery contact sheet was inspected offline.

To resume: read this document and `qa/gap-expansion/progress.json`. Install Python numpy, pillow, scipy, shapely, pyproj and matplotlib if needed. `expand-gaps.py` can use the existing optional geometry-library cache but does not require it if dependencies are installed normally. Rerun preprocessing to reuse source snapshots, then run mask/runtime regressions, regenerate build identity, build through the Sites helper, and run `checks/base-production.mjs`. The Sites source helper owns all Git commits, pushes and packaging; publish its exact saved version without changing sharing.

Current module URLs: viewer36, app34, build-info8, regional-imagery9, regional-blend4, regional worker6, gap atlas1, gap loader1, sky colours1, Three.js renderer7, CSS18. Preserve the original imagery corpus and its validation records. Never include API keys in logs or generated metadata.


## Completed validation

32/32 fast regression gates passed, actual ESM bootstrap passed, worker-failure planner fixture passed, production asset/hash checks passed. Pixel validation: 292 binary native mask tiles; all 14 target samples alpha255; original/fine/water controls alpha0; zero replaced pixels overlapping sampled valid fine-survey alpha; zero uncovered pixels from the old Cook feather mask. 207 coarse overlay tiles; 426 distinct generated mask/overlay assets, 8,781,721 bytes. Production client total about 24.1MB across 629 files; existing R2 imagery packs unchanged. Full source snapshots, progress, native masks, source metadata, Esri z18 evidence and validation files are committed through the Sites helper. No browser/GPU FPS claim. The exact publication commit is recorded in the Site's saved version metadata.

## Publication recovery

The first source upload exceeded the receive-pack size limit because it included unused snapshots from abandoned preprocessing drafts. The release checkout is `/workspace/sites/ridgeline-gap-release`; it retains all 684 source snapshots referenced by the final progress manifest (21,416,884 bytes), masks, evidence, and recovery metadata. The 495 unused draft snapshots are excluded. Runtime source is byte-identical to the validated implementation. The original checkout and failed local commit remain available as a recovery backup. Resume publication from this release checkout using the Sites source helper.
