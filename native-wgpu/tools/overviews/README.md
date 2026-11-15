# Overview imagery (zooms 0–12)

The app's zoomed-out aerial imagery for New Zealand: one image per map tile at
zooms 0–12, averaged from LINZ's zoom-13 aerial photography so the whole country
reads as one consistent, exact downsample rather than LINZ's own coarser mosaics.
Zooms 0–8 are bundled in the APK (`assets/assets/south-overviews-v1`,
`assets/assets/nz-overviews-v1`); zooms 9–12 are in packs (ZIPs with one stored
payload) that the app streams tile by tile or keeps whole for offline use.

## South Island (October 2026)

Made by `scripts/imagery/south-island/pipeline.py` for the earlier web app; see
`SOUTH_ISLAND_IMAGERY_STATUS.md` (repository root) for its run, checks and
recovery. In short:

- **Mask**: Natural Earth 1:10 m country polygons (public domain) for the South
  Island, Rakiura and coastal islands, rasterised at 16 px per zoom-13 tile with a
  coastal guard and an offshore fade (`blend-mask.png`). It only controls how
  photography blends into the sea, never terrain.
- **Sources**: every LINZ aerial zoom-13 tile the mask touches (14,864), plus
  Esri World Imagery in 27 reviewed West Coast gaps where LINZ has none.
- **Pyramid**: zoom 12 from the zoom-13 tiles with the mask as alpha, each coarser
  level from the four below by a premultiplied 2×2 area mean; partly covered tiles
  are laid over LINZ's own tile of that zoom. Lossless WebP, round-trip checked.
- **Output**: 5,313 coordinates (raw and natural colour, which differ only by the
  Esri gaps' compensation), 368 MB: zooms 0–8 as 67 bundled images, zooms 9–12 in
  six packs `Ridgeline-Overviews-0.3.0-N-of-6.zip`.

Its mask-building script was not kept. The North Island's mask reproduces it from
the same Natural Earth polygons, with the parameters measured on the South Island's
`blend-mask.png`: land widened by 21 px, Gaussian blur σ 5 px, full alpha from
0.97. Rebuilt that way, the South Island's mask matches the original to a mean
alpha error of 0.023 in its fade band.

## North Island (November 2026)

`north_island.py` does the same locally, resumable at every stage (WORK is
`$RIDGE_NORTH_WORK`):

```bash
python3 north_island.py mask ne_10m_admin_0_countries.geojson
python3 north_island.py fetch --workers 24
python3 north_island.py build path/to/south-island-zips
python3 north_island.py package          # writes ../../assets/overviews/
```

1. **mask** — the North Island and its coastal islands (Natural Earth v5.1.2
   `ne_10m_admin_0_countries`, NZL, polygons north of Cook Strait; not the
   Kermadecs or Chathams), as above. Writes `blend-mask.png` and `config.json`
   (zoom-13 source tiles, output coordinates per zoom).
2. **fetch** — LINZ aerial zoom-13 tiles wherever the mask is not zero, and
   LINZ's tile at coarser zooms wherever an output tile is not fully covered; at
   most 12 requests a second (the LINZ key in `map-defaults.json`).
3. **build** — the pyramid exactly as the South Island's (same averaging code).
   Tiles both islands share (the coarse zooms over Cook Strait and the whole
   country) are the North Island's overlay laid over the South Island's finished
   tile, so nothing of the South Island changes; elsewhere over LINZ's tile. The
   two islands' masks do not meet (Cook Strait is wider than both fades). No Esri.
4. **package** — zooms 9–12 into packs `Ridgeline-Overviews-NI-1.0-N-of-M.zip`
   (each under 63 MB, the app's limit) in WORK/packs, and the committed
   `native-wgpu/assets/overviews/`: `north-island.json` (tiles, coarse images,
   payloads, packs) and the zoom 0–8 images in `nz-overviews-v1/`.
   `tools/package-assets.mjs` merges these over the South Island's when it builds
   the app's assets (shared coordinates take the North Island's tiles; the South
   Island's own index in `public/` and `capacitor/` is never changed).

The November 2026 run: 10,495 zoom-13 sources (11,915 requests with the coarser
LINZ tiles), 3,838 coordinates (2,756 at zoom 12 … one at zoom 0), 103 of them
shared with the South Island; zooms 9–12 are 3,786 tiles in four packs (244 MB),
zooms 0–8 are 52 bundled images. Checked by eye: a zoom-7 mosaic of the island,
the shared zoom-9 tiles over Cook Strait and zoom-12 tiles on Ruapehu.

## Hosting

`publish.py` builds the hosted tree from all packs (`tiles/<payload>/<offset>.webp`
for each tile, and `packs/<zip>`), checks it, and uploads to Cloudflare R2:

```bash
python3 -I publish.py build ZIP_DIR ../../android/app/src/main/assets SITE_DIR
CLOUDFLARE_API_TOKEN=... python3 -I publish.py upload SITE_DIR ACCOUNT_ID ridgeline-overviews [ONLY ...]
python3 -I publish.py check https://pub-….r2.dev ../../android/app/src/main/assets ZIP_DIR [all]
```

`ONLY` limits an upload to paths containing a given payload sha or ZIP name (a new
island's files). The app's address is `overviewUrl` in `map-defaults.json`.

## Attribution

LINZ aerial imagery, CC BY 4.0, and its imagery providers; Esri World Imagery and
contributors for the South Island's reviewed West Coast gaps; coastline from
Natural Earth (public domain).
