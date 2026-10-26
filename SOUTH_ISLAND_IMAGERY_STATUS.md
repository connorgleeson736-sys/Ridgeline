# South Island imagery release — 18 October 2026

## Completed output

All 16,226 source snapshots and all 5,313 overview coordinates (10,626 raw/natural entries, zooms 0–12) are generated, stored persistently and validated. Source zoom is 13; each tile remains 256 × 256. The full South Island / Rakiura / nearby coastal-island footprint is covered. The same 27 previously reviewed Cook West Coast source-gap tiles retain Esri; no new Esri footprint is introduced. Fine zoom-13+ imagery, elevation, geometry and all existing LOD/residency policies are unchanged.

Site: appgprj_6acc83afb6588191aff43dc542d98b5f
Origin: https://ridgeline-nz.connorgleeson736.chatgpt.site
Audience: owner-private; preserve access.
Run: ac824fc35e7efc4945ea59cf3e38ff5a0dd99bd8eba3521f58f51558e1679a57
Persistent full index: /api/lidar/files/0131124bab52e92164eb46f4e61ca1e41743cb1df68ce6a49a2cb35427c22da1.bin (2,115,490 bytes)

Source snapshot bytes: 154,170,241. Unique deliverable tile files: 5,344 / 368,444,204 bytes, excluding intermediate overlays and any repeated pack storage. Public client index: 190,083 bytes / 94 immutable range-pack references. Coarse static copies at zooms 0–8: 67 content-addressed files / 3,681,702 bytes. Full imagery is in R2, not bundled into Site source. See SOUTH_ISLAND_IMAGERY_VALIDATION.json and SOUTH_ISLAND_ASSET_VALIDATION.json.

## Image and viewer contract

The coastal mask comes from Natural Earth's public-domain 1:10,000,000 country polygons, including Rakiura and coastal islands. The DOC coastline endpoint returned 503. This polygon only controls conservative offshore blending, never terrain or navigational geometry. A broad coastal guard protects land; North Island/outside-mask pixels retain their previous low-zoom LINZ imagery. No imagery enhancement or invented terrain is introduced.

The pyramid uses recursively aligned 2 × 2 premultiplied-alpha area means with nearest-even rounding and lossless WebP round-trip checks. All levels use the same zoom-13 snapshots, replacing the old differently coloured 10 m mosaic on South Island land. Runtime LINZ paint is constant snow-v3: saturation −.25, contrast .26, brightness minimum .058, maximum 1. This slightly reduces snow brightness. Esri compensation is derived from the same constants. Fine source surveys may still differ in date/lighting; constant grading does not make different photographs mathematically identical.

Soft terrain shading starts off, with restored form state disabled and an explicit initial checkbox reset. Opt-in shading remains available. Eight editorial mountain-route overlays, catalogue/search entries, data and Three.js route buffers are removed. The 18 summit labels and user-drawn/saved/imported/exported sketches and profiles remain. Original is still the default renderer; Three.js remains optional.

Directional imagery warmup targets the first-hit surface and the wheel location. It warms at most one level finer than the active native tile, at most three candidate tiles / two concurrent requests / twelve per gesture / twenty-four per minute. Pan motion predicts only a short continuation. It respects provider zoom ceilings and pauses while stationary, distant, hidden, low-power/save-data, with native imagery backlog or nearly full cache. Stable wheel gestures reuse a geographic anchor to avoid repeated terrain readbacks. Speculative inserts do not evict retained views; native requests join pending preloads. It changes compressed-byte caching only: selected LOD, covering, sliders, geometry and GPU cache budgets do not change. Six existing LOD/residency modules are byte-identical to previous production a0930123b48dabfdbad3e6b6d8e7d51ff70aefb0 (SOUTH_ISLAND_LOD_VALIDATION.json).

Overview delivery reads exactly one 256² image from an immutable R2 byte range. The loader verifies HTTP 206, exact Content-Range prefix, actual byte count and WebP container. Generic application/octet-stream MIME is accepted for valid WebP. It cancels an unexpected whole-pack response. Coarse static copies fall back to the identical R2 image, never the old 10 m photograph. A failed saved tile keeps the native hierarchy's parent fallback rather than silently changing imagery. The prior Cook-only manifest/static corpus is retired from deployment; original source QA snapshots remain for provenance.

## Validation

All 16,226 source hashes, all output/overlay hashes, all 10,626 public range mappings and all coarse static hashes passed. All source snapshots are opaque 256². Independent integer-alpha recursive checks cover 65 samples across every generated level; worst mean byte error is 0.004124/255, with at most one byte at offshore floating-point half-way ties. The four-authentic-tile opaque LINZ mean is pixel-exact; all 3,084 opaque coastal guard blocks pass; neighbouring source masks match one continuous resampling exactly. See SOUTH_ISLAND_FILTER_VALIDATION.json.

The completed Cook overview was read from the authenticated live storage endpoint: HTTP 206, 81,794 bytes, SHA-256 332d411b6e5a631f8606b935e8c63d90d1ed6d99c9a2da5635821c6c79089224. Content-Length was omitted; actual received length and Content-Range validate. Its actual bytes pass through the registered imagery protocol and shared cache with one network request including revisits. Static generic-MIME success / exact-range fallback, raw/natural routing, malformed range/image/network failure, cancellation and fine/DEM/outside-region passthrough checks pass. See SOUTH_ISLAND_DELIVERY_VALIDATION.json and qa/south-island/range-proof.json.

Passed: peak-directory, imagery-preload (including provider ceiling and wheel-anchor reuse), south-pyramid, south-assets, south-colour, south-routing, snow-grade, regional imagery/worker, imagery retention/recovery, surface-focus, base viewer with worker success/failure and actual ESM bootstrap, optional Three planner, storage API. HTML IDs are unique and shading is unchecked. The exact build additionally runs base-production: complete coverage, persistent release manifest, actual asset hashes, bootstrap imports, engine hashes, retired assets/routes absence and hosting size budget.

Managed Sites has no control-browser capability. No live GPU screenshot, device FPS, visual route replay or measured speed-up is claimed. The engine and geographic measurements are unchanged.

## Durable recovery

The previous host failed after 5,310 coordinates. This session restored every completed tile and source from external checkpoints and generated only the final three coordinates. Working source milestones were pushed at 233c93f1a01be3fb78f5ca66daca016d5658eaf6 and recovery checkpoint 9e4cf67d3095ed1875cea5061dcdc8abbf193278. Do not publish their partial checkpoint archives. Use the final saved/published Site source.

1. Open this exact Site using get_site, a fresh source credential and the Sites source helper. Read this document. Source checkout is replaceable: /workspace/sites/ridgeline-base-viewer.
2. Local accelerated cache: /workspace/scratch/3dd2ae8bd0a2/south-island-overviews-v1. Never assume it exists. Use RIDGE_SOUTH_WORK to choose another directory consistently.
3. Run python scripts/imagery/south-island/pipeline.py --stage restore --workers 96 in a PTY if local data is missing. At its hidden prompt supply one JSON line containing url (the exact origin above) and token (fresh get_site siwc_bypass_bearer_token). Keep tokens only in process memory; never arguments, logs, files, archives or git. The existing public LINZ key is read from app.js.
4. Restore verifies immutable record and full pack SHA-256s, each file hash and safe extraction path, writes files atomically with fsync and checkpoints after every completed batch. Three bounded pack downloads overlap eight small record reads; writes/checkpoints remain serial because identical image paths may occur across packs. Existing valid batches are skipped. This recovery was exercised end-to-end after real host/cache loss.
5. Run --stage build to reconstruct the final index/reports from restored completed outputs; complete image batches are skipped. Use --stage all only when sources genuinely remain pending. Source downloads have 96 concurrent waits but a shared 12 requests/s LINZ cap; output CPU workers are capped at eight and uploads at two. Complete source batches contain 384 files; output batches 64 coordinates. Immutable uploads verify stored chunks and assembled files before acknowledging a task.
6. Run python scripts/imagery/south-island/client-index.py, then python checks/south-assets.py. This compacts the verified full index and restores coarse static copies. Run relevant viewer checks. Build/package/push with the Sites helper and base-production. The production guard rejects incomplete coverage. Preserve the owner-private audience when publishing.
7. After deployment, run python scripts/imagery/south-island/check-publication.py with fresh hidden Site credentials. It compares live index/bootstrap/HTML and representative static-image hashes, reads the completed Cook range again and writes an immutable proof under this run's status/publication-verified records. This verifies actual delivery, not GPU performance.

The existing /api/lidar/ R2 API stores immutable blobs, assembled-file descriptors, task/config/status records and the final full index. Local progress is atomic progress.json; external per-batch manifests remain authoritative after host loss. Runnable pipeline/config/mask dependencies were saved externally before processing; the latest build-start archive is /api/lidar/files/3b4710cc1790b4a0491c64e3863b3487fcd527bbadaaf892f8c59c437ae35397.bin. All application code, validation and documentation are saved in the Site's Git repository through the supported helper. Never depend on conversational context or the local cache as the only copy.

## 18 October — loading continuity / higher tilt

The later LOADING_CONTINUITY_STATUS.md documents selective geometry/imagery disappearance fixes based on the user's 21:32 recording. Residency lifecycle adapters now protect DEMs needed by visible/warm terrain, keep complete old coverage until replacements are ready and defer incomplete colour refreshes. Max pitch is85deg. Source imagery, geographic measurements and existing LOD selection are unchanged. The old six-module byte-identical residency statement applies to the earlier South Island release; current LOD selection modules remain identical, while residency has intentionally changed. See LOADING_CONTINUITY_VALIDATION.json and updated native/hierarchy reports.
