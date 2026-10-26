# Aggregate cache slider — 19 October 2026

Site appgprj_6acc83afb6588191aff43dc542d98b5f; starting version41/source5cc27bd6bd725087f81b9402e079471cd4a708b2. User explicitly requests only a cache slider and analysis of the supplied colour-change recording. Do not implement a colour/imagery fix in this release. Preserve owner-private audience and LIDAR R2 binding.

## Implemented

Replace Keep loaded views (warm RTT only) with Tile cache budget in Map layers → Detail & caching. Range96–700 MiB,4 MiB steps; standard4 GB default352 MiB. It controls all six existing pools: imagery textures, decoded elevation, warm rendered views, unused rendering targets, compressed imagery bytes and compressed DEM bytes. Device/default/data-saver proportions are preserved and scaled to the selected aggregate; integer rounding remainder goes to imagery so the sum equals the displayed target. This is an estimated cache budget, not a total browser/GPU-memory cap; current visible resources and route/profile/runtime/transient allocations are additional, and measured DEMs needed by visible terrain remain protected.

Changes apply after the existing160ms control debounce and persist in ridgeline-detail-v1.cacheMiB. Legacy view-only values migrate to their equivalent aggregate budget; manual values clamp/round safely. Existing imagery/terrain detail controls, reduced-memory device defaults and data-saver request policies remain. Controls remain scoped to Original renderer. No page/map recreation or terrain/imagery source changes on resize.

Live resize APIs update source pools, byte caches, warm drapes and unused target pool. Raising a budget retains existing entries. Lowering trims LRU off-screen entries, using existing protected DEM dependencies and warm-drape pressure relief. Visible textures remain owned/rendered. Warm view capacity adjusts without resetting native detail/RTTs; compressed buffers are not detached; unfinished requests are not restarted or speculatively added.

Fresh module URLs: viewer22/app26/native10/raster7/tile-cache8/diagnostics12. Native engine6.14.0 unchanged. Production guard checks built/source hashes and CACHE_SLIDER_VALIDATION.json.

## Validation

checks/cache-slider.cjs checks every slider value against six-pool sums for2/4/8 GB and data-saver modes, resize700→96→352 against actual native source/cache classes, production UI/persistence with a mocked map, no map recreation, no active texture loss, byte-cache hit reuse/LRU trim, spare target resizing, settings migration and identical source/paint/detail state. checks/native-residency.cjs separately exercises aggregate changes against actual native terrain tile/RTT objects and preserves the visible RTT. Loading-continuity, hierarchical-streaming, tile-unloading, imagery-retention/preload/diagnostics, South routing/colour, actual ESM bootstrap and Three planner switching pass. No live browser/GPU QA is available; no browser was installed or improvised. Source image, colour, mask, worker and LOD modules are hash-identical to the starting publication; see report hashes. Complete South Island imagery remains persistent and unchanged.

## Recording and colour investigation — analysis only

Input: Screen recording 2026-10-19 08.06.10.webm,16.786s/1290x580, Library identitylibfile_dfff88a7e618819199c09d30858e6d47, supplied local upload. Analytical contact sheet remains scratch-only. The video shows snow-covered mountain faces becoming brighter white as close detail arrives. It supplies no source-tile diagnostics, so exact zoom/source/driver cause cannot be established from the video alone. Current imagery.paint already uses the same saturation/contrast/brightness at every camera zoom. Do not claim we confirmed a particular LINZ source switch or altered any colours.

Recommended investigation: fetch the same ground footprint at adjacent actual source zooms and compare matched snow/rock pixel luminance, white point and clipping before/after the fixed shader grade. Check whether the precomputed zoom13 overview boundary, a higher-resolution survey selection, sRGB resampling/mip generation or retained drape state contributes. LINZ's published configuration supports imagery layers with zoom-dependent availability, which is a possible mechanism, not proof for this clip.

Candidate solutions, not implemented:

1. Fixed regional radiometric calibration: match source-level exposure/white balance/contrast to one reference, anchored to geographic footprints. Use cached calibration coefficients, not per-frame automatic exposure. Lowest-storage option if source measurements reveal a simple tonal mismatch.
2. Linear-light averaging/mips: decode sRGB, downsample/filter in linear RGB, encode back and apply the display grade consistently. Test a small region first; this can reduce luminance changes caused by averaging dark rock and bright snow, but cannot correct different capture dates/snow coverage by itself.
3. A consistent imagery pyramid: derive coarser levels from the same chosen high-detail imagery and treatment, extending the existing downsampled-overview idea where necessary. Best consistency for a fixed photographed region; requires preprocessing/storage and careful choice of source detail/coverage. Do not regenerate imagery without authorization.
4. Tone matching plus short parent/child crossfade: removes a visible transition after source colours are matched. A fade alone can smooth the switch while still leaving an eventual white-point difference. MapLibre's raster-fade-duration exists, but the terrain RTT cache/completion path must also participate; changing only that property is not assumed sufficient.
5. Softer highlight roll-off: a shared tone curve can keep fine-resolution snow from clipping abruptly to white. It affects the appearance of all levels and needs user approval against the preferred snow reference.

Primary references: https://basemaps.linz.govt.nz/docs/operator-guide/quick-start/ (zoom-limited imagery layers); https://basemaps.linz.govt.nz/docs/configuration/ ; https://maplibre.org/maplibre-style-spec/layers/ (raster tone/fade controls); https://registry.khronos.org/OpenGL/extensions/EXT/EXT_texture_sRGB.txt (linear RGB conversion/filtering). These support mechanisms/options, not a diagnosis of the recorded instance.

## Recovery

Restore this exact Site source through Sites helper; keep its returned project_id/checkout_path/commit_sha. Run checks/cache-slider.cjs, native-residency.cjs, raster-residency.cjs and loading-continuity/hierarchical-streaming plus relevant planner regressions. Use the source helper for final build-site.mjs, base-production.mjs, commit/push/archive; no manual Git mutations. Publish returned source/archive with native private operation. Then run scripts/imagery/south-island/check-publication.py in a PTY with fresh origin/bearer via hidden stdin; it verifies exact live modules, static samples and persistent Cook range bytes and saves an immutable external proof. Do not print credentials or copy the user recording into the publication.
