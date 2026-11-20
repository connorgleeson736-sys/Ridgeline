// LINZ's topographic vector tiles through one small memory cache.
// - Both vector sources (the topographic layers, and the tracks and huts) read the
//   same tiles at zooms 12–14, and LINZ sends no caching headers: without this
//   each of those tiles would be downloaded twice.
// - Just before zoom 12, the zoom-12 tiles in view are fetched ahead, so the map's
//   tracks are in hand when DOC's lines hand over to them.
import { urls } from './style.js';
import { tilesFor, lonToX, latToY } from '../core/geo.js';

const MAX = (navigator.deviceMemory || 8) <= 2 ? 48 : 96;
const AHEAD = 11, TRACKS = 12, AHEAD_TILES = 30, PARALLEL = 6;
const cache = new Map();

const linz = (z, x, y) => urls.vector.replace('{z}', z).replace('{x}', x).replace('{y}', y);

function get(z, x, y) {
  const k = `${z}/${x}/${y}`;
  let p = cache.get(k);
  if (p) { cache.delete(k); cache.set(k, p); return p; }
  p = fetch(linz(z, x, y)).then((r) => {
    if (r.status === 204) return new ArrayBuffer(0);
    if (!r.ok) throw new Error(`Tile ${r.status}`);
    return r.arrayBuffer();
  });
  p.catch(() => { if (cache.get(k) === p) cache.delete(k); });
  cache.set(k, p);
  while (cache.size > MAX) cache.delete(cache.keys().next().value);
  return p;
}

export function install({ addProtocol }) {
  // A copy each time: MapLibre hands the buffer to its worker, which empties it.
  // Requests are not cancelled: a tile fetched ahead may be wanted again at once.
  addProtocol('bushline-vt', async (params) => {
    const m = /^bushline-vt:\/\/(\d+)\/(\d+)\/(\d+)/.exec(params.url);
    if (!m) throw new Error('Unknown tile');
    const data = await get(+m[1], +m[2], +m[3]);
    return { data: data.slice(0) };
  });
}

/** Fetch the zoom-12 tiles nearest the centre while the map is just short of 12. */
export function attach(map, wanted) {
  let queue = [], running = 0, timer = 0;
  const pump = () => {
    while (running < PARALLEL && queue.length) {
      const [z, x, y] = queue.shift();
      running++;
      get(z, x, y).catch(() => {}).finally(() => { running--; pump(); });
    }
  };
  const update = () => {
    timer = 0;
    const zoom = map.getZoom();
    if (!wanted() || zoom < AHEAD || zoom >= TRACKS) { queue = []; return; }
    const b = map.getBounds(), c = map.getCenter();
    // Tilted views reach the horizon: only around the centre.
    const half = 0.6;
    const box = [Math.max(b.getWest(), c.lng - half, 165), Math.max(b.getSouth(), c.lat - half, -48), Math.min(b.getEast(), c.lng + half, 179), Math.min(b.getNorth(), c.lat + half, -34)];
    if (!(box[0] < box[2] && box[1] < box[3])) { queue = []; return; }
    const cx = lonToX(c.lng), cy = latToY(c.lat), n = 2 ** TRACKS;
    queue = tilesFor(box, TRACKS)
      .filter(([z, x, y]) => !cache.has(`${z}/${x}/${y}`))
      .map((t) => [t, Math.hypot((t[1] + 0.5) / n - cx, (t[2] + 0.5) / n - cy)])
      .sort((a, b) => a[1] - b[1]).slice(0, AHEAD_TILES).map((e) => e[0]);
    pump();
  };
  const later = () => { if (!timer) timer = setTimeout(update, 80); };
  map.on('move', later);
  map.on('moveend', later);
  later();
  return { refresh: later };
}
