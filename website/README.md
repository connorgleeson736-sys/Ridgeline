# Bushline Maps website

The site for bushlinemaps.co.nz: the home page, support, privacy and credits
pages, and the web map (`/map/`), a browser version of the Android app's map
without the offline features. It is a static site, built for Cloudflare Pages.

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

## Data

- `data/walk-facts.json`: DOC's stated distance, time, difficulty and dog
  rules for every walk, from the walks' DOC pages (browsers cannot read those
  pages). Refresh now and then with `node tools/walk-facts.mjs` (it only fetches
  walks it does not have; delete the file to refetch all of them).
- Overview imagery (zooms 5 to 12) comes from the same public bucket as the
  app, which allows cross-origin GET requests. For heavy traffic, serve it from
  a custom domain on that bucket rather than `r2.dev`, and set `OVERVIEW_URL`.

## Speed

The map uses the old website's measured MapLibre settings (tile LOD, a pixel
budget for the drawing buffer, no antialiasing), cancels tiles that leave the
view, loads DOC data nearest the centre first, and does routing, elevation
and search on worker threads. Scripts, styles and imagery are cached for a
year under content-hashed paths. Pages carry no web fonts or third-party scripts.
