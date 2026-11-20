# Ridgeline Capacitor — additional latency findings

Reviewed 4 November 2026. Repository `connorgleeson736-sys/Ridgeline`, branch `android-performance-0.18.0`, commit `d880143418ac46ece98adff0b3b2fbaedf044d46`. The branch head was rechecked during this audit. This is a findings package, with isolated candidate experiments. No application source was changed or committed.

The strongest demonstrated rendering opportunity is eliminating large transparent overlay textures in Original + Direct mode. The most consequential recovery bug is a coarse parent image being accepted as a successfully loaded sharp child. A bitmap delivery path also offers a substantial architectural reduction for composited imagery. These affect different workloads and their benefits must not be added together as an overall speed estimate.

There was no Android handset or WebView available. Tests use the shipped JavaScript engine and app modules with controlled fixtures. GPU operations, image decoding, and Canvas pixels are stubbed where explicitly described. Counts are reproducible. They are not handset latency measurements.

| Priority | Finding | Reproduced evidence | Scope |
| --- | --- | --- | --- |
| 1 | Skip transparent overlay RTTs with no drawable coordinates | 24 large RTT acquisition calls and 24 mipmap calls become zero. Later route arrival still composes correctly | Original + Direct, on affected cold/new patches |
| 2 | Preserve fallback provenance and retry sharp children | HTTP-success parent crop becomes `loaded`, with no expiry call and no retry record | Android parent fallback after offline/provider failure, in either MapLibre mode |
| 3 | Deliver finished composites as bitmaps | Removes one PNG encode, one encoded-buffer read, and one output decode per composite in the control-flow fixture | Regional boundary/overzoom/compensation paths that actually composite |
| 4 | Avoid constructing unused touchmove events | 120 one-finger events perform 240 unnecessary unprojects; guarded dispatch performs zero | Normal panning without map-level touchmove subscribers |

## 1. Transparent overlay textures defeat part of Direct rendering

[maplibre-direct-sampling.js, emptyOverlay](https://github.com/connorgleeson736-sys/Ridgeline/blob/d880143418ac46ece98adff0b3b2fbaedf044d46/public/maplibre-direct-sampling.js#L39) requires an array of source coordinates for every line/fill overlay. Native `prepareForRender` creates no per-patch entry when a source has no related visible coordinates. The helper interprets that absence conservatively and requests normal composition.

However, native `renderLayer` draws no line/fill when its coordinate list is absent. Direct mode skips the base imagery and background during composition. The result is an allocated, cleared, mipmapped, transparent overlay texture that contributes no pixels.

The test runs the actual RTT preparation and layer-flush code, the Direct adapter, and the Capacitor-optimized residency wrapper. It supplies 24 terrain patches with ready imagery and a line source with no renderable coordinates. GPU submission is replaced by counters.

| Operation | Shipped | Narrow candidate |
| --- | ---: | ---: |
| First-frame large RTT acquisitions | 24 | 0 |
| First-frame mip generation calls | 24 | 0 |
| First-frame Direct terrain draws | 24 | 24 |
| First-frame actual route draw calls | 0 | 0 |
| Cumulative RTT acquisitions after one real route tile arrives | 25 | 1 |

At the default terrain/imagery detail settings, [native-residency.js](https://github.com/connorgleeson736-sys/Ridgeline/blob/d880143418ac46ece98adff0b3b2fbaedf044d46/public/native-residency.js#L366) produces a 1025-square RTT. The exact RGBA8 mip payload is 5,600,600 bytes per texture, or approximately **128.2 MiB for 24**. This is a nominal texture payload estimate, excluding driver padding and shared attachments. Actual Android memory residency was not measured. Direct mode still uses its shared 1×1 transparent substitute.

The experiment treats missing coordinates as empty only when the source manager and coordinate map exist and there is no existing overlay RTT. It also checks that a later nonempty route gets an RTT, temporarily missing coordinates preserve an existing overlay, and a confirmed empty bucket removes old route pixels.

Before shipping, integrate Direct coverage with residency continuity. The existing wrapper uses positive RTT byte cost in retention decisions. Zero-RTT Direct patches need their own valid coverage/ownership state, and transitions to composed rendering must preserve imagery. Also avoid counting a Direct-only draw as an RTT composition or rebuilding readiness information when no RTT was produced.

Expected effect is lower cold-pan/refinement allocation stalls and less memory pressure. These 24 allocations are not repeated every steady frame. Original remains the default renderer, so this is not a universal default-mode gain.

## 2. A coarse fallback can suppress sharpening indefinitely

[TileGateway.java](https://github.com/connorgleeson736-sys/Ridgeline/blob/d880143418ac46ece98adff0b3b2fbaedf044d46/capacitor/android/app/src/main/java/nz/ridgeline/mobile/TileGateway.java#L85) returns a cropped ancestor as HTTP 200 with `Cache-Control: no-store` when the requested tile cannot be obtained. It does not convey the ancestor's zoom or that the requested sharp tile remains unavailable. [TileResampler.java](https://github.com/connorgleeson736-sys/Ridgeline/blob/d880143418ac46ece98adff0b3b2fbaedf044d46/capacitor/android/app/src/main/java/nz/ridgeline/mobile/TileResampler.java#L9) decodes, crops/enlarges and PNG-encodes that ancestor for the requested child address.

The byte cache correctly avoids storing `no-store` bytes. That does not expire the renderer's loaded GPU tile. The app sets `refreshExpiredTiles:false`, and native `RasterTileSource.loadTile` accepts the successful image as a loaded child. [tile-retry.js](https://github.com/connorgleeson736-sys/Ridgeline/blob/d880143418ac46ece98adff0b3b2fbaedf044d46/public/tile-retry.js#L17) clears records for loaded tiles and only recovers recorded errors.

The test routes a simulated 200/no-store body through the real byte cache, engine image queue, native raster load, and retry logic. Image decoding and GPU upload are stubbed. The result is a loaded child, zero compressed-cache entries, zero expiry calls, and zero recovery attempts. A camera/source reload or eviction can eventually replace it, but there is no bounded sharpening deadline from this path alone.

The first fix should attach fallback provenance, propagate it through the transport/cache/protocol layers, keep the coarse image visible, and schedule bounded, visible-first revalidation of the requested child. Connectivity recovery should wake those entries. Respect offline-only mode, cancellation, retry-after and source lifetimes. Merely adding an HTTP header without changing the JS ownership/retry path is insufficient.

A larger follow-up is to load an ancestor once under its true tile ID and let native raster ancestry supply child coverage, instead of manufacturing many child PNGs. For example, one ancestor covers 64 children three zoom levels below it. Reusing one 256-square texture could avoid up to 64 crop/encode/upload jobs in that controlled coverage case. This is an architectural estimate, not a tested app gain. Start with imagery; encoded elevation and continuity require separate validation.

Expected effect is bounded recovery to sharp imagery after outages instead of potentially remaining blurry until another action. It does not increase healthy-network frame rate.

## 3. Keep completed imagery out of the PNG round trip

[regional-blend.js](https://github.com/connorgleeson736-sys/Ridgeline/blob/d880143418ac46ece98adff0b3b2fbaedf044d46/public/regional-blend.js#L51) finishes Canvas composition, then calls `convertToBlob(image/png)` and `arrayBuffer()`. [The worker](https://github.com/connorgleeson736-sys/Ridgeline/blob/d880143418ac46ece98adff0b3b2fbaedf044d46/public/regional-imagery-worker.js#L20) returns those bytes. The response/cache/protocol path hands them to the engine, which decodes them back into an image before upload.

The pinned image queue already accepts an ImageBitmap directly. A separate path for visible composited tiles can transfer the finished bitmap to the main thread and feed it into that queue. Persistent byte-cache encoding should run separately with bounded concurrency, or retain validated input bytes and recompose on demand.

The stage-count test executes the actual compositor control flow and the exact queue source embedded in the engine, with mocked Canvas/decoding. Baseline performs two input decodes, one PNG encode, one buffer read and one final decode. The experimental bitmap path keeps the two input decodes and replaces the remaining stages with a bitmap transfer operation. Both input bitmaps are closed.

This is not yet an integrated patch. `regional-imagery.js`, `gap-imagery.js`, the worker message format and `tile-cache.js` currently expect byte bodies. Define an owned result type, cancellation/close rules, subscriber fan-out, cache freshness and resource budgets. Pixel equivalence, premultiplication, color handling, context loss and worker failure need browser tests.

Potential time saved per affected tile is the PNG encode + buffer handling + second decode + their queue delay, less bitmap transfer overhead. Unaffected passthrough tiles receive no benefit. No defensible millisecond estimate is available without measuring those stages on Android.

## 4. Touch dispatch performs terrain picking nobody uses

The bundled engine's map-event handler constructs `MapTouchEvent` for every touchmove before camera gesture handlers run. Its constructor unprojects each finger and the centroid. For one finger that is two terrain raycasts at the same position. Most normal panning has no map touchmove listener. [Route editing](https://github.com/connorgleeson736-sys/Ridgeline/blob/d880143418ac46ece98adff0b3b2fbaedf044d46/public/route-interactions.js#L7) installs those listeners only during an active drag.

Guard map touchmove event creation using the engine's existing `map.listens('touchmove')`, which includes parent/one-time listeners. Keep the underlying gesture handlers active. Do not remove preventable touchstart events or route/marker drag behavior.

The test uses the real handler, event class and terrain raycast with a controlled synthetic DEM. Across 120 events the shipped path executes 240 unprojects and 10,588 DEM samples. The listener guard executes zero. Listener, one-time listener and parent forwarding checks pass. The warmed host cost was around 0.02 ms per event in this small fixture, so this is a useful low-risk cleanup, not evidence of a large standalone latency breakthrough.

## Correction to the previous audit

The earlier selection test injected `sourceDataType:'content'` and generalized that result to every image arrival. The actual ordinary tile-completion event from `SourceTileManager._tileLoaded` has no such tag. With a 100 ms interval and 60 simulated frames, real ordinary completion events produce 10 selection updates. Explicit content events produce 60.

Therefore the statement that every ordinary imagery arrival defeats the selection interval was wrong. Withdraw that priority and any speed expectation that depended on it. Metadata/content changes can still force selection by design. The other new findings above are independent of that claim.

## Reproduction and acceptance

Run the included scripts against a checkout of the pinned commit using Node 24. See README.md. They modify module strings only in isolated temporary modules or VM contexts. They do not modify repository source.

For Android validation, replay the same camera path in both Original and Direct, with cold and warm caches, then repeat an outage/reconnection case while keeping the camera still. Measure p50/p95 frame time, ready-image-to-visible latency, composition encode/decode time, large RTT acquisitions, and fallback-to-sharp recovery. Compare identical image/terrain detail, overlays and thermal conditions.

A percentage speedup cannot be inferred from operation counts. The Direct finding removes substantial unnecessary resource work in its trigger case; the fallback finding corrects a missing recovery path; the bitmap path removes avoidable sequential stages. Those are the implementation priorities worth testing on the phone.
