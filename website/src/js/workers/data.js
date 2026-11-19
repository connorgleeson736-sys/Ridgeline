// The map's background thread: routes along tracks (the app's TrackFollow over
// LINZ's vector tiles) and elevation profiles (LINZ terrain-RGB). Off the main
// thread so the map keeps drawing at full rate while a route is worked out.
//
// Tiles are fetched with the same URLs as the map, so the browser's HTTP cache
// serves both; decoded tiles are kept in bounded caches (the app's RAM limits),
// and requests for a superseded drag are dropped rather than queued.
import { decode, clip } from '../core/mvt.js';
import { Graph, route, drag, SPLIT_NEAR_METERS } from '../core/follow.js';
import { meters, length, pointAlong, tilesFor, bounds, xToLon, yToLat, lonToX, latToY } from '../core/geo.js';

let key = '';
const FOLLOW_ZOOMS = [14, 13, 12];
const AREA_TILES = 120;
const DRAG_SPLIT_METERS = 2000;
const WALKABLE_ROADS = new Set(['primary', 'secondary']);
const LAYERS = new Set(['streets', 'water_lines', 'water_polygons']);

/** A small LRU: most recently used last. */
class Lru {
  constructor(max) { this.max = max; this.map = new Map(); }
  get(k) { const v = this.map.get(k); if (v !== undefined) { this.map.delete(k); this.map.set(k, v); } return v; }
  set(k, v) { this.map.delete(k); this.map.set(k, v); while (this.map.size > this.max) this.map.delete(this.map.keys().next().value); }
}

// ---- Vector tiles: tracks, walkable roads and rivers per tile ----
const vectorTiles = new Lru(160);
const inflight = new Map();
const vectorUrl = (z, x, y) => `https://basemaps.linz.govt.nz/v1/tiles/topographic-v2/3857/${z}/${x}/${y}.pbf?api=${key}`;
const tileBox = (z, x, y) => { const n = 2 ** z; return [xToLon(x / n), yToLat((y + 1) / n), xToLon((x + 1) / n), yToLat(y / n)]; };

function readTile(z, x, y) {
  const id = `${z}/${x}/${y}`;
  const hit = vectorTiles.get(id);
  if (hit) return Promise.resolve(hit);
  if (inflight.has(id)) return inflight.get(id);
  const p = fetch(vectorUrl(z, x, y)).then((r) => (r.ok ? r.arrayBuffer() : r.status === 404 ? new ArrayBuffer(0) : Promise.reject(new Error(r.status)))).then((buf) => {
    const box = tileBox(z, x, y);
    const tracks = [], ways = [], water = [];
    for (const f of buf.byteLength ? decode(new Uint8Array(buf), z, x, y, LAYERS) : []) {
      const kind = f.properties.kind, name = typeof f.properties.name === 'string' ? f.properties.name : '';
      const list = f.layer === 'streets' ? (kind === 'track' ? tracks : WALKABLE_ROADS.has(kind) ? ways : null) : kind === 'river' ? water : null;
      if (!list) continue;
      for (const path of f.paths) for (const part of clip(path, box)) if (part.length >= 2) list.push(list === water ? part : [name, part]);
    }
    const tile = { tracks, ways, water };
    vectorTiles.set(id, tile);
    return tile;
  }).finally(() => inflight.delete(id));
  inflight.set(id, p);
  return p;
}

/** The tracks, walkable roads and rivers within `margin` metres of points, from the
 * finest zoom whose tiles number at most AREA_TILES; null when none fits. */
async function area(points, margin) {
  const box = bounds(points, margin);
  for (const z of FOLLOW_ZOOMS) {
    const ids = tilesFor(box, z);
    if (ids.length > AREA_TILES) continue;
    const tiles = await mapLimit(ids, 8, ([tz, tx, ty]) => readTile(tz, tx, ty));
    const out = { tracks: [], ways: [], water: [] };
    for (const t of tiles) if (t) { out.tracks.push(...t.tracks); out.ways.push(...t.ways); out.water.push(...t.water); }
    return out;
  }
  return null;
}
async function mapLimit(items, n, fn) {
  const out = new Array(items.length);
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(n, items.length) }, async () => {
    while (next < items.length) { const i = next++; try { out[i] = await fn(items[i]); } catch { out[i] = null; } }
  }));
  return out;
}

async function follow({ from, to, reach }) {
  const margin = Math.min(3000, Math.max(400, meters(from, to) * 0.35));
  const a = await area([from, to], margin);
  if (!a) return null;
  return route(from, to, a.tracks, reach, a.ways, a.water);
}

// ---- Dragging a point along the tracks: one graph per drag ----
let dragGraph = null, dragToken = 0;
async function dragStart({ points, around, token }) {
  dragToken = token;
  let span = 0;
  for (const a of points) for (const b of points) span = Math.max(span, meters(a, b));
  const a = await area(points, Math.min(3000, Math.max(500, span * 0.35)));
  if (token !== dragToken) return false;
  dragGraph = a ? new Graph(a.tracks, a.ways, a.water, (c) => meters(c, around) <= DRAG_SPLIT_METERS) : null;
  return !!dragGraph;
}

// ---- Elevation: LINZ terrain-RGB, decoded once per tile ----
const heightTiles = new Lru(48);
const heightInflight = new Map();
const elevationUrl = (z, x, y) => `https://basemaps.linz.govt.nz/v1/tiles/elevation/WebMercatorQuad/${z}/${x}/${y}.png?pipeline=terrain-rgb&api=${key}`;
function heightTile(z, x, y) {
  const id = `${z}/${x}/${y}`;
  const hit = heightTiles.get(id);
  if (hit) return Promise.resolve(hit);
  if (heightInflight.has(id)) return heightInflight.get(id);
  const p = fetch(elevationUrl(z, x, y)).then((r) => (r.ok ? r.blob() : Promise.reject(new Error(r.status)))).then(async (blob) => {
    const bitmap = await createImageBitmap(blob, { colorSpaceConversion: 'none', premultiplyAlpha: 'none' });
    const size = bitmap.width;
    const canvas = new OffscreenCanvas(size, size);
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    ctx.drawImage(bitmap, 0, 0);
    bitmap.close();
    const rgba = ctx.getImageData(0, 0, size, size).data;
    const h = new Float32Array(size * size);
    for (let i = 0; i < h.length; i++) h[i] = -10000 + (rgba[i * 4] * 65536 + rgba[i * 4 + 1] * 256 + rgba[i * 4 + 2]) * 0.1;
    const tile = { size, h };
    heightTiles.set(id, tile);
    return tile;
  }).catch(() => null).finally(() => heightInflight.delete(id));
  heightInflight.set(id, p);
  return p;
}
function sample(tile, fx, fy) {
  const s = tile.size, x = Math.min(s - 1.001, Math.max(0, fx * s - 0.5)), y = Math.min(s - 1.001, Math.max(0, fy * s - 0.5));
  const x0 = Math.floor(x), y0 = Math.floor(y), tx = x - x0, ty = y - y0, h = tile.h;
  const a = h[y0 * s + x0], b = h[y0 * s + x0 + 1], c = h[(y0 + 1) * s + x0], d = h[(y0 + 1) * s + x0 + 1];
  return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty;
}
/** Heights every so often along `points`: [{d, h, p}] with at most `max` samples. */
async function profile({ points, max = 600 }) {
  const total = length(points);
  if (!(total > 0)) return [];
  const step = Math.max(10, total / max);
  // Fine tiles for short routes, coarser for long ones (at most ~60 tiles).
  let z = 14;
  const box = bounds(points, 0);
  while (z > 9 && tilesFor(box, z).length > 60) z--;
  const samples = [];
  for (let d = 0; d < total; d += step) samples.push(d);
  samples.push(total);
  const n = 2 ** z;
  const pts = samples.map((d) => pointAlong(points, d));
  const ids = new Map();
  for (const p of pts) { const x = Math.floor(lonToX(p[0]) * n), y = Math.floor(latToY(p[1]) * n); ids.set(`${x}/${y}`, [x, y]); }
  const tiles = new Map();
  await mapLimit([...ids.values()], 8, async ([x, y]) => tiles.set(`${x}/${y}`, await heightTile(z, x, y)));
  return samples.map((d, i) => {
    const p = pts[i], fx = lonToX(p[0]) * n, fy = latToY(p[1]) * n;
    const tile = tiles.get(`${Math.floor(fx)}/${Math.floor(fy)}`);
    return { d, h: tile ? Math.round(sample(tile, fx - Math.floor(fx), fy - Math.floor(fy)) * 10) / 10 : null, p };
  });
}

// ---- Messages: {id, type, ...} → {id, result} or {id, error} ----
let latestDrag = null, dragging = false;
async function runDrag() {
  if (dragging) return;
  dragging = true;
  while (latestDrag) {
    const m = latestDrag;
    latestDrag = null;
    let result = null;
    try { result = dragGraph && m.token === dragToken ? drag(dragGraph, m.prev, m.p, m.next, m.reach) : null; } catch { result = null; }
    postMessage({ id: m.id, result });
  }
  dragging = false;
}
self.onmessage = async (event) => {
  const m = event.data;
  if (m.type === 'key') { key = encodeURIComponent(m.key); return; }
  if (m.type === 'drag') {
    // Only the newest position matters while a finger moves.
    if (latestDrag) postMessage({ id: latestDrag.id, result: undefined, superseded: true });
    latestDrag = m;
    queueMicrotask(runDrag);
    return;
  }
  if (m.type === 'dragEnd') { dragGraph = null; dragToken = -1; return; }
  try {
    const result = m.type === 'follow' ? await follow(m) : m.type === 'dragStart' ? await dragStart(m) : m.type === 'profile' ? await profile(m) : null;
    postMessage({ id: m.id, result });
  } catch (error) {
    postMessage({ id: m.id, error: String(error?.message || error) });
  }
};
export { SPLIT_NEAR_METERS };
