# Prioritize the terrain face visible at screen centre

Implements the selected behavior from the MapLibre-based app: source detail and
request ordering follow a measured visible surface hit independently of the
navigation pivot. Continues from native 0.1.4, based on publication `93be86c`.

## Data flow

After a successful native frame, the GPU owner uses the existing CPU terrain
picker on the centre screen ray. It intersects retained heightfields through the
current drawn cover and chooses the nearest actual terrain intersection. No GPU
readback, network request, or fabricated height-plane hit is introduced. The
same result serves existing idle camera recentering, avoiding a duplicate pick.

The focus is separate from the camera pose. During gestures sampling is bounded
to 240 ms, with one deferred wake for unfinished changes and no permanent idle
frame loop. Terrain arrivals/settled camera changes refresh it. Invalid/missing
terrain clears it; unchanged views reuse the measured hit. A recent hit can
survive small motion only while projecting inside the central 80% of the new
viewport, and expires after 500 ms across changed cameras. Resize, surface loss
and GPU recreation invalidate it. Small changes use the reference 3.5%-position /
6%-range bands to avoid needless plan rebuilding.

The immutable demand key contains the focus. Both imagery and terrain plans pass
it to Rust through `planFocused`. The original JNI `plan` remains available for
existing fixtures. Predicted future views clear measured focus rather than
pretending it was sampled there. Changed focus invalidates idle preparation.

Rust prioritizes splitting near the measured face before the pivot. Nearer
surfaces receive an appropriate minimum focus detail using the actual hit range;
normal source maximum zooms, detail settings, frustum and total source budgets
remain. Sorting uses the reference normalized distance-to-tile-bounds rule:
minimum of surface distance / surface range and pivot distance / pivot range
plus 0.15. The pivot remains a secondary focus. The resulting order feeds existing
parent/fine demand ranking, shared transport, CPU work and GPU upload priorities.

With no usable focus, Rust executes the previous ordered selection policy.
This does not add elevation-aware frustum bounds or replace the native distance
LOD heuristic with the complete MapLibre engine. Camera gestures/position,
source pixels, source resolution, height sampling and route planning are unchanged.

## Focused verification

Two Rust and two Kotlin tests; no broad suite or lint. A CPU ray intersects a
synthetic 3000 m elevated 129×129 field before reaching the map pivot, using the
production heightfield intersection algorithm and camera transform.

| Selection for the visible face | Before | With measured focus |
| --- | ---: | ---: |
| Request rank (zero-based) | 33 | 0 |
| Selected imagery source zoom | 15 | 16 |
| Total selected sources / budget | 72 / 72 | 72 / 72 |

The resulting cover is non-overlapping and respects maximum source zoom. Missing
or invalid focus preserves exact ordered plans across 24 varied views. Kotlin
checks cover stale/off-screen/resize fallback, missing hits, gesture cadence,
plan-key invalidation and stable reuse. These prove selection/state behavior;
they are not a measured phone latency improvement or a diagnosis of the remaining
ten-second stall. No claim that the user issue is solved.

**Offline / imagery → Loading diagnostics** reports whether detail focus is a
measured visible terrain hit or the fallback map pivot.

Reference: exact 0.18 `public/surface-focus.js` and `terrain-streaming.js`, as
resolved in [the remaining-gaps audit](MAPLIBRE-GAPS-0.1.4.md). Raw validation:
[surface-focus-2026-11-01](../validation/surface-focus-2026-11-01/).
