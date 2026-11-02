# Capacitor 0.18.0 latency-audit implementation

Target: Capacitor Original + Direct, released source
`d880143418ac46ece98adff0b3b2fbaedf044d46`. Refreshed
`android-preview-download` and checked its Base validation/source record.
Input: the user's `Ridgeline-0.18.0-latency-audit.zip`, preserved under
`validation/latency-audit-2026-11-03/input/`.

This is source implementation and host validation, not a new APK release or a
claim of measured phone FPS. Work is isolated on `capacitor-latency-audit`.
Native Rust changes from the preceding task are outside this branch.

## Changes

1. **Live display and loaded-cover reconciliation continue between selections.**
   Wrapped managers always execute their native update/retention path. Only the
   geometric ideal-ID traversal can reuse its previous result. Plain raster
   arrivals no longer override the cadence. DEM, metadata, non-raster content,
   resize, terrain changes, restoration and gesture end force fresh selection.
   Ideal reuse also stops on source/terrain/projection identity or source policy
   changes, ground elevation changes, and a camera displacement/zoom/rotation
   guard. Lists are copied before native retention can mutate their containers.
   Capacitor's new default is 100 ms; existing saved preferences, including an
   explicit zero/Every frame, remain intact. The website default remains zero.
2. **Conservative terrain bounds are reused.** The visibility adapter resolves a
   source DEM once, sharing it with the native extrema method and uncertainty
   margin. Revision/exaggeration/detail keys invalidate the vertical-bound memo.
   Source lookups still touch the native caches on hits. The pinned Mercator AABB
   cache includes tile coordinates, wrap and final min/max, scoped to the terrain
   object and bounded to 4096 entries. Changed peaks or unknown fallback values
   produce a different box. Native AABB methods used by traversal are read-only.
3. **Byte delivery no longer waits for bitmap decode.** Separate delivery tokens,
   staged bodies and decode counters preserve cancellation and independent
   bitmap ownership. Queued decodes choose the current policy priority when a
   decoder frees. Staging permits at most 16 items/16 MiB, accounting for a 1 MiB
   reservation per admitted delivery. A body larger than that reservation keeps
   its delivery token through decoding instead of entering the staged pool.
   Such bodies remain part of the separately count-bounded delivery lane; the
   staging-byte number is not a cap on all network/bitmap/native memory. Decode
   concurrency retains its existing independent setter/default policy. Reset no
   longer zeroes counters still owned by running jobs.
4. **Error profiles can finish during motion.** One worker owns one admitted DEM
   snapshot; it reuses that snapshot for subregions of the same DEM revision.
   Native pixels are copied only on admission and never detached or modified.
   Snapshot size is capped at 8 MiB and only one job runs at a time; existing
   visible-patch queue limits remain. Data identity, border revision and current
   job identity gate publication. Context loss, disabling and teardown terminate
   the worker. Unsupported workers keep the original cooperative fallback.
   Native full-grid fallback, error/cell-span thresholds, hysteresis, fine edges
   and skirts remain. Copying an admitted snapshot still has a main-thread cost;
   the expensive sampling/error analysis runs in the worker.
5. **Aggressive request coverage does not widen terrain submission.** Raster/DEM
   source managers retain the optional warm ring, but the rendered-terrain
   manager uses the real camera. Removed the unconditional 33 ms repaint loop;
   normal camera/source events drive progress. This opt-in mode still consumes
   extra download/retention capacity when enabled.
6. **Passive routes join the existing draped line group.** Their line is inserted
   before the active route halo, ahead of symbols/circles. This avoids the
   previously appended line's separate terrain-drape group while preserving
   active route and waypoint ordering. Actual device draw counts remain unmeasured.

The pinned engine patch has a replayable script and updated SHA-256 provenance.
No imagery/binary payload, shader, image resolution, terrain-detail default,
route-planning algorithm or alternate renderer was changed.

## Focused verification

See `validation/latency-audit-2026-11-03/results.json` and adjacent logs.

- Selection replay: 60 draws and 60 reconciliations, 10 ideal selections; identical
  counts with a raster content event before every frame. Gesture end and DEM
  arrival force exact selection. This uses the patched engine's actual helper.
- Held-decoder replay from the supplied audit: 16 bodies now arrive before a
  decoder is released, versus the audit's original 8. The pool then stops at its
  16-item bound; releasing a decoder admits body 17. This is a dependency test,
  not a phone download-speed measurement.
- Worker results exactly match direct min/max and both error-profile levels for
  three tested parent/subregion configurations, including snapshot reuse. Original
  source bytes remain identical.
- Existing queue/ownership/cancellation checks: 10 pass. Route checks: 4 pass.
- Existing actual native Mercator/DEM visibility check passes, including fine
  peaks, eviction and unknown bounds. Existing adaptive geometry check passes,
  including native edges/skirts, perspective bounds and border invalidation.
- Capacitor Base web build passes; generated worker/engine syntax and provenance
  checked. Replaying both engine patch scripts from the exact released bundle
  reproduces identical bytes; the latency patch is idempotent.

Commands: `node checks/audit-pipeline.mjs`,
`node checks/audit-image-staging.mjs`,
`node --test checks/trace-performance.cjs`,
`node checks/terrain-visibility.cjs`, `node checks/adaptive-terrain.cjs`,
`node --test capacitor/tests/routes.test.mjs`, and
`RIDGELINE_BUNDLE_OVERVIEWS=0 npm --prefix capacitor run build`.

The untouched scripts under `input/` reproduce the old audit conditions and
contain assertions expecting the old bottlenecks. Use the new `checks/audit-*`
fixtures to verify the changed behavior.

No broad suite/lint, Android APK build, browser device session or physical-phone
benchmark was run. A handset comparison must still check frame pacing, cold
centre-tile sharpness, peak memory, worker/CSP behavior and sustained performance
with matching camera path, caches, detail and layer visibility.
