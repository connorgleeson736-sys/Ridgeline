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
  'topo': { size: [430, 932], view: '12.68/-44.729/168.186/0/0', settings: { base: 'topo', threeD: false } },
};

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
  const ok = await ready(page, 4000);
  // A large software-rendered frame takes a while to capture.
  await page.screenshot({ path: join(out, `${name}.png`), timeout: 300_000, animations: 'disabled' });
  console.log(`${name}: ${w * DSF}x${h * DSF}${ok ? '' : ' (not all tiles loaded)'} in ${((Date.now() - t0) / 1000).toFixed(0)} s`);
  await ctx.close();
}
await browser.close();
