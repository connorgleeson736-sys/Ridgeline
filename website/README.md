# Bushline Maps website

The site for bushlinemaps.co.nz: the web map at `/` (a browser version of the
Android app's map without the offline features), and the about, support,
privacy and credits pages. Old `/map/` links redirect to `/`. It is a static site, built for Cloudflare Pages.

## Build and test

```bash
node build.mjs          # writes dist/
node --test test/*.test.mjs
```

`build.mjs` needs no packages. It reads the app's map style, place names,
overview index and compiled walks from `native-wgpu/`, `capacitor/` and
`public/`, so it must run from a full checkout of this repository.

## Cloudflare Pages

- Root directory: `website`
- Build command: `node build.mjs`
- Output directory: `dist`
- Environment variables:
  - `LINZ_API_KEY`: the LINZ Basemaps key the site uses. Restrict it to
    `bushlinemaps.co.nz` in the LINZ Basemaps key settings. Without it the
    build uses the app's key.
  - `SITE_LEGAL_NAME`: the business's legal name, for the footer and
    privacy policy.
  - `SITE_EMAIL`: the support address (defaults to support@bushlinemaps.co.nz).
  - `BETA_URL` and `PLAY_URL`: the Play closed-test opt-in link and the
    store listing, once they exist. Until then the buttons read "coming soon".
- Custom domain: add `bushlinemaps.co.nz` and `www.bushlinemaps.co.nz`.
- `functions/api/doc-alerts/[guid].js` (a Pages Function) relays DOC's current
  alerts for a walk or hut (`worker/doc-alerts.js`): browsers cannot read DOC's
  alert service from another site. Cached at the edge for 10 minutes. The test
  copy's Worker serves the same at `/api/doc-alerts/`.

## Data

- `data/walk-pages.json.gz` and `data/walk-facts.json`: every DOC walk's page
  (summary, introduction, sections and photos) and its stated distance, time,
  difficulty and dog rules. Browsers cannot read DOC's pages, so they are
  compiled here; refresh now and then with `node tools/walk-pages.mjs`
  (`FRESH=1` refetches all). DOC content is CC BY 4.0, credited on the site.
- `data/te-araroa.json` and `data/te-araroa-sections.json.gz`: Te Araroa from the
  Te Araroa Trust (CC BY 4.0, credited on the site): the trail from its Tracks
  GPX, its 93 sections and each section's notes from teararoa.org.nz, and huts,
  campsites and holiday parks from its Google Earth file. The Trust refreshes its
  files in November, February, May and August: `node tools/te-araroa.mjs`
  (set `TA_SEASON`, e.g. `2027_28`, for a new season). Section status and alerts
  are live, relayed by `functions/api/te-araroa/` (`worker/te-araroa.js`).
- `data/te-araroa-resupply.json`: Te Araroa's usual food stops (supermarket, small
  store, or send-a-box), hand-kept from walkers' published resupply guides; the
  tool places them on the trail and adds the shops the Trust's notes name. Check
  it each season. `TA_CACHE=<folder>` keeps the tool's downloads between runs.
- The shops and cafés at each food stop come from OpenStreetMap (ODbL, credited
  on the site) through its Nominatim search, one request a second; the tool takes a
  few minutes for them.
- `data/gaps.json` (built from the app's gap atlas) marks where LINZ has no
  photography; Esri World Imagery is drawn there, as in the app. Esri's terms
  for commercial use need checking before launch.
- Overview imagery (zooms 5 to 12) is the app's, from its bucket, served
  edge-cached by the test copy's Worker (`/tiles/<pack>/<offset>.webp`, immutable;
  `/packs/<zip>`, resumable): about 0.05 s a tile against 0.3 s from the bucket's
  uncached, rate-limited `r2.dev` address. The app's `map-defaults.json`
  `overviewUrl` points there and the build takes it from there (`OVERVIEW_URL`
  overrides it; the build warns on an `r2.dev` address). When the site has its own
  Cloudflare zone, a custom domain on the bucket is the alternative.

### Aerial shading corrections

LINZ darkens its aerial tiles up to zoom 14 with an 8 m hillshade and texture
shade, then stops, so the photos would brighten suddenly at zoom 15. The site
takes the shading back out (`src/js/workers/imagery.js`) using corrections made
once ahead: both layers combined into greyscale blocks of 4×4 tiles (zooms 0–14,
about 7,800 blocks, about 600 MB), kept in the overview bucket and served
edge-cached at `/shade/v1/`. The shading never changes, so this only needs
redoing if LINZ changes those layers.

    python3 -I tools/shade-corrections.py OUT_DIR 14     # ~1.5 h, resumable
    CLOUDFLARE_ACCOUNT_ID=… CLOUDFLARE_API_TOKEN=… node tools/upload-shade.mjs OUT_DIR

The upload writes `data/shade-index.json`; rebuild and deploy after it. A zoom
missing from the index falls back to LINZ's own shading layers (slower).

## Speed

The map uses the old website's measured MapLibre settings (tile LOD, a pixel
budget for the drawing buffer, no antialiasing), cancels tiles that leave the
view, loads DOC data nearest the centre first, and does routing, elevation
and search on worker threads. Scripts, styles and imagery are cached for a
year under content-hashed paths. Pages carry no web fonts or third-party scripts.

## Test copy (no domain)

`tools/deploy-test.mjs` uploads `dist/` to an R2 bucket (`bushline-site`) and
serves it with `worker/site.js` on the account's workers.dev address, applying
the same `_headers` and `_redirects` rules as Pages. Build it with `NOINDEX=1`
so search engines skip it:

```bash
NOINDEX=1 SITE_URL=https://bushline-maps.<subdomain>.workers.dev node build.mjs
CLOUDFLARE_ACCOUNT_ID=... CLOUDFLARE_API_TOKEN=... node tools/deploy-test.mjs
```

## Launch (production)

The same Worker and R2 setup serves the production site under its own names, on the
real domain. Before the first launch:

1. **Domain.** Register `bushlinemaps.co.nz` with a New Zealand registrar and add it
   to the Cloudflare account (free plan): Cloudflare gives two nameservers to set at
   the registrar.
2. **LINZ key.** Ask LINZ for a production Basemaps key for the website (not the
   app's), restricted to the domain if they offer it.
3. **Esri.** The Satellite base, LINZ's few gaps and 22 West Coast overview tiles use
   Esri World Imagery: have the Esri licence in place before launch, or build with
   `ESRI_IMAGERY=0` (no Satellite base or gap filling; the overview tiles stay).
4. **Data permissions.** Confirm with the Te Araroa Trust that their section data,
   notes and alerts may be shown (credited, CC BY 4.0).
5. **Build and deploy** (sign-ups are kept in the private bucket
   `bushline-site-prod-signups`, created on the first deploy):

```bash
LINZ_API_KEY=<production key> SITE_URL=https://bushlinemaps.co.nz OVERVIEW_URL=https://bushlinemaps.co.nz node build.mjs
CLOUDFLARE_ACCOUNT_ID=... CLOUDFLARE_API_TOKEN=... SITE_BUCKET=bushline-site-prod SITE_WORKER=bushline-maps-prod node tools/deploy-test.mjs
```

6. **Domain to the Worker.** In Cloudflare: Workers & Pages → `bushline-maps-prod` →
   Settings → Domains & Routes → Add custom domain: `bushlinemaps.co.nz` (and
   `www.bushlinemaps.co.nz`). Cloudflare issues the certificate.
7. **Search.** Submit `https://bushlinemaps.co.nz/sitemap.xml` in Google Search
   Console. (Production builds allow indexing; only `NOINDEX=1` builds block it.)

Sign-ups ("Get notified", `worker/notify.js`): one JSON object per address in the
sign-ups bucket, `signups/<sha256 of the address>.json` with the address, the phone
chosen and the date. Download them from the R2 bucket in the Cloudflare dashboard.

### Pictures

The app page's pictures are rendered from the map itself (`?promo`: no controls,
full screen density, imagery tiles to match), three times their size on screen and
saved at twice it:

```bash
SITE=https://bushline-maps.<subdomain>.workers.dev NODE_PATH=$(npm root -g) node tools/promo-shots.mjs promo
python3 -I tools/promo-images.py promo src/img/promo
```
