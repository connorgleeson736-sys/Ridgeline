> Superseded by the South Island pyramid. See SOUTH_ISLAND_IMAGERY_STATUS.md. Cook source snapshots remain for provenance.

# Cook imagery overviews — 18 October 2026 Melbourne

Project: `appgprj_6acc83afb6588191aff43dc542d98b5f`.
Origin: https://ridgeline-nz.connorgleeson736.chatgpt.site . Preserve owner-private access.

## Delivery correction after the user's unchanged-imagery report

The first publication contained correct assets, but its loader rejected them:
an authenticated request to the deployed Cook WebP returned HTTP 200 with the
expected SHA-256 and **`Content-Type: application/octet-stream`**. The client
required `image/*`, silently selected normal LINZ fallback, and therefore kept
showing the old 10 m imagery. The earlier source/pixel and synthetic-header
fixtures did not verify that production response contract.

The loader now checks the actual RIFF/WEBP container, declared length and image
chunk, accepts valid WebP regardless of MIME, and returns a normalised
`image/webp` response to the tile cache. HTML, HTTP failures and malformed
containers still fall back safely. This is a small zero-copy header check, with
no decode, additional request or image generation. All 130 real assets pass.
`overviewGenericContentType` records accepted generic-MIME responses.

`OVERVIEW_DELIVERY_VALIDATION.json` records an authentic production download
passed through the actual registered `ridge` protocol and byte cache: the
downloaded image is returned, its second use is cached, and no LINZ fallback is
requested. `checks/overview-routing.cjs` now uses real photo bytes and the
production octet-stream header, and also rejects HTML falsely labelled WebP.
HTML/bootstrap versions advance to v17 and the regional module to v4. An already
open page needs one reload to execute the correction. No source pixels or
precomputed files need rebuilding. A live browser/GPU replay is still unavailable.

## Result and geographic scope

Zooms **0–12** near Cook now have precomputed coarse copies of the actual
zoom-13 photography. The renderer still selects coarse tiles for distant
mountains; it no longer needs a different photograph merely because those
tiles drop below zoom 13. Original zoom-13 and finer requests, elevations,
routes, camera controls, HLOD, shading and cache budgets are unchanged.

The source is 16×16 zoom-13 tiles, x7960–7975 / y5192–5207, approximately 57 km
square around Cook. Bounds: west 169.8046875, east 170.5078125, north -43.32517768,
south -43.83452678. A smooth two-source-tile (~7 km) inward feather gradually
joins the ordinary basemap at the edge; the central roughly 43 km is unfeathered.
The previously reviewed Esri gaps are included with the same geographic mask.
No new gap or Esri replacement area is introduced.

Only imagery tile coordinates covering this area are replaced. At very low
zooms those tiles also contain other parts of NZ/world: their outside-feather
pixels retain the original LINZ **snapshot** used during this build. Other tile
coordinates continue to use live LINZ. This is not an all-NZ overview rebuild.
Future source survey updates require an explicit rebuild of this dated snapshot.

## Assets and colour

There are 98 tile coordinates across 13 levels, with raw and natural-colour
variants. Identical files are deduplicated by content hash: **130 lossless WebP
files, 10,181,120 bytes total**. They load individually on demand, not all at
startup. Lossless encoding prioritises exact colour/detail retention; these can
be larger than LINZ's lossy coarse images. No browser-side source-tile fan-out,
pyramid assembly, extra viewer source/draw pass, or GPU allocation scheme is added.

Recursive 2×2 area averaging uses premultiplied alpha and unbiased rounding to
avoid dark edge fringes and cumulative brightness drift. LINZ shader grading
remains runtime-only. Natural variants contain the existing counter-transformed
Esri pixels, preserving their appearance through that shader. Raw variants keep
the ungraded option functional. All encoded files round-trip to identical RGB
pixels; there is no AI imagery, sharpening or invented geographic content.

`public/alpine-overviews.js` maps coordinates/colour mode to immutable asset
URLs. `regional-imagery.js` checks it before normal LINZ/Esri routing. A hit
loads one 256² image and uses the existing bounded byte/texture caches. Static
images use a six-hour in-memory cache TTL ceiling. A missing, HTML or failed
asset safely falls back to the ordinary pipeline, with a 30-second retry
cooldown. Cancellation never starts unnecessary fallback work. Diagnostics
expose `precomputedOverviews` and `overviewFallbacks`.

## Persistence and exact recovery

The reproducible builder/configuration was checkpointed first in source commit
`06fea6cf5af53d5f5cd7936743255230aa952bfc`. All 381 original source snapshots,
generated output hashes, completed tasks, assets, tests and this document are
retained in the site's source repository at the completed milestone.

Files:

- `scripts/imagery/overview-config.json`: fixed extent, levels and blend width.
- `scripts/imagery/precompute-overviews.py`: resumable fetch/build entry point.
- `ALPINE_OVERVIEW_PROGRESS.json`: source/output tasks, hashes and dependencies.
- `qa/alpine-overviews-2026-10-18/source/`: immutable original photo snapshots.
- `ALPINE_OVERVIEW_VALIDATION.json`: build, size and core-filter results.
- `ALPINE_OVERVIEW_PIXEL_VALIDATION.json`: independent area-average checks.
- `qa/alpine-overviews-2026-10-18/`: before/after and finer-reference comparisons.
  QA/source snapshots are not deployed; only the overview files and routing ship.

With Python, Pillow, NumPy and Node available, run from the repository:

```bash
python scripts/imagery/precompute-overviews.py --stage fetch
python scripts/imagery/precompute-overviews.py --stage build
python checks/overview-assets.py
node checks/overview-routing.cjs
```

An optional `LINZ_API_KEY` overrides the existing public viewer key. Keys and
source URLs are never written to progress/logs. Downloads and manifests are
atomic and fsynced. Fetch validates dimensions/checksums and reuses valid files;
failed/missing/corrupt tasks can resume. Build validates sources, recomputes the
small in-memory hierarchy and reuses matching content-addressed outputs. A
recovery check verified all **381** sources reused with network calls forbidden.
Config/mask/colour-code changes deliberately fail the fingerprint check: for
an intentional update preserve the old manifest/snapshots, choose a new asset
version and rebuild both variants; never overwrite an existing content hash.

## Validation and limits

All 381 source requests succeeded (354 LINZ including baseline overview tiles,
27 Esri within the existing reviewed gaps). The zoom-13 Cook photo was visually
compared with an authentic downsampled zoom-14 reference, confirming the same
photography. The new 1.7 km Cook coarse sample differs from an independent
zoom-13 area average by at most **0.5 RGB level**, mean 0.241/255. The previous
10 m sample differed by mean 76.01/255. These are sample pixel differences,
not a claim about every location or rendered browser pixel.

Outer-edge zero-opacity pixels match the baseline snapshot exactly. Interior
zoom-12 to zoom-11 averaging and both colour modes pass. All original/source and
output hashes, dimensions and lossless round trips are checked. Runtime tests
cover one-request routing, raw/natural selection, no worker/API fan-out,
out-of-area/z13+/DEM preservation, cache reuse, asset failure/HTML fallback,
cooldown and cancellation. Planner/bootstrap, worker success/failure, colour,
regional blending, imagery retention/recovery and diagnostics regressions pass.
Production checks verify that every indexed image ships with its content hash.

Existing survey boundaries or genuine poor-coverage patches already present in
zoom-13 photography can still be visible. The outer fade deliberately retains
some original overview imagery. This addresses the zoom-driven 10 m source
switch, not all provider seams. No live browser, device FPS or GPU screenshot
is available. Publish only the exact verified Sites archive/pushed source, and
use the terminal deployment result to confirm publication.
