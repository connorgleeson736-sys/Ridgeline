# Remaining differences from the MapLibre-based app, native 0.1.4

The user confirms the APK installs, but new-area imagery still stalls while
coarse coverage is visible. Previous host reproductions establish individual
delay mechanisms; they do not establish the root cause of that phone symptom.
This is a current-code audit, not another benchmark or a new APK.

Reference: refreshed `android-preview-download` at `39431dad`, latest 0.18.0
Base metadata resolves source `d880143418ac46ece98adff0b3b2fbaedf044d46`.
The inspected engine, surface-focus, terrain-streaming, raster-residency and retry
files match that exact source byte-for-byte. Some behaviors below belong to
Ridgeline's adapters around MapLibre, rather than unmodified upstream MapLibre.

## Most relevant remaining gaps

| Behavior | MapLibre-based app | Native 0.1.4 | Consequence / confidence |
| --- | --- | --- | --- |
| Focus on the visible mountain face | `surface-focus.js` raycasts the centre screen ray into terrain, keeps surface and pivot separately, and changes LOD/request priority around the measured hit. | `selection.rs` refines and sorts around `view.center`; Kotlin forwards ranks. Camera picking/recentering exists, but there is no separate surface-hit priority input to the source selector. | It can spend its finest-detail budget near a pivot behind a ridge instead of the surface being watched. Confirmed policy difference; phone contribution unmeasured. |
| Elevation-aware tile covering | The packaged engine's tile-bounds code calls `terrain.getMinMaxElevation(...)` and uses returned bounds when available. Its cover/LOD operates through the terrain-aware hierarchy. | Every planned candidate is tested using the same conservative -100..4000 m prism, even after relevant terrain has loaded. | Conservative false positives can retain/request tiles whose actual terrain is off screen. Do not fix this by guessing a lower maximum: real height bounds need to reach selection. |
| One coherent covering/retention policy | Tile managers maintain in-view tiles, retained parents/children, source load states and out-of-view caches; app adapters order these using surface focus and readiness. | Independent geometry/image plans are augmented with hand-ranked parents: focus parent, four nearby leaves, remaining parents, remaining leaves. Network, CPU and upload stages then apply their own admission decisions. | A local priority fix does not guarantee that the next completed job improves the visible blurry patch. A richer per-tile state/relevance model is still needed. |
| Error recovery | `tile-retry.js` excludes 401/403/404 and aborts, honors retry metadata, and bounds attempts by source-manager lifetime/time window. | Generic task failures use 1 s, 4 s, then 15 s backoff; individual failures are not exposed in the queue snapshot. An unrelated successful upload clears the status message. | A source/decode/preparation error can resemble a loading stall. A 15 s backoff is a concrete candidate, not evidence that it occurred on the user's phone. |
| Residency accounting | Raster/DEM/drape adapters use byte budgets, protect live dependencies and keep bounded off-screen work according to resource state. | Retained CPU payloads have byte budgets, but Android sets renderer GPU residency largely through tile-count budgets. Native has ancestors, sharper descendants and bounded parked work already. | Eviction/working-set decisions differ. This can affect memory pressure/churn, but a larger cache alone is not a cold-area fix. |
| Per-tile diagnosis | `tile-trace.js` records queue, fetch headers/body, decode, transform, adoption/upload and draw stages, with source/tile correlation. | Loading diagnostics exposes useful aggregate logical/underlying HTTP, CPU/GPU queue counts and some HTTP timings. It cannot trace a particular blurry patch to its blocking dependency and first draw. | This is why earlier fixes cannot yet be called a complete diagnosis. Instrumenting a selected stalled tile should precede another speculative concurrency change. |

The source selector's focus and elevation inputs are the most important structural
selection gaps. End-to-end per-tile timing is the most important measurement gap.
Retries deserve checking alongside them because their time scale matches the
reported stall more closely than microsecond CPU optimizations.

## Differences that should not be mistaken for missing features

- Native already has priority queues, foreground/background limits, shared
  cancellable transfers, encoded/decoded/prepared caches, parent/child cover,
  mipmaps, adaptive interiors, GPU backpressure and bounded predictions. These
  should not be listed as absent based on the older 06eed8b migration audit.
- Both apps can wait for multiple provider inputs when composing gap imagery.
  The reference's `gap-imagery.js` uses `Promise.allSettled` for base, regional
  and fine inputs. Native's all-input barrier is therefore not, by itself, a
  demonstrated missing MapLibre capability or explanation of their difference.
- The Capacitor native gateway streams headers/body earlier; native's current
  backend collects complete validated tile bodies. Most image decode/composition
  still requires complete encoded inputs. Streaming alone does not establish a
  ten-second WebP-tile improvement.
- MapLibre's extra render-to-texture/drape machinery is not something to copy
  wholesale into Rust: native samples source textures directly. The reusable
  coverage/readiness policies matter more than reproducing an extra GPU pass.
- Neither copying a global concurrency number nor tightening unknown terrain
  bounds arbitrarily establishes correct visible-tile selection.

## Concrete next investigation

For a stalled patch, record the selected imagery coordinate/target zoom, its
screen relevance and actual surface-hit relevance; current versus retained
ownership; each shared source dependency; queue/admission/header/body/decode/
composition/upload/draw timestamps; and any error/retry deadline. Compare that
with the exact reference app at the same camera and detail settings.

Then feed the actual visible surface hit and measured terrain extrema into the
native selector, retaining conservative fallback for unknown terrain. This
targets what is selected and why before changing transfer limits again. No such
selection change has been implemented by this audit.

## Code anchors

- Reference [surface focus](../../public/surface-focus.js),
  [surface/pivot request ordering](../../public/terrain-streaming.js),
  [raster retention](../../public/raster-residency.js),
  [retry policy](../../public/tile-retry.js), [tile trace](../../public/tile-trace.js).
- Reference generated engine [maplibre-gl.mjs](../../public/assets/maplibre-gl.mjs):
  search `getMinMaxElevation`; native plan [selection.rs](../renderer/src/selection.rs).
- Native [SourceDemand.kt](../android/app/src/main/java/nz/ridgeline/nativeapp/SourceDemand.kt),
  [TileLoader.kt](../android/app/src/main/java/nz/ridgeline/nativeapp/TileLoader.kt),
  GPU budget [lib.rs](../renderer/src/lib.rs),
  retained CPU budgets [RamCacheBudget.kt](../android/app/src/main/java/nz/ridgeline/nativeapp/RamCacheBudget.kt).

No tests, renderer changes or APK rebuild were needed for this audit. The
published 0.1.4 download was independently fetched with an Android browser user
agent: 32,056,520 bytes, matching SHA-256, ZIP CRC and APK signature verification
for Android API 26–30. The user subsequently confirmed installation succeeds;
attention remains on the persistent rendering/loading stall.
