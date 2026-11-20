# Capacitor 0.20.3 — border loading and Rolling Pin outlines

The user confirmed that 0.20.2 was installed, that blurry border tiles occur across New Zealand, and that the irregular Rolling Pin issue includes black dotted lines in the wrong place. The decimal survey-name fix is retained; it was not the entire issue.

## Stop waiting for unused source data

A boundary tile fetched original LINZ, Esri and fine-survey masks, then waited for every input before working out which pixels it needed. At Rolling Pin z17/126961/83596, fine-survey alpha eliminates every Esri pixel. Nevertheless a stalled Esri request held the entire fine LINZ photograph. Worse, an unused saved Esri ancestor could tag that fully detailed LINZ result as a lower-zoom fallback.

The pipeline now prepares the effective alpha mask first. If no Esri pixels remain, it completes the original LINZ image and cancels its Esri subscription. An unused input contributes neither fallback metadata nor expiry. Real mixed tiles release the preparation slot during their network wait, then reacquire a slot only for final composition. Decoded fine masks are closed after preparing alpha; only the photograph and alpha survive the wait. Prepared work retires on cancellation or worker loss. Shared input subscriptions retain their existing independent cancellation semantics. Worker scripts and protocol URLs are versioned together.

This does not bypass missing fine masks or invent pixels when Esri is actually required. Required-source failures retain the prior image and bounded recovery. Worker composition errors retain transient status 503 instead of exhausting the generic-error retry allowance. The no-worker fallback follows the same mask-first flow.

**Controlled replay with real Rolling Pin photograph inputs and a deliberately delayed Esri response:** published 0.20.2 behaviour finished in 5098.6 ms; the new flow finished in 83.1 ms with identical LINZ pixels, no false fallback tag and cancellation of the unused request. With one processing slot, another LINZ-only result finished in 26.3 ms while a mixed tile still waited for Esri. Mixed-source output was byte-identical to the baseline compositor. The five-second delay is injected: these are dependency/work proofs, not measured phone latency or Wi-Fi performance.

## Route original LINZ tiles directly

The old routing predicate tested polygon-edge bounding boxes and used a coarse padded footprint before deciding whether any replacement pixel was possible. Some wholly LINZ tiles consequently depended on unrelated Esri and survey requests. An exact segment/rectangle test now admits original LINZ immediately only where the replacement polygon has zero area in the requested tile. Conservative fine-alpha checks remain for every other tile; no Esri-region routing is weakened.

40,320 source/zoom cases found 1,045 previously blocked pure-LINZ tiles. Independent Shapely intersections verified zero replacement area for every changed tile, and no other routing classes changed. This path avoids donor requests and composition entirely. The routing cache revision is bumped; original provider HTTP responses and saved regions remain usable.

## Replace nominal dotted outlines with measured source selection

The old display used nominal capture-polygon outlines before runtime alpha vetoes. Around Rolling Pin it contained 93 nearby closed outlines, including many microscopic internal rings that did not usefully describe the displayed imagery. Display contours now follow the existing, measured source masks, with internal tile edges dissolved and topology preserved. There are two nearby outlines in the same comparison area. The new countrywide file is 261,630 bytes, compared with 281,906 before.

The raw contours independently reproduce all 41,287,680 original mask pixels. **Only the display overlay changes.** All imagery masks and original photo/DEM data remain unchanged. Display outlines omit regions below 10,000 m² (the existing nominal-gap threshold) and simplify by at most one z13 mask pixel. This gives an approximate source border at closer zooms, roughly 14 m source sampling near Rolling Pin; it is not survey-grade or a claim of exact boundaries at z19. The old nominal file remains as a reference; the visible overlay uses the measured file. The generation pipeline emits the new overlay on future atlas rebuilds.

[Geometry comparison on the same unchanged photograph](validation/borders-0.20.3/rolling-pin-outline-comparison.png). Rolling Pin coordinates were checked against the [NZ Alpine Club entry](https://climbnz.org.nz/nz/si/aspiring/haast-range/rolling-pin).

## Focused verification and limits

Five focused JS checks and the existing seam-source check pass. A real Chromium Worker/Canvas replay covers the five-second unused-input wait, mixed-image equality, cancellation, worker loss, encoded/no-worker output, and required/unused input failures. A portable deterministic replay is available as `node capacitor/scripts/border-dependency-browser.mjs` after the Base web build; optional `RIDGELINE_BORDER_FIXTURES` selects separately obtained real image fixtures. Three headless real-Canvas photo cases preserve every fine-covered LINZ pixel in both encoded and bitmap output. Raw-mask contour verification and the independent countrywide geometry check pass.

No physical phone was available. A synthetic high-zoom terrain replay produced an empty view and was rejected as evidence; no full live-terrain reproduction or handset p50/p95/FPS claim is made. The fixes address proven dependency, routing, recovery and overlay defects. Their effect on all of the user's remaining blur still needs handset confirmation.

0.20.3 Audit (code 26) uses the existing Capacitor Audit package and signing identity, retaining data and the 0–1 GB original-response cache slider. Full imagery/terrain detail, the MapLibre engine, shaders, source LOD and route-planning algorithms remain unchanged. Final APK/source/download records are in [validation](validation/borders-0.20.3/).
