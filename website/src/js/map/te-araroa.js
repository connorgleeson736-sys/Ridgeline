// Te Araroa, the trail the length of the country, as the Te Araroa Trust publishes it
// (data/te-araroa.json from tools/te-araroa.mjs; CC BY 4.0, teararoa.org.nz):
// the trail in pieces (southbound kilometres, land status, section), its sections
// and the options beside them (bypasses, water and ferry legs), and the huts,
// campsites and holiday parks on it. A section's notes and line load when it is
// opened; its status and alerts are read live through the site (worker/te-araroa.js).
import { dataUrl } from './data-url.js';
let data = null, ready = null;
/** The trail if it has already loaded (no fetch). */
export const loaded = () => ready;

export const SITE = 'https://www.teararoa.org.nz/';
export const NOTES = 'https://www.teararoa.org.nz/notes-and-maps/';
export const ALERTS = 'https://www.teararoa.org.nz/trail-alerts/';
export const sectionPage = (slug) => `https://www.teararoa.org.nz/sections/${slug}/`;
export const GRADES = ['', 'Easy', 'Moderate', 'Hard'];

const unpack = (c, z = false) => {
  const line = [], n = z ? 3 : 2;
  let x = 0, y = 0, h = 0;
  for (let k = 0; k + n - 1 < c.length; k += n) { x += c[k]; y += c[k + 1]; if (z) h += c[k + 2]; line.push(z ? [x / 1e5, y / 1e5, h] : [x / 1e5, y / 1e5]); }
  return line;
};

export function load() {
  return data || (data = fetch(dataUrl('/data/te-araroa.json'))
    .then((r) => { if (!r.ok) throw new Error(`Te Araroa ${r.status}`); return r.json(); })
    .then(decode)
    .catch((e) => { data = null; throw e; }));
}

function decode(j) {
  const pieces = j.pieces.map(([name, from, to, land, section, c], i) => ({ i, name, from, to, land, section, line: unpack(c) }));
  const sections = j.sections.map((s) => ({ ...s, line: s.line ? s.line.map((c) => unpack(c)) : null }));
  const bySlug = Object.fromEntries(sections.map((s) => [s.slug, s]));
  const mains = sections.filter((s) => !s.alternative);
  for (const p of pieces) { let w = 180, so = 0, e = 0, n = -90; for (const [x, y] of p.line) { w = Math.min(w, x); e = Math.max(e, x); so = Math.min(so, y); n = Math.max(n, y); } p.box = [w, so, e, n]; }
  return (ready = { name: j.name, season: j.season, total: j.total, pieces, sections, mains, bySlug, places: j.places || [], resupply: j.resupply || [], shops: j.shops || [] });
}

/** A section as one line, southbound: the trail's pieces in order, or an option's
 * longest part (an option's parts are never joined: they can be separate ways). */
export function sectionLine(d, s) {
  if (s.alternative) return (s.line || []).reduce((a, l) => (l.length > a.length ? l : a), []);
  const out = [];
  for (const p of d.pieces) if (p.section === s.slug) for (const q of p.line) { const l = out[out.length - 1]; if (!l || l[0] !== q[0] || l[1] !== q[1]) out.push(q); }
  return out;
}

/** Everything to draw for a section: its line, or all of an option's parts. */
export function sectionLines(d, s) {
  return s.alternative ? (s.line || []).filter((l) => l.length >= 2) : [sectionLine(d, s)].filter((l) => l.length >= 2);
}

// ---- Direction: southbound (Cape Reinga to Bluff) or northbound, the walker's choice ----
const DIR_KEY = 'bushline:ta-direction';
let direction = (() => { try { return localStorage.getItem(DIR_KEY) === 'nobo' ? 'nobo' : 'sobo'; } catch { return 'sobo'; } })();
export const dir = () => direction;
export function setDir(v) { direction = v === 'nobo' ? 'nobo' : 'sobo'; try { localStorage.setItem(DIR_KEY, direction); } catch { /* private mode */ } }
/** A southbound km as the walker counts it. */
export const kmIn = (d, v) => (v == null ? null : direction === 'nobo' ? Math.max(0, d.total - v) : v);
/** In walking order. */
export const walking = (list) => (direction === 'nobo' ? [...list].reverse() : list);
/** A line in walking order. */
export const facing = (line) => (direction === 'nobo' ? [...line].reverse() : line);

/** How much of a section is on roads, by the Trust's land status of each piece. */
export function roadShare(d, s) {
  let road = 0, all = 0;
  for (const p of d.pieces) if (p.section === s.slug) { const km = p.to - p.from; all += km; if (/road/i.test(p.land)) road += km; }
  return all ? road / all : null;
}

/** Southbound kilometre at a point on a piece. */
/** The point on the main trail at a (southbound) km, or null in a gap (a ferry). */
function pointAt(d, k) {
  const p = d.pieces.find((x) => k >= x.from && k <= x.to);
  if (!p?.line.length) return null;
  const t = ((k - p.from) / Math.max(1e-9, p.to - p.from)) * (p.line.length - 1), i = Math.min(p.line.length - 2, Math.floor(t)), f = t - i;
  if (p.line.length < 2) return p.line[0];
  return [p.line[i][0] + (p.line[i + 1][0] - p.line[i][0]) * f, p.line[i][1] + (p.line[i + 1][1] - p.line[i][1]) * f];
}

export function kmOn(piece, p) {
  const line = piece.line, k = Math.cos((p[1] * Math.PI) / 180);
  const seg = (a, b) => Math.hypot((b[0] - a[0]) * k, b[1] - a[1]);
  let total = 0, at = 0, best = Infinity;
  for (let i = 1; i < line.length; i++) {
    const a = line[i - 1], b = line[i], dx = (b[0] - a[0]) * k, dy = b[1] - a[1], l = dx * dx + dy * dy;
    const t = l ? Math.max(0, Math.min(1, (((p[0] - a[0]) * k) * dx + (p[1] - a[1]) * dy) / l)) : 0;
    const d = Math.hypot(a[0] * k + dx * t - p[0] * k, a[1] + dy * t - p[1]);
    if (d < best) { best = d; at = total + Math.sqrt(l) * t; }
    total += seg(a, b);
  }
  return piece.from + (total ? ((piece.to - piece.from) * at) / total : 0);
}

/** The map's features: the trail (main pieces), the options beside it, and places
 * to stay that the map does not already show (campsites and holiday parks). */
export function features(d) {
  const lines = d.pieces.map((p) => ({ type: 'Feature', properties: { kind: 'main', i: p.i, section: p.section }, geometry: { type: 'LineString', coordinates: p.line } }));
  for (const s of d.sections) if (s.alternative && s.line) lines.push({ type: 'Feature', properties: { kind: 'option', section: s.slug }, geometry: { type: 'MultiLineString', coordinates: s.line } });
  // Places known only by their town are listed with their section, not drawn.
  const places = d.places.map((p, i) => ({ p, i })).filter(({ p }) => !p.approx).map(({ p, i }) => ({ type: 'Feature', properties: { i, name: p.name, type: p.type, hut: /^hut$/i.test(p.type) }, geometry: { type: 'Point', coordinates: p.point } }));
  const stops = d.resupply.map((r, i) => ({ type: 'Feature', properties: { i, name: r.name, level: r.level, kind: r.kind || (r.level === 'full' ? 'supermarket' : r.level === 'box' ? 'box' : 'store') }, geometry: { type: 'Point', coordinates: r.point } }));
  // "Te Araroa" once on each island, fixed at the middle of the island's trail, written
  // along it: a smoothed copy of the trail about that point, one per zoom band (longer and
  // smoother further out), each short enough to sit within one tile and its buffer, so
  // the label is placed once and follows the trail's general curve, not its wiggles.
  const names = [];
  // [band, step km, (unused), shortest line km (the text's length at the band's
  // widest zoom, and some), longest stretch of trail km].
  const BANDS = [[0, 3, 0, 480, 1700], [1, 1, 0, 135, 640], [2, 0.25, 0, 36, 200]];
  // The North Island's name sits clear of the trail, just east of it, per band: [km
  // along the trail from the island's middle (north is negative), km east]. The middle
  // is at Tongariro, where the trail loops east; closer in, the name is moved north
  // along the trail to where it runs as one line (checked against the trail on screen).
  const NORTH_NAME = [[0, 28], [-60, 8], [-90, 1.5]];
  // A gentle curve through the stretch: x and y as cubics of the distance along it
  // (least squares), so the lettering bends with the trail's general line, never its turns.
  const smoothLine = (c, span, step) => {
    const pts = [];
    for (let k = c - span / 2; k <= c + span / 2; k += step) { const p = pointAt(d, k); if (p) pts.push(p); }
    if (pts.length < 8) return [];
    const ts = [0];
    for (let i = 1; i < pts.length; i++) ts.push(ts[i - 1] + Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]));
    const T = ts[ts.length - 1] || 1, deg = 3;
    const fit = (axis) => {
      const A = Array.from({ length: deg + 1 }, () => new Array(deg + 2).fill(0));
      pts.forEach((p, i) => { const t = ts[i] / T; for (let r = 0; r <= deg; r++) { for (let q = 0; q <= deg; q++) A[r][q] += t ** (r + q); A[r][deg + 1] += p[axis] * t ** r; } });
      for (let col = 0; col <= deg; col++) {
        let piv = col; for (let r = col + 1; r <= deg; r++) if (Math.abs(A[r][col]) > Math.abs(A[piv][col])) piv = r;
        [A[col], A[piv]] = [A[piv], A[col]];
        for (let r = 0; r <= deg; r++) { if (r === col) continue; const f = A[r][col] / A[col][col]; for (let q = col; q <= deg + 1; q++) A[r][q] -= f * A[col][q]; }
      }
      return A.map((row, r) => row[deg + 1] / row[r]);
    };
    const cx = fit(0), cy = fit(1), out = [];
    // The ends trimmed a little: a fitted curve strays most there.
    for (let i = 0; i <= 60; i++) { const t = 0.06 + (0.88 * i) / 60; out.push([cx.reduce((v, k, j) => v + k * t ** j, 0), cy.reduce((v, k, j) => v + k * t ** j, 0)]); }
    return out;
  };
  const kmLong = (line) => { let n = 0; for (let i = 1; i < line.length; i++) { const k = Math.cos((line[i][1] * Math.PI) / 180); n += Math.hypot((line[i][0] - line[i - 1][0]) * 111.32 * k, (line[i][1] - line[i - 1][1]) * 111.32); } return n; };
  // Centred where the trail passes nearest the middle of the island (the North Island's
  // trail runs down its west side, so its middle km sits too far north-west); the South
  // Island's at the middle of its trail.
  const nearestKm = ([x, y], a, b) => {
    let best = (a + b) / 2, bd = Infinity;
    for (let k = a + 1; k < b - 1; k += 2) { const p = pointAt(d, k); if (!p) continue; const dd = ((p[0] - x) * Math.cos((y * Math.PI) / 180)) ** 2 + (p[1] - y) ** 2; if (dd < bd) { bd = dd; best = k; } }
    return best;
  };
  for (const [a, b, centre] of [[0, 1739, [175.75, -39.0]], [1739, d.total, null]]) {
    for (const [band, step, , least, most] of BANDS) {
      const mid = centre ? nearestKm(centre, a, b) + NORTH_NAME[band][0] : (a + b) / 2;
      // The trail winds: take a longer stretch until its smoothed line fits the text.
      let line = [];
      for (let span = least * 1.6; span <= most; span *= 1.15) {
        const lo = Math.max(a + 1, mid - span / 2), hi = Math.min(b - 1, mid + span / 2);
        line = smoothLine((lo + hi) / 2, hi - lo, step);
        if (kmLong(line) >= least) break;
      }
      // The North Island's name: centred on its line (the South Island's sits above it).
      if (!a) line = line.map(([x, y]) => [x + NORTH_NAME[band][1] / (111.32 * Math.cos((y * Math.PI) / 180)), y]);
      if (line.length >= 2) names.push({ type: 'Feature', properties: { band, island: a ? 'south' : 'north' }, geometry: { type: 'LineString', coordinates: line } });
    }
  }
  // The shops and cafés at each food stop (OpenStreetMap), shown closer in.
  const shops = (d.shops || []).map((x, i) => ({ type: 'Feature', properties: { i, name: x.name, kind: x.kind }, geometry: { type: 'Point', coordinates: x.point } }));
  return { lines: { type: 'FeatureCollection', features: lines }, places: { type: 'FeatureCollection', features: places }, stops: { type: 'FeatureCollection', features: stops }, names: { type: 'FeatureCollection', features: names }, shops: { type: 'FeatureCollection', features: shops } };
}

const notesCache = new Map();
/** A section's notes (HTML from the Trust). */
export function notes(slug) {
  if (!notesCache.has(slug)) notesCache.set(slug, fetch(dataUrl(`/data/te-araroa/${slug}.json`)).then((r) => (r.ok ? r.json() : null)).catch(() => null));
  return notesCache.get(slug);
}

let statusCache = null;
/** Every section's live status and alert count: {slug: {status, alerts}}, or null. */
export function status() {
  if (statusCache && statusCache.until > Date.now()) return statusCache.promise;
  const promise = fetch('/api/te-araroa/status').then((r) => (r.ok ? r.json() : null)).then((j) => j?.sections || null).catch(() => null);
  statusCache = { promise, until: Date.now() + 5 * 60_000 };
  return promise;
}

const alertCache = new Map();
/** A section's current alerts from the Trust, [] for none, null when unreadable. */
export function alerts(slug) {
  const hit = alertCache.get(slug);
  if (hit && hit.until > Date.now()) return hit.promise;
  const promise = fetch(`/api/te-araroa/alerts/${encodeURIComponent(slug)}`).then((r) => (r.ok ? r.json() : null)).then((j) => (Array.isArray(j?.alerts) ? j.alerts : null)).catch(() => null);
  alertCache.set(slug, { promise, until: Date.now() + 5 * 60_000 });
  return promise;
}

// ---- Food: resupply stops and the carries between them ----
export const LEVELS = { full: 'Supermarket', limited: 'Small store', box: 'Send a box' };
export const KINDS = { supermarket: 'Supermarket', store: 'Store', cafe: 'Café only', box: 'No shop: send a box' };
/** A stop the trail reaches (within 3 km, or on its way, as Picton by ferry); further off it is a hitch. */
export const onTrail = (r) => r.off <= 3000 || !!r.via;

/** The Trust's time for a section as [least, most] days, or null ("2–3 days", "6 hours"). */
export function daysOf(time) {
  const m = /(\d+(?:\.\d+)?)(?:\s*(?:-|–|to)\s*(\d+(?:\.\d+)?))?\s*(days?|hours?|hrs?|h\b|mins?)/i.exec(time || '');
  if (!m) return null;
  const lo = +m[1], hi = m[2] ? +m[2] : lo, unit = m[3].toLowerCase();
  if (unit.startsWith('d')) return [lo, hi];
  if (unit.startsWith('m')) return [lo / 480, hi / 480];
  return [Math.min(lo, 10) / 8, Math.min(hi, 10) / 8];
}

/** Rough days to walk the trail from km a to km b: the Trust's section times for the
 * part of each section in between (25 km a day where a section has none). */
export function days(d, a, b) {
  let lo = 0, hi = 0;
  for (const s of d.mains) {
    const part = Math.max(0, Math.min(b, s.to) - Math.max(a, s.from));
    if (!part || s.to <= s.from) continue;
    const t = daysOf(s.time), f = part / (s.to - s.from);
    if (t) { lo += t[0] * f; hi += t[1] * f; } else { lo += part / 28; hi += part / 20; }
  }
  return [lo, hi];
}
export function daysText([lo, hi]) {
  const r = (v) => Math.max(0.5, Math.round(v * 2) / 2);
  if (hi < 0.75) return 'under a day';
  return r(lo) === r(hi) ? `≈ ${r(lo)} day${r(lo) === 1 ? '' : 's'}` : `≈ ${r(lo)}–${r(hi)} days`;
}

/** The food carries southbound: from each stop on the trail to the next, with any
 * stops a hitch away in between. */
export function carries(d) {
  const on = d.resupply.filter(onTrail);
  const nobo = direction === 'nobo';
  const out = [];
  for (let i = 1; i < on.length; i++) {
    const a = on[i - 1], b = on[i];
    if (b.km - a.km < 0.5) continue;
    const hitch = d.resupply.filter((r) => !onTrail(r) && r.km >= a.km && r.km < b.km);
    out.push(nobo ? { from: b, to: a, km: b.km - a.km, days: days(d, a.km, b.km), hitch: hitch.reverse() } : { from: a, to: b, km: b.km - a.km, days: days(d, a.km, b.km), hitch });
  }
  return nobo ? out.reverse() : out;
}
/** The nearest stops on the trail with food behind and ahead of km, in walking order. */
export function foodAround(d, km) {
  const on = d.resupply.filter(onTrail);
  const south = on.find((r) => r.km >= km + 0.05) || null, north = [...on].reverse().find((r) => r.km <= km - 0.05) || null;
  return direction === 'nobo' ? { behind: south, ahead: north } : { behind: north, ahead: south };
}

/** Te Araroa sections a line (a walk or a track) runs along: those holding at least a
 * few of its points within 60 m of the trail, in trail order. */
export function sectionsAlong(d, line) {
  if (!line || line.length < 2) return [];
  const step = Math.max(1, Math.floor(line.length / 40)), pts = line.filter((_, i) => i % step === 0);
  const tol = 60 / 111320, count = new Map();
  for (const p of pts) {
    const k = Math.cos((p[1] * Math.PI) / 180);
    let best = null, bd = tol;
    for (const piece of d.pieces) {
      const b = piece.box;
      if (p[0] < b[0] - tol / k || p[0] > b[2] + tol / k || p[1] < b[1] - tol || p[1] > b[3] + tol) continue;
      const l = piece.line;
      for (let i = 1; i < l.length; i++) {
        const a = l[i - 1], c = l[i], dx = (c[0] - a[0]) * k, dy = c[1] - a[1], len = dx * dx + dy * dy;
        const t = len ? Math.max(0, Math.min(1, (((p[0] - a[0]) * k) * dx + (p[1] - a[1]) * dy) / len)) : 0;
        const dd = Math.hypot((a[0] - p[0]) * k + dx * t, a[1] - p[1] + dy * t);
        if (dd < bd) { bd = dd; best = piece; }
      }
    }
    if (best?.section) count.set(best.section, (count.get(best.section) || 0) + 1);
  }
  const need = Math.max(2, Math.ceil(pts.length * 0.15));
  return [...count].filter(([, n]) => n >= need).map(([slug]) => d.bySlug[slug]).filter(Boolean).sort((a, b) => a.from - b.from);
}

/** What kind of option a bypass, water or ferry leg is. */
export function optionKind(s) {
  const n = s.name.toLowerCase();
  if (/hazard zone/.test(n)) return { label: 'Flood bypass', why: 'For when the river is high: the Trust advises against crossing it then.' };
  if (/interislander|ferry/.test(n)) return { label: 'Ferry', why: 'The crossing of Cook Strait.' };
  if (/water taxi/.test(n)) return { label: 'Water taxi', why: 'By boat to the start of the Queen Charlotte Track.' };
  if (/kayak|by water|canoe/.test(n)) return { label: 'By water', why: 'Paddled rather than walked.' };
  if (/road bypass/.test(n)) return { label: 'Road bypass', why: 'A road route around this part of the trail.' };
  if (/lake wakatipu/.test(n)) return { label: 'Bypass', why: 'Around Lake Wakatipu by road, instead of the boat across it.' };
  return { label: 'Bypass', why: 'An alternative way around this part of the trail.' };
}
