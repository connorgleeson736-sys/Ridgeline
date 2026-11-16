// Te Araroa, the trail the length of the country, as the Te Araroa Trust publishes it
// (data/te-araroa.json from tools/te-araroa.mjs; CC BY 4.0, teararoa.org.nz):
// the trail in pieces (southbound kilometres, land status, section), its sections
// and the options beside them (bypasses, water and ferry legs), and the huts,
// campsites and holiday parks on it. A section's notes and line load when it is
// opened; its status and alerts are read live through the site (worker/te-araroa.js).
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
  return data || (data = fetch('/data/te-araroa.json')
    .then((r) => { if (!r.ok) throw new Error(`Te Araroa ${r.status}`); return r.json(); })
    .then(decode)
    .catch((e) => { data = null; throw e; }));
}

function decode(j) {
  const pieces = j.pieces.map(([name, from, to, land, section, c], i) => ({ i, name, from, to, land, section, line: unpack(c) }));
  const sections = j.sections.map((s) => ({ ...s, line: s.line ? s.line.map((c) => unpack(c)) : null }));
  const bySlug = Object.fromEntries(sections.map((s) => [s.slug, s]));
  const mains = sections.filter((s) => !s.alternative);
  return (ready = { name: j.name, season: j.season, total: j.total, pieces, sections, mains, bySlug, places: j.places || [] });
}

/** The trail's pieces in a section, in order, as one line (southbound). */
export function sectionLine(d, s) {
  if (s.alternative) return (s.line || []).flat();
  const out = [];
  for (const p of d.pieces) if (p.section === s.slug) for (const q of p.line) { const l = out[out.length - 1]; if (!l || l[0] !== q[0] || l[1] !== q[1]) out.push(q); }
  return out;
}

/** How much of a section is on roads, by the Trust's land status of each piece. */
export function roadShare(d, s) {
  let road = 0, all = 0;
  for (const p of d.pieces) if (p.section === s.slug) { const km = p.to - p.from; all += km; if (/road/i.test(p.land)) road += km; }
  return all ? road / all : null;
}

/** Southbound kilometre at a point on a piece. */
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
  const places = d.places.map((p, i) => ({ type: 'Feature', properties: { i, name: p.name, type: p.type, hut: /hut/i.test(p.type) }, geometry: { type: 'Point', coordinates: p.point } }));
  return { lines: { type: 'FeatureCollection', features: lines }, places: { type: 'FeatureCollection', features: places } };
}

const notesCache = new Map();
/** A section's notes (HTML from the Trust). */
export function notes(slug) {
  if (!notesCache.has(slug)) notesCache.set(slug, fetch(`/data/te-araroa/${slug}.json`).then((r) => (r.ok ? r.json() : null)).catch(() => null));
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
