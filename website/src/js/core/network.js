// The map's tracks as a graph for routing: the Android app's
// WalkOptions.Network. Points joined along each track; tracks meet where they
// share a point, where one ends within JOIN_FAR_METERS of another (a T
// junction), and separate parts are joined across gaps of BRIDGE_METERS.
import { meters } from './geo.js';

export const SNAP_METERS = 2;
export const JOIN_METERS = 15;
export const JOIN_FAR_METERS = 80;
export const BRIDGE_METERS = 200;
const CELL = 25;

/** A min-heap of [cost, node]. */
class Heap {
  constructor() { this.a = []; }
  get size() { return this.a.length; }
  push(item) {
    const a = this.a; a.push(item);
    let i = a.length - 1;
    while (i > 0) { const p = (i - 1) >> 1; if (a[p][0] <= a[i][0]) break; [a[p], a[i]] = [a[i], a[p]]; i = p; }
  }
  pop() {
    const a = this.a, top = a[0], last = a.pop();
    if (a.length) {
      a[0] = last; let i = 0;
      for (;;) {
        const l = 2 * i + 1, r = l + 1; let m = i;
        if (l < a.length && a[l][0] < a[m][0]) m = l;
        if (r < a.length && a[r][0] < a[m][0]) m = r;
        if (m === i) break; [a[m], a[i]] = [a[i], a[m]]; i = m;
      }
    }
    return top;
  }
}

export class Network {
  /** tracks: [name, line][]; cost(i): how much each metre of track i counts. */
  constructor(tracks, cost = () => 1) {
    this.tracks = tracks;
    this.points = [];
    /** Per point: [neighbour, metres, track index] (track -1: a join across a gap). */
    this.edges = [];
    const first = tracks.find((t) => t[1].length)?.[1][0] ?? [0, 0];
    this.kx = 111_320 * Math.cos((first[1] * Math.PI) / 180);
    this.ky = 110_574;
    this.cells = new Map();
    const segments = [];
    tracks.forEach(([, line], ti) => {
      let prev = -1;
      for (const c of line) {
        const v = this.node(c);
        if (prev >= 0 && prev !== v) {
          this.link(prev, v, meters(this.points[prev], c) * cost(ti), ti);
          segments.push([prev, v, ti]);
        }
        prev = v;
      }
    });
    this.joinEnds(segments);
    this.bridge();
  }
  x(c) { return c[0] * this.kx; }
  y(c) { return c[1] * this.ky; }
  key(cx, cy) { return cx * 4_194_304 + cy; }
  cell(px, py) { return this.key(Math.floor(px / CELL), Math.floor(py / CELL)); }
  node(c) {
    const px = this.x(c), py = this.y(c);
    for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) {
      const list = this.cells.get(this.cell(px + dx * CELL, py + dy * CELL));
      if (list) for (const i of list) if (Math.hypot(this.x(this.points[i]) - px, this.y(this.points[i]) - py) <= SNAP_METERS) return i;
    }
    this.points.push(c); this.edges.push([]);
    const k = this.cell(px, py);
    if (!this.cells.has(k)) this.cells.set(k, []);
    this.cells.get(k).push(this.points.length - 1);
    return this.points.length - 1;
  }
  link(a, b, m, track) {
    if (a === b) return;
    this.edges[a].push([b, m, track]);
    this.edges[b].push([a, m, track]);
  }
  /** A track ending at another without sharing a point (a T junction): joined to the
   * nearest point of that track's segment; over JOIN_METERS it counts three times. */
  joinEnds(segments) {
    const bySeg = new Map();
    segments.forEach((s, si) => {
      const a = this.points[s[0]], b = this.points[s[1]];
      for (let cx = Math.floor(Math.min(this.x(a), this.x(b)) / CELL); cx <= Math.floor(Math.max(this.x(a), this.x(b)) / CELL); cx++)
        for (let cy = Math.floor(Math.min(this.y(a), this.y(b)) / CELL); cy <= Math.floor(Math.max(this.y(a), this.y(b)) / CELL); cy++) {
          const k = this.key(cx, cy);
          if (!bySeg.has(k)) bySeg.set(k, []);
          bySeg.get(k).push(si);
        }
    });
    this.dead = this.points.map((_, i) => i).filter((i) => this.edges[i].length === 1);
    const reach = Math.ceil(JOIN_FAR_METERS / CELL);
    for (const v of this.dead) {
      if (this.edges[v].length !== 1) continue;
      const own = this.edges[v][0][2], px = this.x(this.points[v]), py = this.y(this.points[v]);
      const close = new Set([v]);
      for (const [a] of this.edges[v]) { close.add(a); for (const [b] of this.edges[a]) close.add(b); }
      let best = -1, bestD = JOIN_FAR_METERS, bestT = 0;
      for (let dx = -reach; dx <= reach; dx++) for (let dy = -reach; dy <= reach; dy++) {
        const list = bySeg.get(this.cell(px + dx * CELL, py + dy * CELL));
        if (!list) continue;
        for (const si of list) {
          const s = segments[si];
          if (s[2] === own || close.has(s[0]) || close.has(s[1])) continue;
          const ax = this.x(this.points[s[0]]), ay = this.y(this.points[s[0]]);
          const bx = this.x(this.points[s[1]]), by = this.y(this.points[s[1]]);
          const l = (bx - ax) ** 2 + (by - ay) ** 2;
          const t = l === 0 ? 0 : Math.min(1, Math.max(0, ((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / l));
          const d = Math.hypot(ax + (bx - ax) * t - px, ay + (by - ay) * t - py);
          if (d < bestD) { bestD = d; best = si; bestT = t; }
        }
      }
      if (best >= 0) {
        const s = segments[best], len = meters(this.points[s[0]], this.points[s[1]]);
        const j = bestD > JOIN_METERS ? bestD * 3 : bestD;
        this.edges[v].push([s[0], j + len * bestT, s[2]]); this.edges[s[0]].push([v, j + len * bestT, s[2]]);
        this.edges[v].push([s[1], j + len * (1 - bestT), s[2]]); this.edges[s[1]].push([v, j + len * (1 - bestT), s[2]]);
      }
    }
  }
  /** Separate parts joined across the shortest gaps from their dead ends, nearest first. */
  bridge() {
    const root = this.points.map((_, i) => i);
    const find = (v) => { let r = v; while (root[r] !== r) r = root[r]; while (root[v] !== r) { const n = root[v]; root[v] = r; v = n; } return r; };
    this.edges.forEach((list, v) => { for (const [n] of list) root[find(v)] = find(n); });
    const gaps = [];
    const reach = Math.ceil(BRIDGE_METERS / CELL);
    for (const v of this.dead) {
      const px = this.x(this.points[v]), py = this.y(this.points[v]);
      let best = -1, bestD = BRIDGE_METERS;
      for (let dx = -reach; dx <= reach; dx++) for (let dy = -reach; dy <= reach; dy++) {
        const list = this.cells.get(this.cell(px + dx * CELL, py + dy * CELL));
        if (!list) continue;
        for (const i of list) {
          if (find(i) === find(v)) continue;
          const d = Math.hypot(this.x(this.points[i]) - px, this.y(this.points[i]) - py);
          if (d < bestD) { bestD = d; best = i; }
        }
      }
      if (best >= 0) gaps.push([v, best, bestD]);
    }
    gaps.sort((a, b) => a[2] - b[2]);
    for (const [a, b, d] of gaps) if (find(a) !== find(b)) { root[find(a)] = find(b); this.link(a, b, d, -1); }
  }
  /** The point nearest c within `within` metres, or -1. */
  nearest(c, within) {
    let best = -1, bestD = within;
    for (let i = 0; i < this.points.length; i++) { const d = meters(this.points[i], c); if (d < bestD) { bestD = d; best = i; } }
    return best;
  }
  /** Metres and previous points from `source` (Dijkstra). */
  from(source) {
    const n = this.points.length, dist = new Float64Array(n).fill(Infinity), prev = new Int32Array(n).fill(-1);
    dist[source] = 0;
    const heap = new Heap(); heap.push([0, source]);
    while (heap.size) {
      const [d, u] = heap.pop();
      if (d > dist[u]) continue;
      for (const [v, w] of this.edges[u]) if (d + w < dist[v]) { dist[v] = d + w; prev[v] = u; heap.push([dist[v], v]); }
    }
    return { dist, prev };
  }
  path(prev, to) {
    const out = [];
    for (let v = to; v >= 0; v = prev[v]) out.push(v);
    return out.reverse();
  }
}
