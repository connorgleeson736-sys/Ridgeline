# Cook imagery colour continuity — 19 October 2026

The Original viewer now prioritises a small, lossless imagery pyramid built from
36 authentic LINZ zoom-15 photographs over the approximately 5.3 × 5.3 km summit
area. Those photos share the finer survey's radiometry; one matched z19 sample
checks the source rather than assuming equal colours at every LINZ zoom.
Zoom 15–19 continue to request the original photographs. Existing constant
snow-v3 raster paint, terrain geometry, HLOD selection, caching, routes, profiles
and optional Three renderer are unchanged.

## Why this approach

Geographically matched LINZ samples jump in brightness between z14 and z15.
The source images also differ in snow/shadow patterns. A global exposure curve
would change good areas and cannot accurately turn one survey into another.
Instead, these overviews contain area averages of the authentic finer images.
There is no generated imagery, AI processing, new shader, per-frame processing,
runtime image decoding/compositing pass, additional source layer or larger
cache budget. A normal cached tile request loads one replacement WebP.

This correction is deliberately local to the verified Cook footprint. Other
South Island imagery still uses the existing durable z13-derived pyramid. It
is not a calibrated all-NZ colour correction. Extending the same method needs
finer source photos for each additional region; a single global RGB adjustment
is not reliable across different aerial surveys.

## Edges and protected imagery

The high-resolution colour overlay feathers into unchanged surroundings over
32 z15 source pixels (about 28 metres). The existing reviewed West Coast mask
excludes Esri pixels from the LINZ replacement. Raw and natural variants keep
Esri's existing inverse compensation. Premultiplied alpha area averaging carries
this footprint consistently down the pyramid. No old South Island R2 object,
range index or static photo is modified or deleted.

`public/cook-reference-overviews.js` indexes content-addressed local WebPs.
`ridge-reference=cook-reference-v1` versions the existing byte-cache key and is
stripped before any LINZ request. Source requests outside the index and fine
source levels pass through. Failed replacements reject so the native hierarchy
can retain its ready parent rather than substituting a different-colour survey.
The report's regional statistics expose `referenceOverviews`.

## Recovery and validation

All source snapshots, saved original bases, build manifest and comparison are in
`qa/zoom-colour-2026-10-19`. Assets and implementation are Git-backed through the
Sites workflow. Rebuild with `python scripts/imagery/cook-reference-overviews.py`;
provide current Site origin and private bearer as hidden stdin JSON. No secret is
saved. Existing snapshots are reused. Outputs/checkpoints use atomic writes.
Use the private Sites workflow; preserve the LIDAR binding and current audience.

Run `node checks/cook-reference.cjs` and
`python checks/cook-reference-pixels.py`. Their reports record routing, immutable
asset hashes, cache reuse and actual matched-footprint pixel errors. Then run
regional/south-routing, base-viewer and cache-return checks, build with the Sites
helper and run base-production. The production guard verifies every new image
hash and the matched-pixel thresholds. Visual QA uses the actual source image
contact sheet; no live browser/GPU or Chromebook FPS verification is available.

Matched summit-face checks at z13/z14 reduce mean absolute raw RGB error from about 38/255 to 0.24–0.35/255 relative to the finer photograph at the same ground footprint. All 30,959 tested fully masked Esri pixels remain identical. The release contains 37 replacement coordinates, 64 unique WebPs, 4,811,208 bytes; these are streamed individually, not loaded together. See the two COOK_REFERENCE validation reports for scope and caveats.
