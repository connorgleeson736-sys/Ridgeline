// A walk's route from DOC's line pieces: the Android app's DocRoute.
// The pieces become a network (split where one ends on another, touching ends
// merged, separate parts joined, a loop left open at its car park closed);
// dead-end branches are side trips; the route is the loop, the loop with an
// access track in and out, or the longest path, chosen by DOC's distance.
import { meters, length, distanceTo, along, nearestOn, lerp, chain } from './geo.js';

export const JOIN_METERS = 60;
export const MERGE_METERS = 30;
export const GAP_METERS = 3000;
export const LOOP_GAP_METERS = 1500;
export const LOOP_FACTOR = 5;
export const LOOP_SHARE = 0.6;
export const ACCESS_CANDIDATES = 6;
export const TARGET_SHARE = 0.1;

/** DOC's stated distance in metres ("60 km loop", "16 km return via same track": 8 km), or null. */
export function stated(distance = '') {
  const all = [...distance.matchAll(/([0-9]+(?:[.,][0-9]+)?)\s*km/gi)];
  if (all.length !== 1 || /\bvarious\b/i.test(distance)) return null;
  const km = parseFloat(all[0][1].replace(',', '.'));
  if (!isFinite(km)) return null;
  const twice = /\breturn\b/i.test(distance) && !/\b(loop|one way)\b/i.test(distance);
  return (km * 1000) / (twice ? 2 : 1);
}

const edge = (u, v, points) => ({ u, v, points, length: length(points) });
const degree = (n, edges) => edges.reduce((s, e) => s + (e.u === n) + (e.v === n), 0);

export function route(pieces, target = null) {
  const parts = pieces.filter((p) => p.length >= 2 && length(p) > 0);
  if (!parts.length) return [];
  if (parts.length === 1) return parts[0];
  const start = parts[0][0];
  const nodes = [];
  const node = (c) => { for (let i = 0; i < nodes.length; i++) if (meters(nodes[i], c) <= MERGE_METERS) return i; nodes.push(c); return nodes.length - 1; };
  let edges = [];
  const connect = (a, b) => { if (a !== b) edges.push(edge(a, b, [nodes[a], nodes[b]])); };
  // Where a piece's end meets another piece part way along: split that piece there.
  const cuts = parts.map(() => []), links = [];
  parts.forEach((q, j) => {
    for (const e of [q[0], q[q.length - 1]]) parts.forEach((p, i) => {
      if (i === j) return;
      const n = nearestOn(p, e), len = length(p);
      if (n.d <= JOIN_METERS && n.along > JOIN_METERS && n.along < len - JOIN_METERS) { cuts[i].push(n.along); if (n.d > MERGE_METERS) links.push([e, n.at]); }
    });
  });
  parts.forEach((p, i) => { for (const piece of splitAt(p, cuts[i])) edges.push(edge(node(piece[0]), node(piece[piece.length - 1]), piece)); });
  for (const [e, at] of links) connect(node(e), node(at));
  // Separate parts: joined across their shortest gaps; beyond GAP_METERS, the longest part only.
  for (;;) {
    const comp = components(nodes.length, edges);
    if (new Set(comp).size <= 1) break;
    let best = null, bestD = GAP_METERS;
    for (let a = 0; a < nodes.length; a++) for (let b = a + 1; b < nodes.length; b++)
      if (comp[a] !== comp[b]) { const d = meters(nodes[a], nodes[b]); if (d < bestD) { bestD = d; best = [a, b]; } }
    if (!best) {
      const sizes = new Map();
      for (const e of edges) sizes.set(comp[e.u], (sizes.get(comp[e.u]) || 0) + e.length);
      const keep = [...sizes.entries()].sort((x, y) => y[1] - x[1])[0][0];
      edges = edges.filter((e) => comp[e.u] === keep);
      break;
    }
    connect(best[0], best[1]);
  }
  // A loop left open (at its car park): its two ends close together, far apart along the line.
  const ends = nodes.map((_, n) => n).filter((n) => degree(n, edges) === 1);
  const pairs = [];
  for (const a of ends) for (const b of ends) if (b > a) { const d = meters(nodes[a], nodes[b]); if (d <= LOOP_GAP_METERS) pairs.push([a, b, d]); }
  pairs.sort((x, y) => x[2] - y[2]);
  for (const [a, b, d] of pairs) {
    if (degree(a, edges) !== 1 || degree(b, edges) !== 1) continue;
    if (search(nodes.length, edges, a).dist[b] > LOOP_FACTOR * d + 1000) connect(a, b);
  }
  // Dead-end branches are side trips: what is left is the loop.
  let core = edges.slice();
  for (;;) { const dead = core.filter((e) => degree(e.u, core) === 1 || degree(e.v, core) === 1); if (!dead.length) break; core = core.filter((e) => !dead.includes(e)); }
  const loop = cycle(core);
  if (!loop && core.length) return chain(parts, start, GAP_METERS);
  const longestPath = longest(nodes, edges, start);
  if (!loop) return longestPath;
  const { round, stemmed } = aroundLoop(loop, core, nodes, edges, parts, start);
  if (target && target > 0) {
    const tried = [round, stemmed, longestPath].filter(Boolean).concat(accessRoutes(loop, core, nodes, edges, parts));
    const error = (l) => Math.abs(length(l) - target) / target;
    return tried.find((l) => error(l) <= TARGET_SHARE) || tried.reduce((a, b) => (error(b) < error(a) ? b : a));
  }
  return length(loop) >= LOOP_SHARE * edges.reduce((s, e) => s + e.length, 0) ? round : longestPath;
}

function accessRoutes(loop, core, nodes, edges, parts) {
  const onLoop = new Set(core.flatMap((e) => [e.u, e.v]));
  const out = [];
  nodes.forEach((_, end) => {
    if (onLoop.has(end) || degree(end, edges) !== 1) return;
    const { dist, via } = search(nodes.length, edges, end);
    let join = -1;
    for (const n of onLoop) if (dist[n] < Infinity && (join < 0 || dist[n] < dist[join])) join = n;
    if (join >= 0) out.push([dist[join], walk(nodes, via, end, join)]);
  });
  return out.sort((a, b) => b[0] - a[0]).slice(0, ACCESS_CANDIDATES)
    .map(([, stem]) => aroundLoop(loop, core, nodes, edges, parts, stem[0]).stemmed || stem.concat(loop, stem.slice().reverse()));
}

function aroundLoop(loop, core, nodes, edges, parts, start) {
  const used = new Set(edges.flatMap((e) => [e.u, e.v]));
  let first = -1;
  for (const n of used) if (first < 0 || meters(nodes[n], start) < meters(nodes[first], start)) first = n;
  const onLoop = new Set(core.flatMap((e) => [e.u, e.v]));
  let stem = [];
  if (!onLoop.has(first) && distanceTo(loop, nodes[first]) > MERGE_METERS) {
    const { dist, via } = search(nodes.length, edges, first);
    let join = -1;
    for (const n of onLoop) if (dist[n] < Infinity && (join < 0 || dist[n] < dist[join])) join = n;
    if (join >= 0) stem = walk(nodes, via, first, join);
  }
  const rotate = (from) => {
    let at = 0;
    loop.forEach((p, i) => { if (meters(p, from) < meters(loop[at], from)) at = i; });
    const r = loop.slice(at).concat(loop.slice(1, at + 1));
    // The way round DOC's line goes: its first piece on the loop early on, not at the end.
    const probePiece = parts.find((p) => distanceTo(r, p[p.length >> 1]) < MERGE_METERS);
    if (!probePiece) return r;
    return along(r, probePiece[probePiece.length >> 1]) > length(r) / 2 ? r.slice().reverse() : r;
  };
  const round = rotate(start);
  let stemmed = null;
  if (stem.length >= 2) { const r = rotate(stem[stem.length - 1]); stemmed = stem.concat(r.slice(1), stem.slice().reverse().slice(1)); }
  return { round, stemmed };
}

function splitAt(line, cuts) {
  if (!cuts.length) return [line];
  const at = [...new Set(cuts)].sort((a, b) => a - b);
  const out = [];
  let current = [line[0]], walked = 0;
  for (let i = 1; i < line.length; i++) {
    const a = line[i - 1], b = line[i], len = meters(a, b);
    while (at.length && at[0] <= walked + len) {
      const t = len === 0 ? 0 : Math.min(1, Math.max(0, (at.shift() - walked) / len));
      const c = lerp(a, b, t);
      current.push(c);
      if (current.length >= 2) out.push(current);
      current = [c];
    }
    current.push(b);
    walked += len;
  }
  if (current.length >= 2) out.push(current);
  return out.filter((p) => length(p) > 0);
}

function components(n, edges) {
  const root = Array.from({ length: n }, (_, i) => i);
  const find = (v) => { while (root[v] !== v) v = root[v]; return v; };
  for (const e of edges) root[find(e.u)] = find(e.v);
  const used = new Set(edges.flatMap((e) => [e.u, e.v]));
  return root.map((_, i) => (used.has(i) ? find(i) : -1 - i));
}

function search(n, edges, from) {
  const dist = new Float64Array(n).fill(Infinity), via = new Array(n).fill(null);
  dist[from] = 0;
  const queue = [[0, from]];
  while (queue.length) {
    queue.sort((a, b) => a[0] - b[0]);
    const [d, u] = queue.shift();
    if (d > dist[u]) continue;
    for (const e of edges) {
      const v = e.u === u ? e.v : e.v === u ? e.u : -1;
      if (v < 0) continue;
      if (d + e.length < dist[v]) { dist[v] = d + e.length; via[v] = e; queue.push([dist[v], v]); }
    }
  }
  return { dist, via };
}

function longest(nodes, edges, start) {
  const used = [...new Set(edges.flatMap((e) => [e.u, e.v]))];
  if (!used.length) return [];
  const far = (from) => { const d = search(nodes.length, edges, from).dist; return used.filter((n) => d[n] < Infinity).reduce((a, b) => (d[b] > d[a] ? b : a)); };
  const a = far(used[0]), b = far(a);
  const [from, to] = meters(nodes[a], start) <= meters(nodes[b], start) ? [a, b] : [b, a];
  return walk(nodes, search(nodes.length, edges, from).via, from, to);
}

function walk(nodes, via, from, to) {
  const steps = [];
  for (let v = to; v !== from;) {
    const e = via[v];
    if (!e) break;
    const back = e.v === v;
    steps.push(back ? e.points : e.points.slice().reverse());
    v = back ? e.u : e.v;
  }
  const out = [];
  for (const s of steps.reverse()) out.push(...(out.length ? s.slice(1) : s));
  return out.length ? out : [nodes[from]];
}

function cycle(core) {
  if (!core.length) return null;
  const nodes = new Set(core.flatMap((e) => [e.u, e.v]));
  for (const n of nodes) if (degree(n, core) !== 2) return null;
  const left = core.slice(), first = left.shift();
  const out = first.points.slice();
  let at = first.v;
  while (left.length) {
    const i = left.findIndex((e) => e.u === at || e.v === at);
    if (i < 0) return null;
    const e = left.splice(i, 1)[0];
    out.push(...(e.u === at ? e.points : e.points.slice().reverse()).slice(1));
    at = e.u === at ? e.v : e.u;
  }
  return at === first.u ? out : null;
}
