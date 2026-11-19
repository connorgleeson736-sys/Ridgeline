// The aerial imagery:
// - zooms 4–8: LINZ's current zoom-9 photography averaged down (tools/linz-coarse.py),
//   where LINZ itself draws a satellite mosaic, so colours match every zoom;
// - zoom 9 on: LINZ's own tiles;
// - only with Esri's licence (build ESRI_IMAGERY=1): Esri World Imagery inside the
//   outlines where LINZ has no photography (the app's gap atlas), and the Satellite base.
// One source for all zooms, so MapLibre's parent-tile fallback works across the
// switch. Requests are cancelled with the tile.
import config from './config.js';
import { urls } from './style.js';
import { data as worker } from './workers.js';
import { dataUrl } from './data-url.js';

const coarse = config.coarse ? { base: config.coarse.base, keys: new Set(config.coarse.keys) } : null;
const ESRI = 'https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile';

/** Kept for main.js: the zoomed-out imagery's index is in the page's config now. */
export function loadOverview() { return Promise.resolve(null); }
// The gap index (small, preloaded) says which tiles touch a gap; only those wait
// for the outlines themselves.
let gapIndex = null, indexLoading = null, gapShapes = null, shapesLoading = null;
export function loadGapIndex() {
  return indexLoading || (indexLoading = fetch(dataUrl('/data/gaps-index.json')).then((r) => (r.ok ? r.json() : null)).then((g) => { if (g) gapIndex = { ...g, keys: new Set(g.keys) }; return gapIndex; }).catch(() => null));
}
function loadGapShapes() {
  return shapesLoading || (shapesLoading = fetch(dataUrl('/data/gaps.json')).then((r) => (r.ok ? r.json() : null)).then((g) => { gapShapes = g; return g; }).catch(() => null));
}

const linz = (z, x, y) => urls.aerial.replace('{z}', z).replace('{x}', x).replace('{y}', y);
/** The base photograph for a tile: LINZ's averaged down at zooms 4-8, else LINZ's own. */
function baseUrl(z, x, y) {
  if (coarse && z <= 8 && coarse.keys.has(`${z}/${x}/${y}`)) return `${coarse.base}/${z}/${x}/${y}.webp`;
  return linz(z, x, y);
}

// ---- The app's SourceGeometry: which source a tile takes ----
function relation(rings, box) {
  for (const ring of rings) for (let i = 1; i < ring.length; i++) {
    const a = ring[i - 1], b = ring[i];
    if (Math.max(a[0], b[0]) >= box[0] && Math.min(a[0], b[0]) <= box[2] && Math.max(a[1], b[1]) >= box[1] && Math.min(a[1], b[1]) <= box[3]) return 0;
  }
  const x = (box[0] + box[2]) / 2, y = (box[1] + box[3]) / 2;
  let hit = false;
  for (const ring of rings) for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const a = ring[i], b = ring[j];
    if ((a[1] > y) !== (b[1] > y) && x < ((b[0] - a[0]) * (y - a[1])) / (b[1] - a[1]) + a[0]) hit = !hit;
  }
  return hit ? 1 : -1;
}
async function plan(z, x, y) {
  if (z >= 9 && !gapIndex) await loadGapIndex();
  if (!gapIndex) return { mode: 'linz' };
  const az = gapIndex.z;
  if (z < az) {
    const c = gapIndex.coarse[`${z}/${x}/${y}`];
    return c ? { mode: c[1] ? 'esri' : 'boundary', coarse: c[0] } : { mode: 'linz' };
  }
  const f = 2 ** (z - az), mx = Math.floor(x / f), my = Math.floor(y / f), key = `${mx}/${my}`;
  if (!gapIndex.keys.has(key)) return { mode: 'linz' };
  if (!gapShapes) await loadGapShapes();
  const e = gapShapes?.[key];
  if (!e) return { mode: 'linz' };
  const [outer, seams, vector, inner] = e;
  const size = 256 / f, px = x * size - mx * 256, py = y * size - my * 256;
  const box = [px - 2, py - 2, px + size + 2, py + size + 2];
  let mode;
  if (seams.length && relation(seams, box) !== -1) mode = 'boundary';
  else if (relation(outer, box) === -1) mode = 'linz';
  else if (relation(vector ? outer : inner, box) === 1) mode = 'esri';
  else mode = 'boundary';
  return { mode, outer, mz: az, mx, my };
}
function esriTile(z, x, y) {
  const ez = Math.min(z, 18), f = 2 ** (z - ez);
  return { url: `${ESRI}/${ez}/${Math.floor(y / f)}/${Math.floor(x / f)}`, crop: f > 1 ? [((x % f) * 256) / f, ((y % f) * 256) / f, 256 / f] : null };
}

async function bytes(url, signal) {
  const r = await fetch(url, { signal });
  if (!r.ok) throw new Error(`Tile ${r.status}`);
  return r;
}

export function install(maplibregl) {
  maplibregl.addProtocol('bushline', async (params, abort) => {
    const m = /^bushline:\/\/(aerial|satellite)\/(\d+)\/(\d+)\/(\d+)/.exec(params.url);
    if (!m) throw new Error('Unknown tile');
    const [z, x, y] = m.slice(2).map(Number);
    if (m[1] === 'satellite') {
      if (!config.esri) throw new Error('Satellite imagery needs Esri\'s licence');
      const t = esriTile(z, x, y);
      if (!t.crop) { const r = await bytes(t.url, abort.signal); return { data: await r.arrayBuffer() }; }
      return { data: await worker.call({ type: 'compose', layers: [{ url: t.url, crop: t.crop }] }) };
    }
    const p = config.esri ? await plan(z, x, y) : { mode: 'linz' };
    const own = baseUrl(z, x, y);
    if (p.mode === 'linz') {
      let r = await fetch(own, { signal: abort.signal });
      // A missing averaged tile: LINZ's own.
      if (!r.ok && own !== linz(z, x, y)) r = await bytes(linz(z, x, y), abort.signal);
      if (!r.ok) throw new Error(`Tile ${r.status}`);
      return { data: await r.arrayBuffer() };
    }
    // Esri where LINZ has no photography: whole tiles, or inside the outline.
    let layers;
    if (p.coarse) layers = p.mode === 'esri' ? [{ url: p.coarse }] : [{ url: own }, { url: p.coarse }];
    else {
      const e = esriTile(z, x, y);
      if (p.mode === 'esri') layers = [{ url: e.url, crop: e.crop }];
      else {
        const f = 2 ** (z - p.mz), dx = (p.mx * f - x) * 256, dy = (p.my * f - y) * 256;
        layers = [{ url: own }, { url: e.url, crop: e.crop, clip: p.outer.map((ring) => ring.map(([px, py]) => [px * f + dx, py * f + dy])) }];
      }
    }
    if (abort.signal.aborted) throw new DOMException('Aborted', 'AbortError');
    return { data: await worker.call({ type: 'compose', layers }) };
  });
}
