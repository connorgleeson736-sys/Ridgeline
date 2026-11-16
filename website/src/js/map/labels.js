// One name per road, track, river or lake in view, near its middle (the app's
// placement), instead of MapLibre's one per map tile the feature crosses.
// - The features come from the tiles already loaded (querySourceFeatures), grouped
//   by name; pieces of one name further apart than GAP_PX on screen are separate
//   features (two creeks of the same name) and each keeps its own label.
// - A label stays where it is while it is still in view and on its feature, so
//   names do not slide about as the map moves or tiles arrive.
const GAP_PX = 140, MIN_LINE_PX = 50;
const MOVING_MS = 500, SETTLE_MS = 160;

const lines = (g) => (g.type === 'LineString' ? [g.coordinates] : g.type === 'MultiLineString' ? g.coordinates : []);
const polygons = (g) => (g.type === 'Polygon' ? [g.coordinates] : g.type === 'MultiPolygon' ? g.coordinates : []);
const keyOf = (p) => (p.hway_num != null ? `SH ${p.hway_num}` : p.name || p.label || '');

function inside(pt, rings) {
  let c = false;
  for (const r of rings) for (let i = 0, j = r.length - 1; i < r.length; j = i++) {
    const a = r[i], b = r[j];
    if ((a[1] > pt[1]) !== (b[1] > pt[1]) && pt[0] < ((b[0] - a[0]) * (pt[1] - a[1])) / (b[1] - a[1]) + a[0]) c = !c;
  }
  return c;
}

/** Squared distance from p to the nearest edge of rings, x scaled by k. */
function edgeDistance(p, rings, k) {
  let best = Infinity;
  for (const r of rings) for (let i = 1; i < r.length; i++) {
    const ax = (r[i - 1][0] - p[0]) * k, ay = r[i - 1][1] - p[1], dx = (r[i][0] - r[i - 1][0]) * k, dy = r[i][1] - r[i - 1][1];
    const l = dx * dx + dy * dy, t = l ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l)) : 0;
    const x = ax + dx * t, y = ay + dy * t;
    best = Math.min(best, x * x + y * y);
  }
  return best;
}

const grow = (b, p) => { b[0] = Math.min(b[0], p[0]); b[1] = Math.min(b[1], p[1]); b[2] = Math.max(b[2], p[0]); b[3] = Math.max(b[3], p[1]); };
const near = (a, b, gap) => a[0] - gap <= b[2] && b[0] - gap <= a[2] && a[1] - gap <= b[3] && b[1] - gap <= a[3];

/** Pieces of one name, joined where they come within `gap` of each other. */
function clusters(pieces, gap) {
  const parent = pieces.map((_, i) => i);
  const find = (i) => (parent[i] === i ? i : (parent[i] = find(parent[i])));
  for (let i = 0; i < pieces.length; i++) for (let j = i + 1; j < pieces.length; j++) {
    if (find(i) !== find(j) && near(pieces[i].box, pieces[j].box, gap)) parent[find(i)] = find(j);
  }
  const out = new Map();
  for (let i = 0; i < pieces.length; i++) { const r = find(i); if (!out.has(r)) out.set(r, []); out.get(r).push(pieces[i]); }
  return [...out.values()];
}

/** The part of segment a-b inside box, or null (Liang–Barsky). */
function clip(a, b, box) {
  let t0 = 0, t1 = 1;
  const dx = b[0] - a[0], dy = b[1] - a[1];
  for (const [p, q] of [[-dx, a[0] - box[0]], [dx, box[2] - a[0]], [-dy, a[1] - box[1]], [dy, box[3] - a[1]]]) {
    if (p === 0) { if (q < 0) return null; continue; }
    const r = q / p;
    if (p < 0) { if (r > t1) return null; if (r > t0) t0 = r; } else { if (r < t0) return null; if (r < t1) t1 = r; }
  }
  return [[a[0] + dx * t0, a[1] + dy * t0], [a[0] + dx * t1, a[1] + dy * t1]];
}

/** The point of a line nearest the middle of its part in view; none for a line too
 * short on screen to carry its name (as MapLibre's own line labels). */
function lineAnchor(cluster, box, minLength, onScreen) {
  const parts = [];
  let sx = 0, sy = 0, sw = 0;
  for (const { coords } of cluster) for (let i = 1; i < coords.length; i++) {
    const c = clip(coords[i - 1], coords[i], box);
    if (!c) continue;
    const w = Math.hypot(c[1][0] - c[0][0], c[1][1] - c[0][1]);
    parts.push(c); sx += ((c[0][0] + c[1][0]) / 2) * w; sy += ((c[0][1] + c[1][1]) / 2) * w; sw += w;
  }
  if (sw < minLength) return null;
  const cx = sx / sw, cy = sy / sw;
  // Nearest the middle, among the points actually on screen (a tilted view's box
  // takes in ground beside the screen's trapezoid).
  const candidates = [];
  for (const [a, b] of parts) {
    const dx = b[0] - a[0], dy = b[1] - a[1], l = dx * dx + dy * dy;
    const t = l ? Math.max(0, Math.min(1, ((cx - a[0]) * dx + (cy - a[1]) * dy) / l)) : 0;
    for (const p of [[a[0] + dx * t, a[1] + dy * t], [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2]]) candidates.push([(p[0] - cx) ** 2 + (p[1] - cy) ** 2, p]);
  }
  candidates.sort((x, y) => x[0] - y[0]);
  for (const [, p] of candidates) if (onScreen(p)) return p;
  return null;
}

/** The point in view deepest inside an area (a coarse pole of inaccessibility): the
 * middles of the inside spans of scan lines across it, both ways, so a long thin
 * area (a braided river) is found as surely as a round one (a lake). */
function areaAnchor(cluster, view, box) {
  const b = [Infinity, Infinity, -Infinity, -Infinity];
  for (const { box: pb } of cluster) { grow(b, [pb[0], pb[1]]); grow(b, [pb[2], pb[3]]); }
  const lo = [Math.max(b[0], box[0]), Math.max(b[1], box[1])], hi = [Math.min(b[2], box[2]), Math.min(b[3], box[3])];
  if (!(lo[0] < hi[0] && lo[1] < hi[1])) return null;
  const k = Math.cos((((lo[1] + hi[1]) / 2) * Math.PI) / 180), N = 24;
  let best = null, bd = -1;
  for (const axis of [0, 1]) {
    const o = 1 - axis;
    for (let i = 1; i < N; i++) {
      const v = lo[axis] + ((hi[axis] - lo[axis]) * i) / N;
      for (const { rings } of cluster) {
        const xs = [];
        for (const r of rings) for (let j = 1; j < r.length; j++) {
          const a = r[j - 1], c = r[j];
          if ((a[axis] > v) !== (c[axis] > v)) xs.push(a[o] + ((c[o] - a[o]) * (v - a[axis])) / (c[axis] - a[axis]));
        }
        xs.sort((x, y) => x - y);
        for (let j = 0; j + 1 < xs.length; j += 2) {
          const s0 = Math.max(xs[j], lo[o]), s1 = Math.min(xs[j + 1], hi[o]);
          if (s0 >= s1) continue;
          const p = axis === 0 ? [v, (s0 + s1) / 2] : [(s0 + s1) / 2, v];
          if (!view(p)) continue;
          const d = edgeDistance(p, rings, k);
          if (d > bd) { bd = d; best = p; }
        }
      }
    }
  }
  return best;
}

function stillOn(p, cluster, kind, gap) {
  if (kind === 'polygon') return cluster.some(({ rings }) => inside(p, rings));
  return cluster.some(({ coords }) => edgeDistance(p, [coords], 1) < (gap / 40) ** 2);
}

export function attach(map, specs) {
  let placed = new Map(), last = '', timer = 0, lastRun = 0;

  function run() {
    timer = 0; lastRun = performance.now();
    const source = map.getSource('labels');
    if (!source) return;
    const zoom = map.getZoom(), c = map.getCanvas(), w = c.clientWidth, h = c.clientHeight;
    // The ground on screen, from points around its edges (map.getBounds falls short
    // of a tilted view's far edge over terrain); no further out than reasonable.
    const box = [Infinity, Infinity, -Infinity, -Infinity], centre = map.getCenter(), reach = 720 / 2 ** zoom;
    for (let i = 0; i <= 8; i++) for (const [x, y] of [[(w * i) / 8, 0], [(w * i) / 8, h], [0, (h * i) / 8], [w, (h * i) / 8]]) {
      const q = map.unproject([x, y]);
      grow(box, [Math.min(Math.max(q.lng, centre.lng - reach), centre.lng + reach), Math.min(Math.max(q.lat, centre.lat - reach), centre.lat + reach)]);
    }
    const M = 24;
    const view = (p) => { const q = map.project(p); return q.x >= M && q.x <= w - M && q.y >= M && q.y <= h - M; };
    const gap = (GAP_PX * 360) / (512 * 2 ** zoom);
    const features = [], next = new Map();
    for (const s of specs) {
      const layer = map.getLayer(s.id);
      if (!layer || map.getLayoutProperty(s.id, 'visibility') === 'none') continue;
      if (zoom < (layer.minzoom ?? 0) - 1 || zoom >= (layer.maxzoom ?? 99)) continue;
      if (!map.getSource(s.source)) continue;
      const found = map.querySourceFeatures(s.source, { sourceLayer: s.sourceLayer, filter: s.filter });
      const byName = new Map();
      for (const f of found) {
        const key = keyOf(f.properties);
        if (!key) continue;
        const parts = s.kind === 'polygon' ? polygons(f.geometry) : lines(f.geometry);
        for (const part of parts) {
          const coords = s.kind === 'polygon' ? part[0] : part;
          if (!coords?.length) continue;
          const pbox = [Infinity, Infinity, -Infinity, -Infinity];
          for (const p of coords) grow(pbox, p);
          if (!near(pbox, box, 0)) continue;
          if (!byName.has(key)) byName.set(key, []);
          byName.get(key).push({ coords, rings: part, box: pbox, props: f.properties });
        }
      }
      for (const [key, pieces] of byName) {
        const id = `${s.id}|${key}`, before = placed.get(id) || [], kept = [];
        for (const cluster of clusters(pieces, gap)) {
          let at = before.find((p) => view(p) && !kept.includes(p) && stillOn(p, cluster, s.kind, gap));
          if (!at) at = s.kind === 'polygon' ? areaAnchor(cluster, view, box) : lineAnchor(cluster, box, (MIN_LINE_PX * 360) / (512 * 2 ** zoom), view);
          if (!at) continue;
          kept.push(at);
          const props = cluster.reduce((a, c) => (c.coords.length > a.n ? { n: c.coords.length, p: c.props } : a), { n: -1, p: null }).p;
          features.push({ type: 'Feature', properties: { ...props, _layer: s.id }, geometry: { type: 'Point', coordinates: at } });
        }
        if (kept.length) next.set(id, kept);
      }
    }
    placed = next;
    const json = JSON.stringify(features);
    if (json !== last) { last = json; source.setData({ type: 'FeatureCollection', features }); }
  }
  const settle = () => { clearTimeout(timer); timer = setTimeout(run, SETTLE_MS); };
  const sources = new Set(specs.map((s) => s.source));
  map.on('sourcedata', (e) => { if (sources.has(e.sourceId) && e.tile) settle(); });
  map.on('moveend', settle);
  // While moving: names for ground coming into view, at most every MOVING_MS and in
  // the browser's idle time, so a frame is never held up for them.
  const idle = window.requestIdleCallback || ((f) => setTimeout(f, 50));
  let waiting = false;
  map.on('move', () => {
    if (timer || waiting || performance.now() - lastRun < MOVING_MS) return;
    waiting = true;
    idle(() => { waiting = false; if (!timer) run(); }, { timeout: 400 });
  });
  settle();
  return { refresh: settle };
}
