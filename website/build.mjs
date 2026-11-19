// Builds the Bushline Maps website into dist/ for Cloudflare Pages (static).
//
//   LINZ_API_KEY=... node build.mjs        (or read from the app's map-defaults.json)
//
// The LINZ key goes only into the built config, never into the source. Scripts
// and styles are copied into a folder named by their content hash, so they can
// be cached for a year; pages are always revalidated.
import { createHash } from 'node:crypto';
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import vm from 'node:vm';
import { gunzipSync } from 'node:zlib';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, '..');
const src = join(here, 'src'), out = join(here, 'dist');
const appAssets = join(repo, 'native-wgpu/android/app/src/main/assets');

export const SITE = {
  name: 'Bushline Maps',
  // The legal business name for the footer and privacy policy (as on the NZBN / company register).
  legalName: process.env.SITE_LEGAL_NAME || 'Bushline Maps',
  domain: process.env.SITE_URL || 'https://bushlinemaps.co.nz',
  email: process.env.SITE_EMAIL || 'support@bushlinemaps.co.nz',
  // Google Play listing and the closed test's opt-in page, once they exist.
  playUrl: process.env.PLAY_URL || '',
  betaUrl: process.env.BETA_URL || '',
  updated: '19 November 2026',
};

const defaults = JSON.parse(readFileSync(join(appAssets, 'map-defaults.json'), 'utf8'));
const linzKey = process.env.LINZ_API_KEY || defaults.linzApiKey;
const overviewUrl = process.env.OVERVIEW_URL ?? defaults.overviewUrl ?? '';
// r2.dev is the bucket's uncached, rate-limited development address (0.3 s a tile
// against 0.05 s through the edge-cached Worker): never ship it by accident.
if (/\.r2\.dev\b/.test(overviewUrl)) console.warn(`warning: overview imagery from ${overviewUrl} (uncached r2.dev); set OVERVIEW_URL to an edge-cached address`);
if (!linzKey) throw new Error('No LINZ API key (set LINZ_API_KEY)');
// Esri World Imagery: the Satellite base and LINZ's few gaps (the overview imagery has
// Esri in 22 West Coast tiles too). On by default (an Esri licence is being arranged);
// ESRI_IMAGERY=0 builds the map without the Satellite base and gap filling.
const esri = process.env.ESRI_IMAGERY !== '0';
// The overview imagery with LINZ's shading already taken out (tools/correct-overview.py):
// hosted under /ctiles/, coarse tiles from imagery-corrected/. Off: the originals,
// corrected in the browser like LINZ's own tiles.
const overviewCorrected = process.env.OVERVIEW_CORRECTED !== '0' && existsSync(join(here, 'imagery-corrected'));

rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });

// ---- Scripts and styles, versioned by content ----
const hashOf = (dir) => {
  const h = createHash('sha256');
  const walk = (d) => { for (const f of readdirSync(d).sort()) { const p = join(d, f); if (statSync(p).isDirectory()) walk(p); else { h.update(relative(dir, p)); h.update(readFileSync(p)); } } };
  walk(dir);
  return h.digest('hex').slice(0, 10);
};
// The data files' version, from everything they are made from: their URLs carry it
// (?v=), so a changed file is fetched afresh while unchanged ones stay cached.
const dataVersion = (() => {
  const h = createHash('sha256').update(readFileSync(fileURLToPath(import.meta.url))).update(hashOf(join(here, 'data')));
  for (const f of [join(appAssets, 'topography-style.json'), join(appAssets, 'search/places.tsv'), join(appAssets, 'walks/walks.json'), join(repo, 'capacitor/overview-catalog.json'), join(repo, 'native-wgpu/assets/overviews/north-island.json'), join(repo, 'public/south-overviews.js'), join(repo, 'public/gap-imagery-atlas.js')]) if (existsSync(f)) h.update(readFileSync(f));
  return h.digest('hex').slice(0, 10);
})();
const config = `export default ${JSON.stringify({ linzKey, overviewUrl, playUrl: SITE.playUrl, betaUrl: SITE.betaUrl, version: SITE.updated, dataVersion, esri, overviewCorrected })};\n`;
const jsVersion = createHash('sha256').update(hashOf(join(src, 'js'))).update(config).digest('hex').slice(0, 10);
const cssVersion = hashOf(join(src, 'css'));
const jsOut = join(out, 'js', jsVersion), cssOut = join(out, 'css', cssVersion);
cpSync(join(src, 'js'), jsOut, { recursive: true });
writeFileSync(join(jsOut, 'map/config.js'), config);
cpSync(join(src, 'css'), cssOut, { recursive: true });
cpSync(join(src, 'img'), join(out, 'img'), { recursive: true });
cpSync(join(src, 'fonts'), join(out, 'fonts'), { recursive: true });
cpSync(join(src, 'site.webmanifest'), join(out, 'site.webmanifest'));
// MapLibre, by version (its own path never changes content).
const maplibre = 'maplibre-gl-6.16.0';
mkdirSync(join(out, 'vendor', maplibre), { recursive: true });
for (const f of readdirSync(join(here, 'vendor', maplibre))) {
  let body = readFileSync(join(here, 'vendor', maplibre, f));
  if (f.endsWith('.mjs')) body = body.toString().replace(/\n\/\/# sourceMappingURL=.*\s*$/, '\n');
  writeFileSync(join(out, 'vendor', maplibre, f), body);
}

// Every module the map loads at start, found by following static imports from
// main.js: preloaded together, instead of discovered one level at a time.
const startModules = (() => {
  const seen = new Set();
  const visit = (file) => {
    if (seen.has(file)) return;
    seen.add(file);
    const code = readFileSync(join(jsOut, file), 'utf8');
    for (const m of code.matchAll(/^\s*(?:import|export)\s[^;]*?\bfrom\s*['"](\.{1,2}\/[^'"]+)['"]|^\s*import\s*['"](\.{1,2}\/[^'"]+)['"]/gm)) {
      visit(relative(jsOut, join(jsOut, dirname(file), m[1] || m[2])).split('\\').join('/'));
    }
  };
  visit('map/main.js');
  return [...seen].slice(1).map((f) => `js/${f}`);
})();

// ---- Pages: shared header and footer, site values, versioned asset paths ----
const partials = Object.fromEntries(readdirSync(join(src, '_partials')).map((f) => [f.replace('.html', ''), readFileSync(join(src, '_partials', f), 'utf8')]));
const render = (html) => html
  .replace(/\{\{>\s*(\w+)\s*\}\}/g, (_, name) => partials[name] ?? '')
  .replace(/\{\{site\.(\w+)\}\}/g, (_, key) => SITE[key] ?? '')
  .replace(/\{\{year\}\}/g, '2026')
  .replace(/\{\{dataVersion\}\}/g, dataVersion)
  .replace(/\{\{maplibre\}\}/g, `/vendor/${maplibre}`)
  .replace(/<link rel="preconnect" href="\{\{overviewOrigin\}\}"[^>]*>\n?/g, overviewUrl ? `<link rel="preconnect" href="${new URL(overviewUrl).origin}" crossorigin>\n` : '')
  .replace(/(<link rel="modulepreload" href="\/js\/map\/main\.js">\n)/, (line) => line + startModules.map((m) => `<link rel="modulepreload" href="/${m}">\n`).join(''))
  .replace(/(["'(])\/js\//g, `$1/js/${jsVersion}/`)
  .replace(/(["'(])\/css\//g, `$1/css/${cssVersion}/`);
const pages = [];
const walkPages = (d) => {
  for (const f of readdirSync(d)) {
    const p = join(d, f);
    if (f.startsWith('_') || ['js', 'css', 'img', 'fonts'].includes(f) && d === src) continue;
    if (statSync(p).isDirectory()) walkPages(p);
    else if (f.endsWith('.html')) {
      const rel = relative(src, p);
      mkdirSync(dirname(join(out, rel)), { recursive: true });
      writeFileSync(join(out, rel), render(readFileSync(p, 'utf8')));
      pages.push(rel);
    }
  }
};
walkPages(src);

// ---- Map data ----
// Data files under their version (dataVersion): cached for good, a new version is a new path.
const data = join(out, 'data', dataVersion);
mkdirSync(data, { recursive: true });
// The app's topographic style (LINZ topographic-v2), with the app's own changes:
// river names later, other tracks named from zoom 13 (MapLayers / TopographyStyle).
const style = JSON.parse(readFileSync(join(appAssets, 'topography-style.json'), 'utf8'));
const later = { 'nz-rivers': 15.5, 'nz-river-polygons': 14.5 };
for (const layer of style) {
  if (later[layer.id]) layer.minzoom = later[layer.id];
  if (layer.id === 'nz-tracks-label') {
    const fix = (f) => { if (!Array.isArray(f)) return; if (f[0] === '>=' && Array.isArray(f[1]) && f[1][0] === 'zoom' && Array.isArray(f[2]) && f[2][0] === 'case') { f[2][f[2].length - 1] = 13; return; } f.forEach(fix); };
    fix(layer.filter);
  }
}
// Names along lines (tracks, roads, rivers, contours) as the app draws them: one flat
// label at the middle of each line, not text bent along it. MapLibre's bent labels
// give up on curvy tracks and are re-placed at every zoom, so names came and went.
for (const layer of style) {
  if (layer.type !== 'symbol' || layer.layout?.['symbol-placement'] !== 'line') continue;
  layer.layout['symbol-placement'] = 'line-center';
  layer.layout['text-rotation-alignment'] = 'viewport';
  layer.layout['text-max-angle'] = 180;
  delete layer.layout['symbol-spacing'];
}
writeFileSync(join(data, 'topography-style.json'), JSON.stringify(style));
// Place search (LINZ Gazetteer, DOC huts, campsites and walks), as the app searches.
// DOC page links shortened to their id (the search adds DOC's address back).
writeFileSync(join(data, 'places.tsv'), readFileSync(join(appAssets, 'search/places.tsv'), 'utf8').replace(/https:\/\/www\.doc\.govt\.nz\/link\/([0-9a-f]{32})\.aspx/g, '$1'));
// DOC's stated distance and time per walk (from the walks' DOC pages, compiled for the app).
// From DOC's pages (tools/walk-facts.mjs: distance, time, difficulty, dogs), with
// the app's compiled walks' shape (loop, one way…) where it has one.
const walks = JSON.parse(readFileSync(join(appAssets, 'walks/walks.json'), 'utf8')).walks;
const factsFile = join(here, 'data/walk-facts.json');
const facts = existsSync(factsFile) ? JSON.parse(readFileSync(factsFile, 'utf8')) : {};
const meta = {};
for (const [guid, f] of Object.entries(facts)) meta[guid] = [f[0] || '', f[1] || '', '', f[3] || ''];
for (const [guid, w] of Object.entries(walks)) {
  const way = (w.ways || []).find((x) => x.main) || (w.ways || [])[0];
  const m = meta[guid] || (meta[guid] = ['', '', '', '']);
  if (way) { m[0] ||= way.distance || ''; m[1] ||= way.time || ''; }
  m[2] = w.kind || way?.shape || '';
}
writeFileSync(join(data, 'walks.json'), JSON.stringify(meta));
// Te Araroa (tools/te-araroa.mjs): the index, loaded by the map when its layer is on,
// and each section's notes and line, one file per section, loaded when it is opened.
if (existsSync(join(here, 'data/te-araroa.json'))) writeFileSync(join(data, 'te-araroa.json'), readFileSync(join(here, 'data/te-araroa.json')));
const taSections = join(here, 'data/te-araroa-sections.json.gz');
if (existsSync(taSections)) {
  mkdirSync(join(data, 'te-araroa'), { recursive: true });
  for (const [slug, notes] of Object.entries(JSON.parse(gunzipSync(readFileSync(taSections))))) if (/^[a-z0-9-]+$/.test(slug)) writeFileSync(join(data, 'te-araroa', `${slug}.json`), JSON.stringify(notes));
}
// Each walk's DOC page (tools/walk-pages.mjs): summary and sections, one file per
// walk, loaded only when its summary is opened.
const pagesFile = join(here, 'data/walk-pages.json.gz');
if (existsSync(pagesFile)) {
  const pages = JSON.parse(gunzipSync(readFileSync(pagesFile)));
  mkdirSync(join(data, 'walks'), { recursive: true });
  for (const [guid, page] of Object.entries(pages)) if (/^[0-9a-f-]{36}$/.test(guid)) writeFileSync(join(data, 'walks', `${guid}.json`), JSON.stringify(page));
}
// Overview imagery (zooms 0-12, averaged from LINZ's zoom-13 aerial photography):
// the same tiles as the app, served from the overview host, coarse ones with the site.
const overview = { files: [], tiles: {}, coarse: {} };
const fileIndex = new Map();
const fileOf = (sha) => { if (!fileIndex.has(sha)) { fileIndex.set(sha, overview.files.length); overview.files.push(sha); } return fileIndex.get(sha); };
const catalog = JSON.parse(readFileSync(join(repo, 'capacitor/overview-catalog.json'), 'utf8'));
const north = JSON.parse(readFileSync(join(repo, 'native-wgpu/assets/overviews/north-island.json'), 'utf8'));
// Both colourings, as the app: "natural" (the default) and LINZ's own ("raw").
const variant = (e, v) => e[v] || e;
for (const [k, e] of Object.entries({ ...catalog.tiles, ...north.tiles })) {
  const n = variant(e, 'natural'), r = variant(e, 'raw');
  overview.tiles[k] = [fileOf(n.file), n.offset, fileOf(r.file), r.offset];
}
const southText = readFileSync(join(repo, 'public/south-overviews.js'), 'utf8');
const ctx = {}; vm.runInNewContext(southText, ctx);
const south = ctx.RidgeSouthOverviews;
mkdirSync(join(out, 'imagery'), { recursive: true });
// (Corrected ones under their own path: /imagery/ is cached as immutable.)
const coarseFile = (from, name) => {
  if (overviewCorrected) { from = join(here, 'imagery-corrected', name); if (!existsSync(from)) throw new Error(`imagery-corrected/${name} missing: run tools/correct-overview.py`); name = `c/${name}`; }
  const to = join(out, 'imagery', name);
  if (!existsSync(to)) { mkdirSync(dirname(to), { recursive: true }); cpSync(from, to); }
  return `/imagery/${name}`;
};
for (const [k, e] of Object.entries(south.tiles)) {
  if (+k.split('/')[0] > 8 || north.coarse[k]) continue;
  const urls = ['natural', 'raw'].map((v) => variant(e, v).url?.replace(/^\//, ''));
  if (urls.every(Boolean)) overview.coarse[k] = urls.map((url) => coarseFile(join(repo, 'public', url), url.split('/').pop()));
}
for (const [k, e] of Object.entries(north.coarse)) {
  overview.coarse[k] = ['natural', 'raw'].map((v) => { const name = e[v].replace(/^assets\/nz-overviews-v1\//, ''); return coarseFile(join(repo, 'native-wgpu/assets/overviews/nz-overviews-v1', name), name); });
}
// Compact index: per zoom, rows of [x step, y, natural file, offset, raw file, offset], sorted by x then y.
const zooms = {};
for (const [k, v] of Object.entries(overview.tiles).map(([k, v]) => [k.split('/').map(Number), v]).sort((a, b) => a[0][0] - b[0][0] || a[0][1] - b[0][1] || a[0][2] - b[0][2])) {
  const [z, x, y] = k;
  const rows = (zooms[z] ||= { x: 0, rows: [] });
  rows.rows.push(x - rows.x, y, ...v);
  rows.x = x;
}
writeFileSync(join(data, 'overview.json'), JSON.stringify({ files: overview.files, zooms: Object.fromEntries(Object.entries(zooms).map(([z, v]) => [z, v.rows])), coarse: overview.coarse }));

// Where LINZ's photography has gaps (the app's gap atlas, from LINZ's capture
// areas): Esri World Imagery is drawn inside these outlines, LINZ outside.
const gapContext = {}; if (esri) vm.runInNewContext(readFileSync(join(repo, 'public/gap-imagery-atlas.js'), 'utf8'), gapContext);
const atlas = gapContext.RidgeGapAtlas || { z: 13, entries: {}, coarse: {} };
const round = (rings) => (rings || []).map((ring) => ring.map(([x, y]) => [Math.round(x * 10) / 10, Math.round(y * 10) / 10]));
const gaps = { z: atlas.z, entries: {}, coarse: {} };
for (const [k, e] of Object.entries(atlas.entries)) gaps.entries[k] = [round(e.outer), round(e.seamRings), e.vector ? 1 : 0, round(e.inner)];
mkdirSync(join(out, 'imagery/gap'), { recursive: true });
for (const [k, e] of Object.entries(atlas.coarse || {})) {
  const from = join(repo, 'public', e.url.replace(/^\//, '')), name = e.url.split('/').pop();
  cpSync(from, join(out, 'imagery/gap', name));
  gaps.coarse[k] = [`/imagery/gap/${name}`, e.full ? 1 : 0];
}
writeFileSync(join(data, 'gaps.json'), JSON.stringify(gaps.entries));
// The small part the first tiles need: which zoom-13 tiles have gaps, and the coarse images.
writeFileSync(join(data, 'gaps-index.json'), JSON.stringify({ z: gaps.z, keys: Object.keys(gaps.entries), coarse: gaps.coarse }));
// The aerial imagery's shading corrections (tools/shade-corrections.py): which blocks exist.
if (existsSync(join(here, 'data/shade-index.json'))) writeFileSync(join(data, 'shade.json'), readFileSync(join(here, 'data/shade-index.json')));

// ---- Hosting: headers, sitemap, robots ----
const connect = ["'self'", 'https://basemaps.linz.govt.nz', 'https://services1.arcgis.com', ...(esri ? ['https://services.arcgisonline.com'] : [])];
if (overviewUrl) connect.push(new URL(overviewUrl).origin);
const csp = [
  "default-src 'self'",
  "script-src 'self'",
  "worker-src 'self' blob:",
  "style-src 'self' 'unsafe-inline'",
  `img-src 'self' data: blob: https://www.doc.govt.nz ${connect.slice(1).join(' ')}`,
  `connect-src ${connect.join(' ')}`,
  "font-src 'self'",
  "frame-ancestors 'none'",
  "base-uri 'self'",
  "form-action 'self'",
].join('; ');
writeFileSync(join(out, '_headers'), `/*
  Content-Security-Policy: ${csp}
  X-Content-Type-Options: nosniff${process.env.NOINDEX ? '\n  X-Robots-Tag: noindex, nofollow' : ''}
  Referrer-Policy: strict-origin-when-cross-origin
  Permissions-Policy: geolocation=(self), camera=(), microphone=(), payment=()
  Strict-Transport-Security: max-age=31536000; includeSubDomains
/js/*
  Cache-Control: public, max-age=31536000, immutable
/css/*
  Cache-Control: public, max-age=31536000, immutable
/vendor/*
  Cache-Control: public, max-age=31536000, immutable
/imagery/*
  Cache-Control: public, max-age=31536000, immutable
/img/*
  Cache-Control: public, max-age=604800
/fonts/*
  Cache-Control: public, max-age=2592000
/site.webmanifest
  Content-Type: application/manifest+json
/data/*
  Cache-Control: public, max-age=31536000, immutable
`);
writeFileSync(join(out, '_redirects'), '/map / 301\n/map/ / 301\n/planner / 301\n/app /about/#app 301\n');
const urls = pages.filter((p) => !p.startsWith('404')).map((p) => SITE.domain + '/' + p.replace(/index\.html$/, ''));
writeFileSync(join(out, 'sitemap.xml'), `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls.map((u) => `  <url><loc>${u}</loc></url>`).join('\n')}\n</urlset>\n`);
// NOINDEX=1: a test copy (pages.dev) that search engines leave alone.
writeFileSync(join(out, 'robots.txt'), process.env.NOINDEX ? 'User-agent: *\nDisallow: /\n' : `User-agent: *\nAllow: /\nSitemap: ${SITE.domain}/sitemap.xml\n`);
console.log(JSON.stringify({ pages: pages.length, js: jsVersion, css: cssVersion, walks: Object.keys(meta).length, overviewTiles: Object.keys(overview.tiles).length, coarse: Object.keys(overview.coarse).length, esri, overviewCorrected }));
