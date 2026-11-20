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
  'offline': { size: [430, 932], view: '10.6/-41.955/172.745/12/56', after: '10.6/-41.955/172.745/12/56', offline: {
    name: 'Travers-Sabine',
    // DOC's line for the walk, painted as in the app: [detail (0 Overview … 3 Maximum),
    // brush radius km, which part ([south, north] latitudes; null: all)] in order.
    walk: '86358329-fa21-453e-a111-62abc4a83b77',
    strokes: [
      [1, 0.8, null],
      // High detail where it matters: the alpine crossing, John Tait Hut to West Sabine Hut.
      [3, 1.15, [-42.04, -41.972]],
    ] } },
  // The app's offline maps (Region tab): the page frame over the flat map and the region panel.
  'region': { size: [430, 932], view: '10.9/-43.66/170.12/0/0', settings: { threeD: false }, region: { name: 'Aoraki/Mount Cook', paper: [210, 297], zoom: 15 } },
  // Hunting areas shaded over 3D terrain, one opened (its permit details).
  'hunting': { size: [430, 932], view: '10.9/-39.21/176.0/30/58', settings: { hunting: true }, hunting: [176.0, -39.2], after: '10.9/-39.2/176.0/30/58' },
  // A long Te Araroa section, its profile and notes open.
  'te-araroa': { size: [430, 932], view: '10.2/-41.5/173.1/0/55', open: ['teAraroa', 'richmond-alpine-track'], after: '9.65/-41.6/173.18/-38/56' },
  'topo': { size: [430, 932], view: '12.68/-44.729/168.186/0/0', settings: { base: 'topo', threeD: false } },
};

// ---- The app's offline Paint tab, drawn over the map (as OfflineUi.kt and PaintedArea.kt) ----
const PANEL_CSS = `
      .op{position:fixed;left:0;right:0;bottom:0;z-index:50;background:#fbfaf5;border-radius:20px 20px 0 0;box-shadow:0 -4px 18px rgba(0,0,0,.25);padding:8px 12px 14px 16px;font:14px/1.3 Inter,system-ui,sans-serif;color:#1d2a24}
      .op .top{display:flex;align-items:center;margin-bottom:8px}.op .tabs{display:flex;background:#ecebe4;border-radius:10px;padding:3px}
      .op .tabs b,.op .tabs span{padding:7px 16px;border-radius:7px;font-size:13px}.op .tabs b{background:#1d2a24;color:#fbfaf5}.op .tabs span{color:#1d2a24}
      .op .x{margin-left:auto;color:#6b746f;font-size:20px;padding:0 6px}
      .op .chips{display:flex;gap:4px}.op .chip{flex:1;height:52px;border-radius:10px;background:#f0efe8;display:flex;flex-direction:column;align-items:center;justify-content:center;font-size:11.5px;gap:5px}
      .op .chip i{width:16px;height:16px;border-radius:50%}.op .chip.on{background:#fff;outline:2px solid #1d2a24}.op .chip.q{flex:0 0 36px;font-weight:700;font-size:15px}
      .op .row{display:flex;gap:8px;margin-top:6px}.op .btn{flex:1;height:36px;border-radius:10px;border:1px solid #d6d4cb;background:#fff;display:flex;align-items:center;justify-content:center;font-size:13.5px}
      .op .info{font-size:12.5px;color:#4b5550;margin:8px 0}
      .op .name{flex:1;border-bottom:1.5px solid #9aa39e;padding:10px 2px;font-size:14px}.op .dl{flex:0 0 116px;height:44px;border-radius:12px;background:#e8742c;color:#fff;font-weight:600;display:flex;align-items:center;justify-content:center}
      `;
const REGION_CSS = `
      .op .row .sel{flex:1;height:40px;border-bottom:1.5px solid #9aa39e;display:flex;align-items:center;justify-content:space-between;padding:0 4px;font-size:14px}.op .row .sel:after{content:'▾';color:#6b746f;font-size:12px}
      .op .name{border-bottom:1.5px solid #9aa39e;padding:10px 2px;font-size:14px;margin-top:2px}
      .op .chk{display:flex;align-items:center;gap:10px;font-size:13px;margin:8px 0 2px}.op .chk i{width:18px;height:18px;border-radius:3px;background:#2f6b4f;color:#fff;font-style:normal;font-size:13px;display:flex;align-items:center;justify-content:center}.op .chk.off i{background:none;border:2px solid #6b746f;width:14px;height:14px}
      .op .rg .row .btn,.op.rg .row .btn{height:44px}.op.rg .row .dl{flex:1;height:44px}
      .op .fold{font-size:13px;color:#4b5550;margin-top:8px}
      .rgf i{position:fixed;z-index:40;background:rgba(16,40,35,.27)}.rgf b{position:fixed;z-index:41;box-sizing:border-box;border:2.5px solid #fff;box-shadow:0 0 0 1.5px rgba(18,32,35,.6),inset 0 0 0 1.5px rgba(18,32,35,.6)}
      .rgf u{position:fixed;z-index:42;width:18px;height:18px;border-radius:50%;background:#fff;box-sizing:border-box;border:2px solid #1d2a24}`;
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
  // The walk's own line from DOC, through the site's module.
  const paths = await page.evaluate(async (guid) => {
    const main = document.querySelector('script[src*="/map/main.js"]').getAttribute('src');
    return (await import(main.replace('main.js', 'doc.js'))).walkLine(guid);
  }, spec.walk);
  const strokes = [];
  for (const [detail, km, part] of spec.strokes)
    for (const path of paths) {
      // Runs of the line within the part's latitudes.
      let run = [];
      for (const p of path) {
        if (!part || (p[1] >= part[0] && p[1] <= part[1])) run.push(p);
        else { if (run.length > 1) strokes.push([detail, km, run]); run = []; }
      }
      if (run.length > 1) strokes.push([detail, km, run]);
    }
  const cells = paintCells(strokes), n = 2 ** 16;
  const features = [...cells].map(([key, d]) => {
    const [x, y] = key.split('/').map(Number);
    const ring = [[x, y], [x + 1, y], [x + 1, y + 1], [x, y + 1], [x, y]].map(([a, b]) => unmerc([a / n, b / n]));
    return { type: 'Feature', properties: { color: COLORS[d] }, geometry: { type: 'Polygon', coordinates: [ring] } };
  });
  const { files, mb } = estimate(cells);
  await page.evaluate(({ features, css, name, info, names, colors }) => {
    const map = window.bushlineMap;
    map.addSource('promo-paint', { type: 'geojson', data: { type: 'FeatureCollection', features } });
    // As the app: painted cells at 150/255, no outline.
    map.addLayer({ id: 'promo-paint', type: 'fill', source: 'promo-paint', paint: { 'fill-color': ['get', 'color'], 'fill-opacity': 0.59, 'fill-antialias': false } });
    const panel = document.createElement('div');
    panel.innerHTML = `<style>${css}</style><div class="op" data-promo-panel><div class="top"><div class="tabs"><b>Paint</b><span>Region</span></div><div class="x">✕</div></div>
      <div class="chips">${names.map((n, i) => `<div class="chip${i === 3 ? ' on' : ''}"><i style="background:${colors[i]}"></i>${n}</div>`).join('')}<div class="chip">Erase</div><div class="chip q">?</div></div>
      <div class="row"><div class="btn">Undo</div><div class="btn">Clear</div><div class="btn" style="flex:1.4">Along route</div></div>
      <div class="row"><div class="btn">Te Araroa…</div></div>
      <div class="info">${info}</div>
      <div class="row" style="align-items:center"><div class="name">${name}</div><div class="dl">Download</div></div></div>`;
    document.body.append(panel);
  }, { features, css: PANEL_CSS, name: spec.name, info: `About ${Math.round(mb)} MB · ${files.toLocaleString('en-NZ')} files`, names: NAMES, colors: COLORS });
  return { cells: cells.size, files, mb };
}

/** The app's Region tab over the map (OfflineUi.openTopo, RegionFrame): a page-shaped
 * frame (84% of the map above the panel) with its corner handles, and the panel. */
async function regionOffline(page, spec) {
  const panelHtml = `<style>${PANEL_CSS}${REGION_CSS}</style><div class="op rg" data-promo-panel><div class="top"><div class="tabs"><span>Paint</span><b>Region</b></div><div class="x">✕</div></div>
    <div class="row"><div class="sel">A4</div><div class="sel">Portrait</div></div>
    <div class="name">${spec.name}</div>
    <div class="row"><div class="sel">LINZ aerial</div><div class="sel">Zoom ${spec.zoom} · detailed</div></div>
    <div class="chk"><i>✓</i>NZ Topo &amp; overlays</div>
    <div class="info" id="rg-info"></div>
    <div class="chk off"><i></i>Magnetic grid on PDF</div>
    <div class="row"><div class="btn">Save as PDF</div><div class="dl">Download</div></div>
    <div class="fold">Coordinates ›</div></div>`;
  const box = await page.evaluate(({ html, paper }) => {
    document.body.insertAdjacentHTML('beforeend', html);
    const top = document.querySelector('[data-promo-panel]').getBoundingClientRect().top;
    const W = innerWidth, aspect = paper[0] / paper[1];
    const fw = Math.min(W * 0.84, top * 0.84 * aspect), fh = fw / aspect, l = (W - fw) / 2, t = (top - fh) / 2;
    const f = document.createElement('div'); f.className = 'rgf';
    f.innerHTML = `<i style="left:0;top:0;width:100%;height:${t}px"></i><i style="left:0;top:${t + fh}px;width:100%;height:${top - t - fh}px"></i><i style="left:0;top:${t}px;width:${l}px;height:${fh}px"></i><i style="left:${l + fw}px;top:${t}px;width:${W - l - fw}px;height:${fh}px"></i>
      <b style="left:${l}px;top:${t}px;width:${fw}px;height:${fh}px"></b>${[[l, t], [l + fw, t], [l, t + fh], [l + fw, t + fh]].map(([x, y]) => `<u style="left:${x - 9}px;top:${y - 9}px"></u>`).join('')}`;
    document.body.append(f);
    const m = window.bushlineMap, a = m.unproject([l, t]), z = m.unproject([l + fw, t + fh]);
    return [a.lng, z.lat, z.lng, a.lat];
  }, { html: panelHtml, paper: spec.paper });
  // Size and download as the app words them (regionEstimate: imagery and elevation to the detail zoom, overlays to 14).
  const [w, s, e, n] = box, km = (dx, lat) => dx * 111.32 * Math.cos((lat * Math.PI) / 180);
  const tx = (lng, z) => Math.floor(((lng + 180) / 360) * 2 ** z), ty = (lat, z) => Math.floor(((1 - Math.log(Math.tan(Math.PI / 4 + (lat * Math.PI) / 360)) / Math.PI) / 2) * 2 ** z);
  let tiles = 0;
  for (let z = 0; z <= spec.zoom; z++) tiles += (tx(e, z) - tx(w, z) + 1) * (ty(s, z) - ty(n, z) + 1) * (z <= 14 ? 3 : 2);
  const info = `${km(e - w, (s + n) / 2).toFixed(1)} × ${((n - s) * 110.57).toFixed(1)} km · about ${Math.round((tiles * 30) / 1024)} MB · ${tiles.toLocaleString('en-NZ')} files`;
  await page.evaluate((t) => { document.getElementById('rg-info').textContent = t; }, info);
  return info;
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
  if (scene.hunting) {
    // Hunting areas load with the view: tap (as a person would) once they are in.
    let opened = false;
    for (let i = 0; i < 30 && !opened; i++) { opened = await page.evaluate(([x, y]) => window.bushlinePromo.hunting(x, y), scene.hunting); if (!opened) await page.waitForTimeout(1000); }
    if (!opened) console.log(name, 'no hunting area at', scene.hunting);
    await page.waitForTimeout(3000);
    if (scene.after) await page.evaluate((v) => window.bushlinePromo.view(v), scene.after);
    await ready(page);
  }
  if (scene.open || scene.route) {
    if (scene.open) await page.evaluate(([kind, arg]) => window.bushlinePromo[kind](arg), scene.open);
    else await page.evaluate((gpx) => window.bushlinePromo.route(gpx), readFileSync(new URL(`./promo-routes/${scene.route}`, import.meta.url), 'utf8'));
    // The panel opens and the map flies to its line: let both settle.
    await page.waitForTimeout(6000);
    if (scene.sheet) await page.evaluate((s) => window.bushlinePromo.sheet.size(s), scene.sheet);
    if (scene.after) { await page.waitForTimeout(1000); await page.evaluate((v) => window.bushlinePromo.view(v), scene.after); }
    await ready(page);
  }
  if (scene.region) {
    const info = await regionOffline(page, scene.region);
    console.log(name, 'region:', info);
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
