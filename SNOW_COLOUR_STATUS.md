# LINZ snow and rock colour — 18 October 2026 Melbourne

Later the same day, `ALPINE_OVERVIEW_STATUS.md` adds precomputed low-zoom Cook
imagery using these same colour settings. At those coordinates the natural/raw
Esri variants are already baked, avoiding runtime regional composition. Fine
imagery and the grading settings described here remain unchanged.

Applies to the default **Original MapLibre viewer**, when Natural imagery colour
is enabled. LINZ's photography, DEM, HLOD, light direction and cache budgets are
unchanged. The optional Three.js renderer keeps its existing appearance.

The existing native raster shader now uses LINZ saturation -0.25, contrast 0.26,
brightness minimum 0.07 and maximum 1.0. These are one consistent set of uniforms
at all zooms: brighter, less grey snow and slightly deeper dark rocks. There is
no new shader, viewer draw pass, generated photograph, image upscaling or processing
of original LINZ tiles. Disabling Natural imagery colour restores the ungraded
images, including the regional Esri patches.

## Esri patch protection

The reviewed Esri gaps are composited into the LINZ source, so a stronger LINZ
grade would otherwise clip their already-bright snow. A reversible affine
counter-transform is applied **only to those Esri pixels**, before blending.
It targets the unchanged Satellite layer's native grade (-0.12 saturation,
0.015 contrast, 0–0.99 brightness) after the LINZ shader runs.

`ridge-grade=snow-v1` separates graded/regional output in the existing byte
cache. It is stripped before contacting LINZ. Outside reviewed gaps, the original
LINZ response still passes through without decoding or encoding. In a full gap
tile, one Esri request is still sufficient; the existing worker now decodes,
counter-grades and losslessly encodes that tile once per cache miss. Boundary and
overzoom jobs reuse their existing canvas. A CPU-oriented canvas avoids adding
unnecessary GPU readback for the small pixel correction. Two-job concurrency,
expiry, cancellation and memory budgets are unchanged. No repeated frame work
or extra source layer was added. Reports expose `esriColourCompensations`.

The arithmetic loop measured about 0.7 ms per 256² tile on this host. This excludes
decode/encode and is **not** a device/browser latency or FPS result. Regional
cached PNGs may use more compressed-cache space than raw Esri JPEGs; the byte
limit remains authoritative. Fully saturated synthetic RGB extremes can differ
by up to 7 levels after gamut clipping; the inspected authentic Esri sample's
maximum difference was 0.625 of 255, mean 0.287. Alpha is unchanged.

## Evidence and recovery

`SNOW_COLOUR_VALIDATION.json` records source XYZ/hashes and quantitative checks.
`qa/snow-colour-2026-10-18/comparison.png` shows previous LINZ, adjusted LINZ and
the Esri reference at matching Cook coordinates. It is review evidence and is
not deployed. In that 1.7 km sample, heuristic snow median RGB changed from about
(178,182,182) to (201,206,206); dark neutral rock brightness fell 6.7%. Any-channel
highlight clipping was 0.34%, shadow clipping 0.051%. These percentages are local
sample results, not all-NZ guarantees. Snow masks are simple photometric sample
selectors, not authoritative snow/rock classification.

Different aerial survey dates and sun angles prevent an exact per-location
match. This moves LINZ closer to the preferred Esri snow appearance while
preserving its terrain detail; do not claim identical snow colours everywhere.

Run `node checks/snow-grade.cjs` and the regional imagery/blend/worker checks.
To recreate the authentic reference, run `python scripts/imagery/snow-colour-qa.py`
with Pillow and NumPy. `RIDGELINE_PHOTO_CACHE` optionally points to the previous
raw imagery cache; missing samples download with atomic writes. `LINZ_API_KEY`
may override the current public viewer key. Keys and source URLs are not logged.

Base viewer checks (including worker failure and ESM bootstrap), natural toggle,
key replacement, imagery cache retention, diagnostics and optional planner
switching passed. Source image/reference pixels were inspected. A live browser
and GPU screenshot are unavailable; no live visual/FPS verification is claimed.
Use the Sites workflow to build/package/push and preserve owner-private access.

## 18 October — slight final adjustment

Current native LINZ grade is saturation −.25, contrast .28, brightness minimum .075, maximum 1. This is a restrained increment from the previous .26/.07 settings. Inverse Esri compensation is derived from the paint settings and preserves the satellite reference. Rebuilt natural overview assets reuse all 381 saved sources. Cache mode snow-v2; worker and module imports updated. See ALPINE_ATLAS_COLOUR_VALIDATION.json and ALPINE_ROUTES_STATUS.md. Earlier fixed coefficients and snow-v1 references above are historical.

## 18 October — current snow-v3 grade

The current LINZ grade is saturation −.25, contrast .26, brightness minimum .058, maximum 1. These are constants at every zoom. Snow is slightly less bright than snow-v2; Esri compensation derives from the same paint constants. South Island coarse levels are averaged from zoom-13 snapshots rather than the old 10 m mosaic. Actual finer source survey lighting can differ. Earlier coefficients and Cook-only snow-v2 assets are historical. See SOUTH_ISLAND_COLOUR_VALIDATION.json.
