// The website's pictures, rendered from the map itself (?promo: no controls, drawn at
// the screen's full density with imagery tiles to match). Each is rendered at three
// times its pixel size on screen and saved at twice it (supersampled when shrunk).
//   node build.mjs && node tools/serve-dist.mjs &   (any static server for dist/ on :8137)
//   NODE_PATH=$(npm root -g) node tools/promo-shots.mjs <out-dir> [scene ...]
// Needs Playwright with Chromium. Writes <scene>.png (full render) to <out-dir>.
import { createRequire } from 'node:module';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const out = process.argv[2] || 'promo';
const only = process.argv.slice(3);
const SITE = process.env.SITE || 'http://localhost:8137';
// DSF=1 for quick framing previews.
const DSF = +(process.env.DSF || 3);
const DEFAULTS = { base: 'aerial', natural: true, threeD: true, relief: 1, names: true, peaks: true, roads: true, tracks: true, huts: true, contours: false, hunting: false, teAraroa: true };

// view: zoom/lat/lng/bearing/pitch. phone: 430x932 (a large phone), desktop: 1600x900.
export const SCENES = {
  'hero-aoraki': { size: [1600, 900], view: '12.35/-43.655/170.125/-12/70', settings: { teAraroa: false } },
  'milford': { size: [1600, 900], view: '12.35/-44.648/167.885/128/70', settings: { teAraroa: false } },
  'tongariro': { size: [1600, 900], view: '12.45/-39.172/175.63/195/70' },
  'walk-gillespie': { size: [430, 932], view: '12/-44.24/169.04/20/58', open: ['walk', '515e0210-ba1a-4750-8297-a892aba9a23e'] },
  'te-araroa': { size: [430, 932], view: '12/-39.15/175.62/200/58', open: ['teAraroa', 'tongariro-alpine-crossing-to-crater'] },
  'topo': { size: [430, 932], view: '13.2/-44.727/168.1755/0/0', settings: { base: 'topo', threeD: false } },
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
  page.on('pageerror', (e) => console.error(name, 'page error', String(e)));
  const t0 = Date.now();
  await page.goto(`${SITE}/?promo#${scene.view}`, { waitUntil: 'load' });
  await ready(page);
  if (scene.open) {
    await page.evaluate(([kind, arg]) => window.bushlinePromo[kind](arg), scene.open);
    // The panel opens and the map flies to its line: let both settle.
    await page.waitForTimeout(6000);
    await ready(page);
  }
  const ok = await ready(page, 4000);
  // A large software-rendered frame takes a while to capture.
  await page.screenshot({ path: join(out, `${name}.png`), timeout: 300_000, animations: 'disabled' });
  console.log(`${name}: ${w * DSF}x${h * DSF}${ok ? '' : ' (not all tiles loaded)'} in ${((Date.now() - t0) / 1000).toFixed(0)} s`);
  await ctx.close();
}
await browser.close();
