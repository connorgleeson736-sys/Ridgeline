// Place search over the app's places.tsv (LINZ Gazetteer, DOC huts, campsites
// and walks): the Android app's PlaceSearch. Macron- and case-insensitive,
// word prefixes in any order, "Mt" for "Mount", one typo per longer word.

export function normalize(s) {
  let t = String(s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
  t = t.replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
  if (!t.includes('mt') && !t.includes('st')) return t;
  return t.split(' ').map((w) => (w === 'mt' ? 'mount' : w === 'st' ? 'saint' : w)).join(' ');
}
const words = (s) => { const n = normalize(s); return n ? n.split(' ') : []; };

export const LABELS = { walk: 'Track', hut: 'Hut', camp: 'Campsite', peak: 'Peak', pass: 'Pass', lake: 'Lake', glacier: 'Glacier', river: 'River', place: 'Place', valley: 'Valley', ridge: 'Ridge', waterfall: 'Waterfall', coast: 'Coast', area: 'Area' };
/** Zoom to fly to for a result: wide for tracks and places, close for huts. */
export function zoomFor(kind) {
  if (kind === 'walk') return 12;
  if (['place', 'area', 'lake', 'river', 'valley', 'coast'].includes(kind)) return 12.5;
  if (['glacier', 'ridge'].includes(kind)) return 13;
  if (['peak', 'pass', 'waterfall'].includes(kind)) return 13.5;
  return 14.5;
}

function edits(a, b) {
  if (Math.abs(a.length - b.length) > 1) return 2;
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i), cur = new Array(b.length + 1);
  for (let i = 1; i <= a.length; i++) {
    cur[0] = i; let best = i;
    for (let j = 1; j <= b.length; j++) { cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1)); best = Math.min(best, cur[j]); }
    if (best > 1) return 2;
    [prev, cur] = [cur, prev];
  }
  return prev[b.length];
}
function close(candidate, word) {
  if (candidate.length < word.length - 1) return false;
  return edits(candidate.slice(0, word.length), word) <= 1
    || (candidate.length >= word.length + 1 && edits(candidate.slice(0, word.length + 1), word) <= 1)
    || edits(candidate.slice(0, Math.max(1, word.length - 1)), word) <= 1;
}
export function distanceKm(lat1, lon1, lat2, lon2) {
  const r = Math.PI / 180, p1 = lat1 * r, p2 = lat2 * r, dp = p2 - p1, dl = (lon2 - lon1) * r;
  const h = Math.sin(dp / 2) ** 2 + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) ** 2;
  return 6371 * 2 * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** DOC page links are stored as their 32-character id. */
const expandLink = (l) => (/^[0-9a-f]{32}$/.test(l) ? `https://www.doc.govt.nz/link/${l}.aspx` : l);

const GENERIC = new Set(['carpark', 'car', 'park', 'road', 'end', 'roadend', 'shelter', 'picnic', 'area', 'village', 'settlement', 'township', 'track', 'entrance', 'start', 'the']);

export class PlaceSearch {
  constructor(text) {
    this.entries = [];
    for (const line of text.split('\n')) {
      const f = line.split('\t');
      if (f.length < 8) continue;
      const lat = +f[3], lon = +f[4];
      if (!isFinite(lat) || !isFinite(lon) || f[3] === '') continue;
      const alts = f[2] ? f[2].split('|') : [];
      const place = { kind: f[0], name: f[1], lat, lon, rank: isFinite(+f[5]) && f[5] !== '' ? +f[5] : 5, detail: f[6], link: expandLink(f[7].trim()) };
      const full = [place.name, ...alts].map(normalize);
      const ws = new Set();
      for (const n of full) if (n) for (const w of n.split(' ')) ws.add(w);
      this.entries.push({ place, words: [...ws], full });
    }
  }
  get size() { return this.entries.length; }
  /** DOC huts within `reach` metres of a route's line (`points` [lon, lat], `along` the
   * profile's distance at each), at their distance along it, nearest point first; a
   * hut at the start, at the end or passed on the way. As the app's RouteHuts. */
  hutsAlong(points, along, reach = 200) {
    if (!points || points.length < 2) return [];
    let w = Infinity, e = -Infinity, s = Infinity, n = -Infinity, lat = 0;
    for (const [x, y] of points) { w = Math.min(w, x); e = Math.max(e, x); s = Math.min(s, y); n = Math.max(n, y); lat += y; }
    lat /= points.length;
    const my = 111320, mx = my * Math.cos(lat * Math.PI / 180), gy = reach / my, gx = reach / mx;
    const best = new Map();
    for (const { place } of this.entries) {
      if (place.kind !== 'hut' || place.lon < w - gx || place.lon > e + gx || place.lat < s - gy || place.lat > n + gy) continue;
      const px = place.lon * mx, py = place.lat * my;
      let near = Infinity, at = 0;
      for (let i = 1; i < points.length; i++) {
        const ax = points[i - 1][0] * mx, ay = points[i - 1][1] * my, dx = points[i][0] * mx - ax, dy = points[i][1] * my - ay;
        const len = dx * dx + dy * dy, t = len > 0 ? Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len)) : 0;
        const d = Math.hypot(ax + dx * t - px, ay + dy * t - py);
        if (d < near) { near = d; at = along[i - 1] + (along[i] - along[i - 1]) * t; }
      }
      if (near > reach) continue;
      const key = place.name.trim().toLowerCase();
      if (!best.has(key) || best.get(key).near > near) best.set(key, { near, at, name: place.name.trim() });
    }
    return [...best.values()].sort((a, b) => a.at - b.at).map((h) => [h.at, h.name]);
  }
  /** The place called `name` nearest `near` ([lon, lat]) within maxKm: exact, else a
   * longer name starting with it, else without generic words (the app's locate). */
  locate(name, near, maxKm = 40) {
    const key = normalize(name);
    if (!key) return null;
    const nearest = (match) => {
      let best = null, bestKm = maxKm;
      for (const e of this.entries) if (e.full.some(match)) { const km = distanceKm(near[1], near[0], e.place.lat, e.place.lon); if (km <= bestKm) { bestKm = km; best = e.place; } }
      return best;
    };
    const bare = key.split(' ').filter((w) => !GENERIC.has(w)).join(' ');
    return nearest((n) => n === key) || nearest((n) => n.startsWith(key + ' ')) || (bare && bare !== key ? nearest((n) => n === bare) || nearest((n) => n.startsWith(bare + ' ')) : null);
  }
  search(query, near = null, limit = 30) {
    const q = words(query);
    if (!q.length) return [];
    const phrase = normalize(query), out = [];
    const scan = (typos) => {
      for (const e of this.entries) {
        let count = 0, matched = true;
        for (const w of q) {
          if (e.words.some((x) => x.startsWith(w))) continue;
          if (typos && w.length >= 4 && e.words.some((x) => close(x, w))) { count++; continue; }
          matched = false; break;
        }
        if (!matched || (typos && count === 0)) continue;
        const quality = (e.full.some((n) => n === phrase) ? 0 : e.full.some((n) => n.startsWith(phrase)) ? 1 : 2) + count * 1.5;
        const km = near ? distanceKm(near[1], near[0], e.place.lat, e.place.lon) : 0;
        out.push({ place: e.place, score: quality * 2 + e.place.rank + Math.log(1 + km / 50) * 0.6, km });
      }
    };
    scan(false);
    if (out.length < 10) scan(true);
    return out.sort((a, b) => a.score - b.score).slice(0, limit);
  }
}
