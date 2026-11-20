// "From here to there" along the map's tracks and walkable roads: the
// Android app's TrackFollow. A tap close to a track or road is reached from
// the route's last point by the shortest way, ending on it; a river or stream
// may be forded where that saves a real detour.
import { meters, segment, distanceTo, simplify as simplifyLine, lerp } from './geo.js';
import { Network } from './network.js';

/** The route's last point (a hut, say) joins a track this close. */
export const START_REACH_METERS = 200;
/** Long straight pieces are split at this spacing, so a tap joins the track beside it. */
export const SPLIT_METERS = 20;
/** Points kept where the line bends by more than this. */
export const TOLERANCE_METERS = 2;
/** Track is split this close to either tap. */
export const SPLIT_NEAR_METERS = 600;
/** A metre of road counts this much against a metre of track. */
export const ROAD_COST = 1.25;
/** A ford: a straight crossing of a mapped river or stream, at most this wide… */
export const FORD_MAX_METERS = 120;
/** …counted this many times its width plus FORD_EXTRA_METERS. */
export const FORD_FACTOR = 3;
export const FORD_EXTRA_METERS = 150;

/** A line with points added so no piece is longer than SPLIT_METERS (where `near` holds). */
export function split(line, near = () => true) {
  const out = [line[0]];
  for (let i = 1; i < line.length; i++) {
    const a = line[i - 1], b = line[i], len = meters(a, b);
    const n = near(a) || near(b) || len > SPLIT_NEAR_METERS ? Math.min(500, Math.max(1, Math.ceil(len / SPLIT_METERS))) : 1;
    for (let k = 1; k < n; k++) out.push(lerp(a, b, k / n));
    out.push(b);
  }
  return out;
}

/** The tracks and walkable roads of an area as one network, built once. */
export class Graph {
  constructor(tracks, roads = [], water = [], near = () => true) {
    const lines = [], road = [];
    for (const t of tracks) if (t[1].length >= 2) { lines.push([t[0], split(t[1], near)]); road.push(false); }
    for (const t of roads) if (t[1].length >= 2) { lines.push([t[0], split(t[1], near)]); road.push(true); }
    this.net = new Network(lines, (i) => (road[i] ? ROAD_COST : 1));
    this.water = water;
  }
  /** The point on a track or road nearest p, if within `reach` metres. */
  snap(p, reach) {
    const v = this.net.nearest(p, reach + SPLIT_METERS);
    if (v < 0) return null;
    const q = onTrack(this.net, v, p).point;
    return meters(q, p) <= reach ? q : null;
  }
}

/** From `from` along the tracks to the point on a track nearest `to`, or null. */
export function route(from, to, graphOrTracks, reach, roads = [], water = []) {
  let graph = graphOrTracks;
  if (!(graph instanceof Graph)) {
    const near = (c) => meters(c, from) <= SPLIT_NEAR_METERS || meters(c, to) <= SPLIT_NEAR_METERS;
    graph = new Graph(graphOrTracks, roads, water, near);
  }
  const net = graph.net;
  if (!net.points.length) return null;
  const b = net.nearest(to, reach + SPLIT_METERS);
  if (b < 0) return null;
  const end = onTrack(net, b, to);
  if (meters(end.point, to) > reach) return null;
  const a = net.nearest(from, START_REACH_METERS + SPLIT_METERS);
  if (a < 0) return null;
  const start = onTrack(net, a, from);
  if (meters(start.point, from) > START_REACH_METERS) return null;
  const { dist, prev } = net.from(a);
  let nodes = dist[b] < Infinity ? net.path(prev, b) : null;
  if (graph.water.length) {
    const back = net.from(b);
    const f = ford(net, dist, back.dist, graph.water, nodes ? dist[b] : Infinity);
    if (f) nodes = net.path(prev, f[0]).concat(net.path(back.prev, f[1]).reverse());
  }
  if (!nodes) return null;
  const line = [from];
  const tidy = (l) => simplifyLine(l.filter((p, i) => i === 0 || meters(p, l[i - 1]) > 0.05), TOLERANCE_METERS);
  // Both on the same piece of track: straight along it.
  if (nodes.length === 1 && start.other >= 0 && start.other === end.other) { line.push(start.point, end.point); return tidy(line); }
  if (nodes.length >= 2 && nodes[1] === start.other) line.push(start.point); else line.push(start.point, net.points[a]);
  for (let i = 1; i < nodes.length - 1; i++) line.push(net.points[nodes[i]]);
  if (nodes.length >= 2) { if (nodes[nodes.length - 2] === end.other) line.push(end.point); else line.push(net.points[b], end.point); }
  else line.push(end.point);
  return tidy(line);
}

/** The point on the track nearest p beside node v, and that piece's other node (-1: the node). */
function onTrack(net, v, p) {
  let best = net.points[v], bestD = meters(best, p), other = -1;
  for (const [n, , t] of net.edges[v]) {
    if (t < 0) continue;
    const a = net.points[v], c = net.points[n];
    if (meters(a, c) > SPLIT_METERS * 1.05) continue;
    const [, f] = segment(p, a, c);
    const q = lerp(a, c, f), d = meters(q, p);
    if (d < bestD - 1e-9) { bestD = d; best = q; other = f > 0 ? n : -1; }
  }
  return { point: best, other };
}

/** The best ford [u, v]: a straight step of at most FORD_MAX_METERS crossing water. */
function ford(net, fromA, toB, water, limit) {
  if (!net.points.length) return null;
  const lat0 = net.points[0][1], kx = 111_320 * Math.cos((lat0 * Math.PI) / 180), ky = 111_320;
  const x = (c) => c[0] * kx, y = (c) => c[1] * ky;
  const cell = FORD_MAX_METERS, key = (cx, cy) => cx * 4_194_304 + cy;
  const ends = new Map();
  net.points.forEach((p, v) => {
    if (toB[v] === Infinity) return;
    const k = key(Math.floor(x(p) / cell), Math.floor(y(p) / cell));
    if (!ends.has(k)) ends.set(k, []);
    ends.get(k).push(v);
  });
  const wcell = 60, waterCells = new Map();
  for (const w of water) for (let i = 1; i < w.length; i++) {
    const ax = x(w[i - 1]), ay = y(w[i - 1]), bx = x(w[i]), by = y(w[i]);
    for (let cx = Math.floor(Math.min(ax, bx) / wcell); cx <= Math.floor(Math.max(ax, bx) / wcell); cx++)
      for (let cy = Math.floor(Math.min(ay, by) / wcell); cy <= Math.floor(Math.max(ay, by) / wcell); cy++) {
        const k = key(cx, cy);
        if (!waterCells.has(k)) waterCells.set(k, []);
        waterCells.get(k).push([ax, ay, bx, by]);
      }
  }
  if (!waterCells.size) return null;
  const crosses = (px, py, qx, qy) => {
    for (let cx = Math.floor(Math.min(px, qx) / wcell); cx <= Math.floor(Math.max(px, qx) / wcell); cx++)
      for (let cy = Math.floor(Math.min(py, qy) / wcell); cy <= Math.floor(Math.max(py, qy) / wcell); cy++)
        for (const s of waterCells.get(key(cx, cy)) || []) if (intersects(px, py, qx, qy, s[0], s[1], s[2], s[3])) return true;
    return false;
  };
  let best = limit, pick = null;
  net.points.forEach((pu, u) => {
    const du = fromA[u];
    if (du === Infinity || du + FORD_EXTRA_METERS >= best) return;
    const ux = x(pu), uy = y(pu), cx = Math.floor(ux / cell), cy = Math.floor(uy / cell);
    for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) for (const v of ends.get(key(cx + dx, cy + dy)) || []) {
      const vx = x(net.points[v]), vy = y(net.points[v]), d = Math.hypot(vx - ux, vy - uy);
      if (d > FORD_MAX_METERS || d < 1) continue;
      const c = du + FORD_FACTOR * d + FORD_EXTRA_METERS + toB[v];
      if (c < best && crosses(ux, uy, vx, vy)) { best = c; pick = [u, v]; }
    }
  });
  return pick;
}

function intersects(px, py, qx, qy, ax, ay, bx, by) {
  const side = (x1, y1, x2, y2, x3, y3) => (x2 - x1) * (y3 - y1) - (y2 - y1) * (x3 - x1);
  const d1 = side(ax, ay, bx, by, px, py), d2 = side(ax, ay, bx, by, qx, qy);
  const d3 = side(px, py, qx, qy, ax, ay), d4 = side(px, py, qx, qy, bx, by);
  return d1 > 0 !== d2 > 0 && d3 > 0 !== d4 > 0;
}

/** Dragging a route point between prev and next (null at the route's ends) to p:
 * within `reach` of a track it slides along it and both pieces follow the tracks;
 * further away it comes off and both pieces are straight. */
export function drag(graph, prev, p, next, reach) {
  const on = graph.snap(p, reach);
  if (!on) return { point: p, before: [p], after: [] };
  let before = prev ? route(prev, on, graph, reach)?.slice(1) : null;
  if (!before || !before.length) before = [on];
  const point = before[before.length - 1];
  const back = next ? route(next, point, graph, reach) : null;
  const after = back ? back.reverse().slice(1, -1) : [];
  return { point, before, after };
}

export { distanceTo };
