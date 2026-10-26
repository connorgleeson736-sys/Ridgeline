# Original MapLibre performance update

Audit baseline: published v70, source 8f2f29e1cdc00079c64980586ede7c7a7e0f20fe.

- Empty route lines are hidden until there are two waypoints; draw/clear/restore is covered by a UI-state regression check.
- Shipped MapLibre 6.14.0 image admission uses two ordered heaps, eager queued cancellation, one coalesced readiness wake, and view-generation rebuilding. Cold caches use every selected slot; warm caches protect at most one cached-byte slot within the total cap. Fairness and live limits remain covered.
- Coordinate lookup preserves exact native matrix/order behavior without filtering every render patch for every source tile.
- Sustained DPR-1 pressure can lower drawing resolution to 0.85 and then 0.75. Sub-native reductions persist through gesture boundaries and recover only after sustained healthy motion. No device-FPS claim is made.
- Detailed imagery-stage recording follows the live diagnostics toggle. Normal mode retains bounded errors. Pressure scans are deferred out of the synchronous render handler; preparation status avoids disabled-mode readiness scans.
- Original elevation requests allow a 40 ms disk-first window with cancellation, then proceed on storage stalls, with a 30-second storage cooldown after a timeout. Zero-lifetime/no-store responses are not reused or persisted. Late lookup results cannot replace the network result.
- A generated conservative 6.9 kB catalog index gates the large gap/overview imports by tile. All 5,812 authoritative entries are checked; the index regenerates during production builds.
- DEM-bound ownership/extrema changes update ancestor contributions incrementally. Conservative unknown/coarse bounds are preserved; 500 mutations match a full-reference calculation with one initial source scan.
- Strictly zoom-independent photographic stacks survive separate overlay-only zoom refreshes. Mixed photographic/vector stacks retain native invalidation, source revisions, transitions, and layer ordering. No extra separator/draw pass is introduced to split mixed stacks.
- Original source residency tracks pending counts without per-report tile-array filtering and invalidates shared composition-input snapshots on resource changes.
- Expanded preparation reuses generation-scoped source snapshots, raises existing per-minute allowances by 50% only after recent healthy moving frames, and avoids speculative composition targets above 16 MiB. Foreground/idle/memory gates and serialized source ownership are preserved.

The alternative renderer's source, shaders, assets, and rendering policy are unchanged. Broad mixed-stack separation, a new public imagery CDN, and speculative parallel GPU preparation were not introduced: these are architectural candidates requiring visual/device evaluation, rather than verified fixes.

44 relevant checks pass, including the added server module-closure check. Verification uses native CPU scheduling/coordinate/fingerprint paths, controlled GPU handles, original route UI fixtures, authoritative imagery catalogs, and the production artifact checks. After the local launch repair, the supported browser preview loads the page. Its browser reports GPUInitializationError because WebGL2 is unavailable, so real-GPU timings and rendered-map visual QA remain unverified.

Publication repair: the legacy vinext CLI emitted an entry with absent hashed server imports even though it exited successfully. Production builds now use the Vite CLI multi-environment pipeline (including through the existing bounded Linux wrapper). A server module-closure check fails the build if a relative static or literal dynamic import is missing. Worker bindings, assets, access and runtime routing are preserved.
