// Geometry on [longitude, latitude] points, as the Android app does it
// (DocTracks): short-distance flat-earth metres, good to well under 1% over a
// walk's length in New Zealand.

export const METRES_PER_DEGREE = 111_320;
const RAD = Math.PI / 180;

/** Metres between two [lon, lat] points. */
export function meters(a, b) {
  const k = Math.cos(((a[1] + b[1]) / 2) * RAD);
  return Math.hypot((a[0] - b[0]) * k, a[1] - b[1]) * METRES_PER_DEGREE;
}

/** Length of a line in metres. */
export function length(line) {
  let sum = 0;
  for (let i = 1; i < line.length; i++) sum += meters(line[i - 1], line[i]);
  return sum;
}

/** Distance from p to segment a-b in metres, and the position along it (0..1). */
export function segment(p, a, b) {
  const k = Math.cos(p[1] * RAD) * METRES_PER_DEGREE;
  const m = METRES_PER_DEGREE;
  const ax = (a[0] - p[0]) * k, ay = (a[1] - p[1]) * m;
  const dx = (b[0] - a[0]) * k, dy = (b[1] - a[1]) * m;
  const l = dx * dx + dy * dy;
  const t = l === 0 ? 0 : Math.min(1, Math.max(0, -(ax * dx + ay * dy) / l));
  return [Math.hypot(ax + dx * t, ay + dy * t), t];
}

/** Distance from p to a line (or its only point). */
export function distanceTo(line, p) {
  if (line.length === 0) return Infinity;
  if (line.length === 1) return meters(line[0], p);
  let best = Infinity;
  for (let i = 1; i < line.length; i++) best = Math.min(best, segment(p, line[i - 1], line[i])[0]);
  return best;
}

/** Distance along a line of its point nearest p, looking only from `after` metres on. */
export function along(line, p, after = 0) {
  let best = Infinity, at = after, walked = 0;
  for (let i = 1; i < line.length; i++) {
    const a = line[i - 1], b = line[i], len = meters(a, b);
    if (walked + len >= after) {
      const [d, t] = segment(p, a, b);
      const pos = Math.max(after, walked + len * t);
      if (d < best) { best = d; at = pos; }
    }
    walked += len;
  }
  return at;
}

/** Nearest point on a line: its distance, how far along, and the point. */
export function nearestOn(line, p) {
  let best = Infinity, alongAt = 0, at = line[0], walked = 0;
  for (let i = 1; i < line.length; i++) {
    const a = line[i - 1], b = line[i], len = meters(a, b);
    const [d, t] = segment(p, a, b);
    if (d < best) { best = d; alongAt = walked + len * t; at = lerp(a, b, t); }
    walked += len;
  }
  return { d: best, along: alongAt, at };
}

export function lerp(a, b, t) {
  return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];
}

/** The point `d` metres along a line. */
export function pointAlong(line, d) {
  if (line.length === 0) return null;
  let walked = 0;
  for (let i = 1; i < line.length; i++) {
    const len = meters(line[i - 1], line[i]);
    if (walked + len >= d) return lerp(line[i - 1], line[i], len === 0 ? 0 : (d - walked) / len);
    walked += len;
  }
  return line[line.length - 1];
}

/** Douglas-Peucker simplification at `tolerance` metres; the ends are always kept. */
export function simplify(line, tolerance) {
  if (line.length <= 2) return line;
  const keep = new Uint8Array(line.length);
  keep[0] = keep[line.length - 1] = 1;
  const stack = [[0, line.length - 1]];
  while (stack.length) {
    const [a, b] = stack.pop();
    let far = -1, farD = tolerance;
    for (let i = a + 1; i < b; i++) {
      const d = segment(line[i], line[a], line[b])[0];
      if (d > farD) { farD = d; far = i; }
    }
    if (far >= 0) { keep[far] = 1; stack.push([a, far], [far, b]); }
  }
  return line.filter((_, i) => keep[i]);
}

/** DOC's pieces joined end to end, greedily, from the piece nearest `at`
 * (the Android app's DocTracks.chain: DocRoute's fallback for tangled lines). */
export function chain(pieces, at, gap = 150) {
  const left = pieces.filter((p) => p.length >= 2);
  if (!left.length) return [];
  let firstIndex = 0, firstD = Infinity;
  left.forEach((p, i) => { const d = distanceTo(p, at); if (d < firstD) { firstD = d; firstIndex = i; } });
  const line = left.splice(firstIndex, 1)[0].slice();
  while (left.length) {
    let best = -1, bestD = gap, atEnd = true, reverse = false;
    left.forEach((p, i) => {
      for (const [end, head] of [[true, p[0]], [true, p[p.length - 1]], [false, p[0]], [false, p[p.length - 1]]]) {
        const d = meters(end ? line[line.length - 1] : line[0], head);
        if (d < bestD) { bestD = d; best = i; atEnd = end; reverse = end ? head === p[p.length - 1] : head === p[0]; }
      }
    });
    if (best < 0) break;
    const p = left.splice(best, 1)[0];
    const piece = reverse ? p.slice().reverse() : p;
    if (atEnd) line.push(...piece.slice(1)); else line.unshift(...piece.slice(0, -1));
  }
  return line;
}

// Web Mercator world units (0..1), as map tiles use them.
export const lonToX = (lon) => (lon + 180) / 360;
export const latToY = (lat) => (1 - Math.asinh(Math.tan(lat * RAD)) / Math.PI) / 2;
export const yToLat = (y) => Math.atan(Math.sinh(Math.PI * (1 - 2 * y))) / RAD;
export const xToLon = (x) => x * 360 - 180;

/** The map tiles at zoom z covering a box [west, south, east, north]. */
export function tilesFor(box, z) {
  const n = 2 ** z;
  const x0 = Math.floor(lonToX(box[0]) * n), x1 = Math.floor(lonToX(box[2]) * n);
  const y0 = Math.floor(latToY(box[3]) * n), y1 = Math.floor(latToY(box[1]) * n);
  const out = [];
  for (let x = x0; x <= x1; x++) for (let y = y0; y <= y1; y++) out.push([z, x, y]);
  return out;
}

/** A box [west, south, east, north] around points, grown by `margin` metres. */
export function bounds(points, margin = 0) {
  let w = Infinity, s = Infinity, e = -Infinity, n = -Infinity;
  for (const p of points) { w = Math.min(w, p[0]); e = Math.max(e, p[0]); s = Math.min(s, p[1]); n = Math.max(n, p[1]); }
  const lat = (s + n) / 2, dy = margin / METRES_PER_DEGREE, dx = dy / Math.max(0.2, Math.cos(lat * RAD));
  return [w - dx, s - dy, e + dx, n + dy];
}
