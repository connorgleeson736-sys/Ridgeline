// The "bushline://aerial/z/x/y" tiles: the app's overview imagery where it has
// it (zooms 5–12, averaged from LINZ's zoom-13 photography, so the country is
// sharp and even when zoomed out), LINZ's own aerial tiles everywhere else.
// One source for all zooms, so MapLibre's parent-tile fallback works across
// the switch. Requests are cancelled with the tile (obsolete downloads never
// hold a slot: the old website's latency finding).
import config from './config.js';
import { urls } from './style.js';

let index = null;
let loading = null;
const base = (config.overviewUrl || '').replace(/\/$/, '');

/** Loads the overview index (compact: zoom → x,y,file,offset rows). */
export function loadOverview() {
  if (!base) return Promise.resolve(null);
  return loading || (loading = fetch('/data/overview.json').then((r) => (r.ok ? r.json() : null)).then((o) => {
    if (!o) return null;
    const tiles = new Map();
    for (const [z, rows] of Object.entries(o.zooms)) {
      let x = 0;
      for (let i = 0; i < rows.length; i += 4) { x += rows[i]; tiles.set(`${z}/${x}/${rows[i + 1]}`, [rows[i + 2], rows[i + 3]]); }
    }
    index = { files: o.files, tiles, coarse: o.coarse };
    return index;
  }).catch(() => null));
}

const linz = (z, x, y) => urls.aerial.replace('{z}', z).replace('{x}', x).replace('{y}', y);
function urlFor(z, x, y) {
  const k = `${z}/${x}/${y}`;
  if (index) {
    const t = index.tiles.get(k);
    if (t) return `${base}/tiles/${index.files[t[0]]}/${t[1]}.webp`;
    if (index.coarse[k]) return index.coarse[k];
  }
  return linz(z, x, y);
}

export function install(maplibregl) {
  maplibregl.addProtocol('bushline', async (params, abort) => {
    const m = /^bushline:\/\/aerial\/(\d+)\/(\d+)\/(\d+)/.exec(params.url);
    if (!m) throw new Error('Unknown tile');
    const [z, x, y] = m.slice(1).map(Number);
    if (z <= 12 && !index) await loadOverview();
    const url = urlFor(z, x, y);
    let r = await fetch(url, { signal: abort.signal });
    // A missing overview tile: LINZ's own.
    if (!r.ok && url !== linz(z, x, y)) r = await fetch(linz(z, x, y), { signal: abort.signal });
    if (!r.ok) throw new Error(`Tile ${r.status}`);
    return { data: await r.arrayBuffer(), cacheControl: r.headers.get('cache-control'), expires: r.headers.get('expires') };
  });
}
