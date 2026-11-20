// Bounded Mapbox Vector Tile 2.x decoder (the Android app's VectorTile):
// features of the requested layers, with paths in [longitude, latitude].
import { xToLon, yToLat } from './geo.js';

class Reader {
  constructor(data, at = 0, end = data.length) { this.d = data; this.at = at; this.end = end; }
  number() {
    let value = 0, mult = 1;
    for (let i = 0; i < 10; i++) {
      if (this.at >= this.end) throw new Error('Truncated vector tile');
      const b = this.d[this.at++];
      value += (b & 127) * mult;
      if (b < 128) return value;
      mult *= 128;
    }
    throw new Error('Invalid vector integer');
  }
  size() { const n = this.number(); if (n > this.end - this.at) throw new Error('Invalid vector field size'); return n; }
  child() { const n = this.size(); const r = new Reader(this.d, this.at, this.at + n); this.at += n; return r; }
  string() { const n = this.size(); const s = new TextDecoder().decode(this.d.subarray(this.at, this.at + n)); this.at += n; return s; }
  skip(wire) {
    if (wire === 0) this.number(); else if (wire === 1) this.at += 8; else if (wire === 2) this.at += this.size(); else if (wire === 5) this.at += 4;
    else throw new Error('Unsupported vector wire type');
  }
  float(bytes) {
    const v = new DataView(this.d.buffer, this.d.byteOffset + this.at, bytes);
    this.at += bytes;
    return bytes === 4 ? v.getFloat32(0, true) : v.getFloat64(0, true);
  }
}

const zigzag = (n) => (n % 2 === 1 ? -(n + 1) / 2 : n / 2);

function value(r) {
  let result = '';
  while (r.at < r.end) {
    const tag = r.number();
    if (tag === 10) result = r.string();
    else if (tag === 21) result = r.float(4);
    else if (tag === 25) result = r.float(8);
    else if (tag === 32 || tag === 40) result = r.number();
    else if (tag === 48) result = zigzag(r.number());
    else if (tag === 56) result = r.number() !== 0;
    else r.skip(tag & 7);
  }
  return result;
}

/** Features of `layers` in an MVT tile at z/x/y: { layer, type, properties, paths }. */
export function decode(bytes, z, x, y, layers) {
  const out = [], tile = new Reader(bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes));
  const n = 2 ** z;
  while (tile.at < tile.end) {
    const tag = tile.number();
    if (tag !== 26) { tile.skip(tag & 7); continue; }
    const layer = tile.child();
    let name = '', extent = 4096;
    const keys = [], values = [], features = [];
    while (layer.at < layer.end) {
      const t = layer.number();
      if (t === 10) name = layer.string();
      else if (t === 18) features.push(layer.child());
      else if (t === 26) keys.push(layer.string());
      else if (t === 34) values.push(value(layer.child()));
      else if (t === 40) extent = layer.number();
      else layer.skip(t & 7);
    }
    if (!layers.has(name)) continue;
    const scale = 1 / (extent * n);
    for (const f of features) {
      let type = 0, geometry = null;
      const tags = [];
      while (f.at < f.end) {
        const t = f.number();
        if (t === 18) { const tr = f.child(); while (tr.at < tr.end) tags.push(tr.number()); }
        else if (t === 24) type = f.number();
        else if (t === 34) geometry = f.child();
        else f.skip(t & 7);
      }
      if (!geometry || type < 1 || type > 3) continue;
      const properties = {};
      for (let i = 0; i + 1 < tags.length; i += 2) properties[keys[tags[i]]] = values[tags[i + 1]];
      const paths = [];
      let path = [], px = 0, py = 0;
      while (geometry.at < geometry.end) {
        const command = geometry.number(), kind = command & 7, count = Math.floor(command / 8);
        if (kind === 1 || kind === 2) {
          for (let i = 0; i < count; i++) {
            if (kind === 1 && path.length) { paths.push(path); path = []; }
            px += zigzag(geometry.number()); py += zigzag(geometry.number());
            path.push([xToLon(x / n + px * scale), yToLat(y / n + py * scale)]);
          }
        } else if (kind === 7) {
          if (path.length) path.push(path[0]);
        } else throw new Error('Unsupported vector command');
      }
      if (path.length) paths.push(path);
      out.push({ layer: name, type, properties, paths });
    }
  }
  return out;
}

/** Clip a line to a box [west, south, east, north] (pieces inside it). */
export function clip(line, box) {
  const inside = (p) => p[0] >= box[0] && p[0] <= box[2] && p[1] >= box[1] && p[1] <= box[3];
  const out = [];
  let current = [];
  for (let i = 0; i < line.length; i++) {
    const p = line[i];
    if (inside(p)) {
      if (!current.length && i > 0) { const c = crossing(line[i - 1], p, box); if (c) current.push(c); }
      current.push(p);
    } else if (current.length) {
      const c = crossing(current[current.length - 1], p, box);
      if (c) current.push(c);
      out.push(current); current = [];
    }
  }
  if (current.length >= 2) out.push(current);
  return out.filter((l) => l.length >= 2);
}

function crossing(a, b, box) {
  // Liang-Barsky: the point where segment a-b crosses the box boundary.
  let t0 = 0, t1 = 1;
  const dx = b[0] - a[0], dy = b[1] - a[1];
  for (const [p, q] of [[-dx, a[0] - box[0]], [dx, box[2] - a[0]], [-dy, a[1] - box[1]], [dy, box[3] - a[1]]]) {
    if (p === 0) { if (q < 0) return null; continue; }
    const r = q / p;
    if (p < 0) t0 = Math.max(t0, r); else t1 = Math.min(t1, r);
  }
  if (t0 > t1) return null;
  const inA = a[0] >= box[0] && a[0] <= box[2] && a[1] >= box[1] && a[1] <= box[3];
  const t = inA ? t1 : t0;
  return [a[0] + dx * t, a[1] + dy * t];
}
