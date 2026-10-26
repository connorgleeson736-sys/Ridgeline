# Prepare ahead radius — 19 October 2026

Latest cache-return fixes and recovery: see CACHE_RETURN_STATUS.md. Earlier historical descriptions below retain their original context.

Ridgeline Site appgprj_6acc83afb6588191aff43dc542d98b5f, starting version44/source dbea76e12bd8bf4cac189b6e22657bb0ca440496. User requests a slider from1 to an aggressive20-tile ring. Existing private audience/R2 LIDAR/Original renderer, measured DEM, imagery colours, visible LOD, profiles and routes are preserved.

## Implemented

Map layers → Detail & caching → Prepare ahead radius. Integer1–20, default1, enabled by the existing checkbox. Choice is saved in ridgeline-detail-v1.prepareRadius; missing/invalid legacy values default1, finite values clamp/round1–20. Checkbox preference stays separate and unchanged. Existing detail fieldset remains disabled in optional Three.

Radius applies to both imagery/elevation source preparation and finished terrain composition, around camera and first-hit terrain anchors at their respective existing preparation zooms. Radius1 means3×3 coordinates per anchor; radius20 means41×41 (1,681). Overlaps deduplicate; up to3,362 unique coordinates per stage/source within NZ. Order is inner rings first, then nearest anchor distance, with stable tie breaks. Native source/render LOD, frustum and foreground request policy remain authoritative. No new imagery/generated DEM.

At radius1 the previous source12-per-view/24-per-minute and composition4-per-view/8-per-minute limits remain. For larger radii, the source queue can include the full selected area instead of the old16-coordinate/twelve-task truncation. Native source preparation is still one job at a time. The rate cap scales24×radius per minute; after reaching it, bounded timers can resume the remaining queue as tokens expire. New source allocation per view is capped at min(12×radius MiB, min(80%,8%×radius) of source budgets). Cached GPU/source tiles and visible same-or-finer coverage are skipped.

Composition planning uses the same radius rather than the old8-candidate truncation. Every four completed source starts at larger radii, give ready composition a turn so a large source queue cannot defer it until all downloads finish. Composition attempts cap4×radius/view and8×radius/minute; allocation cap min(12×radius MiB, min(80%,20%×radius) of existing view budget). Existing one idle operation at a time,350ms spacing,650ms settle,4ms idle allowance and >8ms CPU-submission backoff remain. No simultaneous background request burst and no GPU readback/finish.

All existing source90%, byte85% and warm-view90% admission gates stay. Preparing large radii can stop due to cache capacity; it is a search/work area, not a guarantee that every one of1,681+ tiles will fit in352–700 MiB. Existing cached/visible data remains valid when shrinking; obsolete queued/pending unused network work cancels, visible-adopted/in-progress decode work remains protected. Navigation, checkbox off, low-power/data-saver, hidden/context loss and renderer teardown still stop scheduling. Diagnostics include radiusTiles.

## Validation / recovery

checks/prepare-radius.cjs verifies exact9→1,681 coordinates for one anchor,2,091 for overlapping anchors,20th-ring corners, nearest-ring ordering, deduplication, integer/clamp/defaults, real application saved controls and callback wiring. PREPARE_RADIUS_VALIDATION.json.

checks/prepare-ahead.cjs uses actual pinned native source/cache/DEM GPU paths and now verifies live radius change cancellation plus expanded20-radius preparation beyond the old12-job cap (27 native jobs in the fixture), with native imagery/DEM pools within budget. checks/composition-scheduler.cjs verifies expanded composition cap/live resize alongside existing scheduling/cancellation/pressure tests. terrain-composition, base-viewer/bootstrap, cache-slider and native residency/continuity regressions pass. Controlled GPU handles; no actual Chromebook/browser FPS benchmark.

Restore this exact Site through Sites source helper if scratch is lost. Read this document plus TERRAIN_COMPOSITION_STATUS.md and CACHE_SLIDER_STATUS.md. Run the above tests. Helper owns build, production guard, Git milestone and archive; publish exact source/archive privately, preserving storage/audience. New module URLs: viewer25/app28/native13/prepare-ahead3/diagnostics15; terrain-composition1 unchanged. Production guard requires radius validation and hashes. Live checker scripts/imagery/south-island/check-publication.py verifies versioned files and existing persistent South Island imagery; feed fresh credentials through hidden PTY only. Its immutable R2 proof records exact source commit. No imagery rebuilding is required.
