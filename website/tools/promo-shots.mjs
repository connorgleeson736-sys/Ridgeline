// The website's pictures, rendered from the map itself (?promo: no controls, drawn at
// the screen's full density with imagery tiles to match). Each is rendered at twice
// its pixel size on screen: a little sharper than the app, no more.
//   node build.mjs && node tools/serve-dist.mjs &   (any static server for dist/ on :8137)
//   NODE_PATH=$(npm root -g) node tools/promo-shots.mjs <out-dir> [scene ...]
// Needs Playwright with Chromium. Writes <scene>.png (full render) to <out-dir>.
import { createRequire } from 'node:module';
import { mkdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const out = process.argv[2] || 'promo';
const only = process.argv.slice(3);
const SITE = process.env.SITE || 'http://localhost:8137';
// DSF=1 for quick framing previews.
const DSF = +(process.env.DSF || 2);
const DEFAULTS = { base: 'aerial', natural: true, threeD: true, relief: 1, names: true, peaks: true, roads: true, tracks: true, huts: true, contours: false, hunting: false, teAraroa: true };

// view: zoom/lat/lng/bearing/pitch. phone: 430x932 (a large phone), desktop: 1600x900.
// open: a panel ([kind, arg]); route: a GPX file (tools/promo-routes) opened as a route;
// after: the view set once the panel has framed its line; sheet: the panel's size.
export const SCENES = {
  'hero-tongariro': { size: [1600, 900], view: '11.85/-39.136/175.634/232/60', settings: { relief: 1.6 } },
  'walk-gillespie': { size: [430, 932], view: '10.6/-44.2/169.1/0/55', open: ['walk', '515e0210-ba1a-4750-8297-a892aba9a23e'], after: '10.3/-44.205/169.1/-8/56' },
  'route-aspiring': { size: [430, 932], view: '11.6/-44.4/168.7/10/60', route: 'mt-aspiring-via-bevan-col.gpx', after: '11.65/-44.395/168.70/14/63' },
  // The app's offline maps (Paint tab): painted cells over the 3D map and the paint panel.
  'offline': { size: [430, 932], view: '10.45/-41.935/172.752/186/54', after: '10.45/-41.935/172.752/186/54', offline: {
    name: 'Travers-Sabine',
    // [detail (0 Overview … 3 Maximum), brush radius km, path [lng, lat]] painted in order.
    strokes: [
      [2, 0.9, [[172.842, -41.806], [172.822, -41.8757], [172.817, -41.8704], [172.795, -41.93], [172.7687, -41.9748], [172.7499, -42.0203], [172.732, -42.0263], [172.71, -42.02], [172.684, -42.0078], [172.6557, -42.058], [172.684, -42.0078], [172.682, -41.95], [172.6807, -41.9014], [172.71, -41.875], [172.7518, -41.8522], [172.80, -41.83], [172.842, -41.806]]],
      [3, 0.75, [[172.7687, -41.9748], [172.7499, -42.0203], [172.732, -42.0263], [172.71, -42.02], [172.684, -42.0078]]],
    ] } },
  'topo': { size: [430, 932], view: '12.68/-44.729/168.186/0/0', settings: { base: 'topo', threeD: false } },
};

// ---- The app's offline Paint tab, drawn over the map (as OfflineUi.kt and PaintedArea.kt) ----
const DETAILS = [13, 14, 15, 17], NAMES = ['Overview', 'Standard', 'Detailed', 'Maximum'];
const COLORS = ['#e0503a', '#ef8a2b', '#e8c22e', '#3fae5a'];
const merc = ([lng, lat]) => [(lng + 180) / 360, (1 - Math.log(Math.tan(Math.PI / 4 + (lat * Math.PI) / 360)) / Math.PI) / 2];
const unmerc = ([x, y]) => [x * 360 - 180, (Math.atan(Math.sinh(Math.PI * (1 - 2 * y))) * 180) / Math.PI];
/** Painted zoom-16 cells (key "x/y" to detail) along each stroke, later strokes over earlier ones. */
function paintCells(strokes) {
  const cells = new Map(), n = 2 ** 16;
  for (const [detail, km, path] of strokes) {
    for (let i = 1; i < path.length; i++) {
      const [a, b] = [merc(path[i - 1]), merc(path[i])];
      const kmPerUnit = 40075 * Math.cos((path[i][1] * Math.PI) / 180), r = km / kmPerUnit;
      const steps = Math.max(1, Math.ceil(Math.hypot(b[0] - a[0], b[1] - a[1]) / (r / 4)));
      for (let s = 0; s <= steps; s++) {
        const x = a[0] + ((b[0] - a[0]) * s) / steps, y = a[1] + ((b[1] - a[1]) * s) / steps;
        for (let cx = Math.floor((x - r) * n); cx <= Math.floor((x + r) * n); cx++)
          for (let cy = Math.floor((y - r) * n); cy <= Math.floor((y + r) * n); cy++)
            if (Math.hypot((cx + 0.5) / n - x, (cy + 0.5) / n - y) <= r) cells.set(`${cx}/${cy}`, detail);
      }
    }
  }
  return cells;
}
/** The download as the app estimates it: each cell's tiles at every zoom to its detail
 * (imagery and elevation), about 30 KB a tile. */
function estimate(cells) {
  const tiles = new Set();
  for (const [key, d] of cells) {
    const [x, y] = key.split('/').map(Number);
    for (let z = 0; z <= DETAILS[d]; z++) {
      if (z <= 16) tiles.add(`${z}/${x >> (16 - z)}/${y >> (16 - z)}`);
      else for (let dx = 0; dx < 2 ** (z - 16); dx++) for (let dy = 0; dy < 2 ** (z - 16); dy++) tiles.add(`${z}/${(x << (z - 16)) + dx}/${(y << (z - 16)) + dy}`);
    }
  }
  const files = tiles.size * 2;
  return { files, mb: (files * 30) / 1024 };
}
async function paintOffline(page, spec) {
  const cells = paintCells(spec.strokes), n = 2 ** 16;
  const features = [...cells].map(([key, d]) => {
    const [x, y] = key.split('/').map(Number);
    const ring = [[x, y], [x + 1, y], [x + 1, y + 1], [x, y + 1], [x, y]].map(([a, b]) => unmerc([a / n, b / n]));
    return { type: 'Feature', properties: { color: COLORS[d] }, geometry: { type: 'Polygon', coordinates: [ring] } };
  });
  const { files, mb } = estimate(cells);
  await page.evaluate(({ features, name, info, names, colors }) => {
    const map = window.bushlineMap;
    map.addSource('promo-paint', { type: 'geojson', data: { type: 'FeatureCollection', features } });
    // As the app: painted cells at 150/255, no outline.
    map.addLayer({ id: 'promo-paint', type: 'fill', source: 'promo-paint', paint: { 'fill-color': ['get', 'color'], 'fill-opacity': 0.59, 'fill-antialias': false } });
    const panel = document.createElement('div');
    panel.innerHTML = `<style>
      .op{position:fixed;left:0;right:0;bottom:0;z-index:50;background:#fbfaf5;border-radius:20px 20px 0 0;box-shadow:0 -4px 18px rgba(0,0,0,.25);padding:8px 12px 14px 16px;font:14px/1.3 Inter,system-ui,sans-serif;color:#1d2a24}
      .op .top{display:flex;align-items:center;margin-bottom:8px}.op .tabs{display:flex;background:#ecebe4;border-radius:10px;padding:3px}
      .op .tabs b,.op .tabs span{padding:7px 16px;border-radius:8px;font-size:13px}.op .tabs b{background:#fff;box-shadow:0 1px 3px rgba(0,0,0,.15)}.op .tabs span{color:#6b746f}
      .op .x{margin-left:auto;color:#6b746f;font-size:20px;padding:0 6px}
      .op .chips{display:flex;gap:4px}.op .chip{flex:1;height:52px;border-radius:10px;background:#f0efe8;display:flex;flex-direction:column;align-items:center;justify-content:center;font-size:11.5px;gap:5px}
      .op .chip i{width:16px;height:16px;border-radius:50%}.op .chip.on{background:#fff;outline:2px solid #1d2a24}.op .chip.q{flex:0 0 36px;font-weight:700;font-size:15px}
      .op .row{display:flex;gap:8px;margin-top:6px}.op .btn{flex:1;height:36px;border-radius:10px;border:1px solid #d6d4cb;background:#fff;display:flex;align-items:center;justify-content:center;font-size:13.5px}
      .op .info{font-size:12.5px;color:#4b5550;margin:8px 0}
      .op .name{flex:1;border-bottom:1.5px solid #9aa39e;padding:10px 2px;font-size:14px}.op .dl{flex:0 0 116px;height:44px;border-radius:12px;background:#e8742c;color:#fff;font-weight:600;display:flex;align-items:center;justify-content:center}
      </style><div class="op" data-promo-panel><div class="top"><div class="tabs"><b>Paint</b><span>Region</span></div><div class="x">✕</div></div>
      <div class="chips">${names.map((n, i) => `<div class="chip${i === 3 ? ' on' : ''}"><i style="background:${colors[i]}"></i>${n}</div>`).join('')}<div class="chip">Erase</div><div class="chip q">?</div></div>
      <div class="row"><div class="btn">Undo</div><div class="btn">Clear</div><div class="btn" style="flex:1.4">Along route</div></div>
      <div class="row"><div class="btn">Te Araroa…</div></div>
      <div class="info">${info}</div>
      <div class="row" style="align-items:center"><div class="name">${name}</div><div class="dl">Download</div></div></div>`;
    document.body.append(panel);
  }, { features, name: spec.name, info: `About ${Math.round(mb)} MB · ${files.toLocaleString('en-NZ')} files`, names: NAMES, colors: COLORS });
  return { cells: cells.size, files, mb };
}

async function ready(page, quiet = 2500, limit = 240_000) {
  const start = Date.now(); let since = 0;
  while (Date.now() - start < limit) {
    const done = await page.evaluate(() => { const m = window.bushlineMap; return !!m && m.loaded() && m.areTilesLoaded() && !m.isMoving(); }).catch(() => false);
    if (done) { since ||= Date.now(); if (Date.now() - since >= quiet) return true; } else since = 0;
    await page.waitForTimeout(500);
  }
  return false;
}

mkdirSync(out, { recursive: true });
const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
for (const [name, scene] of Object.entries(SCENES)) {
  if (only.length && !only.includes(name)) continue;
  const [w, h] = scene.size;
  const ctx = await browser.newContext({ viewport: { width: w, height: h }, deviceScaleFactor: DSF, isMobile: w < 800, hasTouch: w < 800 });
  await ctx.addInitScript((s) => { localStorage.setItem('bushline.settings', JSON.stringify(s)); localStorage.removeItem('bushline.view'); }, { ...DEFAULTS, ...scene.settings });
  const page = await ctx.newPage();
  // A local copy has no alert relay: no alerts (not "couldn't load") in the pictures.
  if (/localhost/.test(SITE)) await page.route(/\/api\/(doc-alerts|te-araroa\/alerts)\//, (r) => r.fulfill({ contentType: 'application/json', body: '{"alerts":[]}' }));
  page.on('pageerror', (e) => console.error(name, 'page error', String(e)));
  const t0 = Date.now();
  await page.goto(`${SITE}/?promo#${scene.view}`, { waitUntil: 'load' });
  await ready(page);
  if (scene.open || scene.route) {
    if (scene.open) await page.evaluate(([kind, arg]) => window.bushlinePromo[kind](arg), scene.open);
    else await page.evaluate((gpx) => window.bushlinePromo.route(gpx), readFileSync(new URL(`./promo-routes/${scene.route}`, import.meta.url), 'utf8'));
    // The panel opens and the map flies to its line: let both settle.
    await page.waitForTimeout(6000);
    if (scene.sheet) await page.evaluate((s) => window.bushlinePromo.sheet.size(s), scene.sheet);
    if (scene.after) { await page.waitForTimeout(1000); await page.evaluate((v) => window.bushlinePromo.view(v), scene.after); }
    await ready(page);
  }
  if (scene.offline) {
    // The map framed above the panel (as the app leaves the map usable above it).
    const info = await paintOffline(page, scene.offline);
    console.log(name, 'offline:', info.cells, 'cells,', info.files, 'files, about', Math.round(info.mb), 'MB');
    await page.evaluate((v) => window.bushlinePromo.view(v), scene.after);
    await ready(page);
  }
  const ok = await ready(page, 4000);
  // A large software-rendered frame takes a while to capture.
  await page.screenshot({ path: join(out, `${name}.png`), timeout: 300_000, animations: 'disabled' });
  console.log(`${name}: ${w * DSF}x${h * DSF}${ok ? '' : ' (not all tiles loaded)'} in ${((Date.now() - t0) / 1000).toFixed(0)} s`);
  await ctx.close();
}
await browser.close();
