// DOC's walk lines (zooms 9–12, before the map's own tracks show) and hunting
// permit areas, fetched per data tile as the app does: nearest the centre
// first, a few at a time, obsolete requests cancelled, a bounded tile cache,
// and the map's sources updated once per batch rather than per tile.
import { tilesFor, lonToX, latToY } from '../core/geo.js';
import { large } from '../core/walks.js';
import * as doc from './doc.js';

const WALK_ZOOM = 10, WALK_MIN = 9, WALK_TILES = 240;
const HUNT_MIN = 7, HUNT_TILES = 120;
const huntZoom = (zoom) => (zoom < 10.5 ? 8 : zoom < 12.5 ? 11 : 13);
const PARALLEL = 4;

function tileBox(z, x, y) {
  const n = 2 ** z, lon = (v) => (v / n) * 360 - 180, lat = (v) => (Math.atan(Math.sinh(Math.PI * (1 - (2 * v) / n))) * 180) / Math.PI;
  return [lon(x), lat(y + 1), lon(x + 1), lat(y)];
}

class TileSet {
  constructor({ max, load, changed }) { this.max = max; this.load = load; this.changed = changed; this.tiles = new Map(); this.loading = new Map(); this.failed = new Map(); this.wanted = []; }
  want(ids, center) {
    const key = (t) => t.join('/');
    const cx = lonToX(center[0]), cy = latToY(center[1]);
    // Nearest the centre first (the app's overlay priority).
    this.wanted = ids.map((t) => ({ t, k: key(t), d: Math.hypot((t[1] + 0.5) / 2 ** t[0] - cx, (t[2] + 0.5) / 2 ** t[0] - cy) })).sort((a, b) => a.d - b.d);
    const keep = new Set(this.wanted.map((w) => w.k));
    for (const [k, c] of this.loading) if (!keep.has(k)) { c.abort(); this.loading.delete(k); }
    this.pump();
  }
  pump() {
    const now = Date.now();
    for (const w of this.wanted) {
      if (this.loading.size >= PARALLEL) break;
      if (this.tiles.has(w.k) || this.loading.has(w.k) || (this.failed.get(w.k) || 0) > now) continue;
      const c = new AbortController();
      this.loading.set(w.k, c);
      this.load(w.t, c.signal).then((value) => {
        this.tiles.delete(w.k); this.tiles.set(w.k, value);
        while (this.tiles.size > this.max) this.tiles.delete(this.tiles.keys().next().value);
        this.changed();
      }).catch((e) => { if (e?.name !== 'AbortError') this.failed.set(w.k, Date.now() + 15000); }).finally(() => {
        if (this.loading.get(w.k) === c) this.loading.delete(w.k);
        this.pump();
      });
    }
  }
  current() { return this.wanted.map((w) => this.tiles.get(w.k)).filter(Boolean); }
  clear() { for (const c of this.loading.values()) c.abort(); this.loading.clear(); this.wanted = []; }
}

/** The area hatched is the polygon; its name sits where it is widest. */
function anchor(geometry) {
  const rings = geometry.type === 'Polygon' ? geometry.coordinates : geometry.type === 'MultiPolygon' ? geometry.coordinates.flat() : [];
  let w = Infinity, s = Infinity, e = -Infinity, n = -Infinity;
  for (const r of rings) for (const [x, y] of r) { w = Math.min(w, x); e = Math.max(e, x); s = Math.min(s, y); n = Math.max(n, y); }
  let best = null, width = 0;
  for (const f of [0.5, 0.35, 0.65, 0.2, 0.8]) {
    const y = s + (n - s) * f, hits = [];
    for (const r of rings) for (let i = 1; i < r.length; i++) { const a = r[i - 1], b = r[i]; if ((a[1] > y) !== (b[1] > y)) hits.push(a[0] + ((b[0] - a[0]) * (y - a[1])) / (b[1] - a[1])); }
    hits.sort((a, b) => a - b);
    for (let i = 0; i + 1 < hits.length; i += 2) if (hits[i + 1] - hits[i] > width) { width = hits[i + 1] - hits[i]; best = [(hits[i] + hits[i + 1]) / 2, y]; }
  }
  return best;
}

export function attach(map, settings) {
  let infos = {};
  let walkDirty = false, huntDirty = false, frame = 0;
  const flush = () => {
    frame = 0;
    if (walkDirty) {
      walkDirty = false;
      const seen = new Set(), features = [];
      for (const lines of walkSet.current()) for (const l of lines) {
        if (seen.has(l.guid)) continue;
        seen.add(l.guid);
        const info = infos[l.guid];
        features.push({ type: 'Feature', properties: { guid: l.guid, name: info?.name || '', large: !!(info && large(info)) }, geometry: { type: 'MultiLineString', coordinates: l.paths } });
      }
      map.getSource('doc-walks')?.setData({ type: 'FeatureCollection', features });
    }
    if (huntDirty) {
      huntDirty = false;
      const seen = new Set(), features = [];
      for (const list of huntSet.current()) for (const f of list) {
        const id = f.properties.OBJECTID;
        if (seen.has(id)) continue;
        seen.add(id);
        const props = { id, name: f.properties.HuntBlockName || '', permit: f.properties.PermitArea || '', type: f.properties.BlockType || '', page: f.properties.HuntingWebPage || '', ha: f.properties.Ha || 0 };
        features.push({ type: 'Feature', id, properties: props, geometry: f.geometry });
        const a = props.name && anchor(f.geometry);
        if (a) features.push({ type: 'Feature', properties: props, geometry: { type: 'Point', coordinates: a } });
      }
      map.getSource('hunting')?.setData({ type: 'FeatureCollection', features });
    }
  };
  const schedule = () => { if (!frame) frame = requestAnimationFrame(flush); };
  const walkSet = new TileSet({ max: WALK_TILES, load: ([z, x, y], signal) => doc.linesNear(tileBox(z, x, y), signal), changed: () => { walkDirty = true; schedule(); } });
  const huntSet = new TileSet({ max: HUNT_TILES, load: ([z, x, y], signal) => doc.huntingIn(tileBox(z, x, y), (tileBox(z, x, y)[2] - tileBox(z, x, y)[0]) / 1024, signal), changed: () => { huntDirty = true; schedule(); } });

  doc.walks().then((w) => { infos = w; walkDirty = true; schedule(); }).catch(() => {});

  function view() {
    const b = map.getBounds(), zoom = map.getZoom(), c = map.getCenter();
    // Pitched views reach the horizon: only what is reasonably near.
    const span = Math.max(b.getEast() - b.getWest(), b.getNorth() - b.getSouth());
    const clamp = (box, max) => { const w = Math.min(max, box[2] - box[0]) / 2, h = Math.min(max, box[3] - box[1]) / 2; return [Math.max(box[0], c.lng - w), Math.max(box[1], c.lat - h), Math.min(box[2], c.lng + w), Math.min(box[3], c.lat + h)]; };
    return { box: clamp([b.getWest(), b.getSouth(), b.getEast(), b.getNorth()], span), zoom, center: [c.lng, c.lat] };
  }
  function update() {
    const s = settings(), v = view();
    if (s.tracks && s.base !== 'topo' && v.zoom >= WALK_MIN - 0.5) walkSet.want(tilesFor(clampTo(v.box, v.center, 1.6), WALK_ZOOM).slice(0, 40), v.center); else walkSet.want([], v.center);
    if (s.hunting && v.zoom >= HUNT_MIN) { const z = huntZoom(v.zoom); huntSet.want(tilesFor(clampTo(v.box, v.center, 4 / 2 ** (z - 8)), z).slice(0, 48), v.center); } else huntSet.want([], v.center);
  }
  const clampTo = (box, c, deg) => [Math.max(box[0], c[0] - deg), Math.max(box[1], c[1] - deg), Math.min(box[2], c[0] + deg), Math.min(box[3], c[1] + deg)];
  let timer = 0;
  const later = () => { clearTimeout(timer); timer = setTimeout(() => { timer = 0; update(); }, 120); };
  map.on('moveend', later);
  // While moving: at most every 120 ms.
  map.on('move', () => { if (!timer) later(); });
  update();
  return {
    refresh: () => { walkDirty = true; huntDirty = true; schedule(); update(); },
    infos: () => infos,
    walkLines: () => walkSet.current().flat(),
    huntingAt(lngLat) {
      const hits = map.queryRenderedFeatures(map.project(lngLat), { layers: ['hunting-fill'] });
      const seen = new Set();
      return hits.map((f) => f.properties).filter((p) => !seen.has(p.id) && seen.add(p.id)).sort((a, b) => a.ha - b.ha);
    },
  };
}
