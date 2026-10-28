# Main-thread work reductions — 0.15.0

This update selects three measured CPU/allocation fixes. It does not change renderer, source imagery, source zooms, terrain geometry/detail, camera/frustum selection, raster filtering, cache budgets or movement-resolution policy. Website `public/` files stay unchanged; checked app-only build transformations modify the pinned terrain adapter.

## 1. Reuse geographic coordinate indexes

`relatedCoordinates` previously rebuilt parent/descendant maps once per synchronous preparation call. Those maps depend on geographic membership, not camera matrices. The app now keeps one snapshot per terrain manager using a WeakMap. Every lookup validates key membership, TileID identity and canonical coordinates. Changed membership/identity/coordinates rebuilds it; order-only changes reuse the geographic maps and replace the order map. Previous snapshots are not mutated.

Native regular-tile coordinate and matrix calculations still run on every request. The cache does not retain camera-specific matrices, pixel data or GPU textures. It introduces no TTL or independent camera/LOD policy. Manager teardown permits snapshot collection through weak ownership.

A controlled Node fixture (256 terrain tiles, 40 preparation passes, 16 source-coordinate queries per pass, median of five after warmup) changed from approximately **121.2 ms to 10.6 ms**, returning the same candidates. Native matrix work was mocked in this timing fixture; this is not an end-to-end or phone FPS result. Tests separately verify fresh matrix calls and candidate order, including ordering changes, membership mutation and TileID replacement.

## 2. Reuse temporary release functions

The terrain update adapter previously created release wrappers and undo closures for every active tile on every update. It now memoises the release wrapper per tile/key/original method using weak ownership, and restores the original method after each native update. Wrappers are installed only during the same update interval as before; the current update's deferral maps remain authoritative. A changed underlying method gets a fresh wrapper. Exceptions restore original methods, and normal teardown/context recovery remain covered by lifecycle tests.

The real pinned-engine fixture records **101 distinct release-function identities before versus 2 after over 100 stable updates** (including the original method). The real tile/RTT tests also verify look-away retention, identical texture reuse on return, source-revision invalidation, off-screen invalidation, bounded eviction, focus-first refresh, vector refresh budgeting, multiple drape stacks, context restoration, retry cleanup and visible-texture preservation. GPU allocations are controlled in that fixture; it does not measure phone drawing speed.

## 3. Skip unused transition scaffolding

When no cover transition is held and the desired tile membership is unchanged, the adapter now bypasses construction of transition maps, sets and helper closures. Ordered equality takes a direct fast path; an order-only change retains the original membership-based behavior. Changed membership or held transitions executes the existing transition algorithm. Source/texture preparation, native updates and focal ordering still run.

The stable-update fixture changes **Map/Set constructions from 1,000 to 200 over 100 updates**. Both paths call native update exactly 100 times. No existing imagery/DEM readiness or dirty-texture invalidation is suppressed.

## Why these changes were selected

They remove work without delaying visible uploads, changing quality, increasing memory budgets, replacing the network path, or adding another speculative cache. The existing source-member fingerprint checks and DEM-only invalidation suppression are already precise; this release preserves them. A new GPU upload scheduler or alternative browser delivery path would need device evidence and separate validation rather than being bundled into these CPU changes.

## Validation and limits

See the versioned APK validation record for final test counts. Unit tests include real pinned-engine CPU lifecycle checks against both the unmodified website adapter and the generated app adapter, plus explicit exception cleanup and ownership tests. Browser workflow/transport tests exercise real WebGL, routes, sheets, offline selection, Topo detail, verified overview reads and diagnostic export. Android unit tests, lint, instrumentation-test compilation, signing and APK-module comparisons run separately.

No physical phone benchmark or proof of parity with Chrome is claimed. The new `coordinateIndexBuilds`, `coordinateIndexHits`, `coordinateIndexReorders` and `stableTransitionSkips` counters are included in residency statistics. Existing diagnostic component timings can show whether source selection, terrain selection, texture preparation or Direct planning still dominates on the phone.
