// DOC walks and huts: names, matching a map track to its DOC walk, and huts
// beside a line (the Android app's DocWalks and DocTracks). Points are [lon, lat].
import { normalize } from './search.js';
import { meters, length, nearestOn, segment, lerp } from './geo.js';

/** A section shows its own name instead of its walk's from this zoom. */
export const CHILD_NAMES = 15;
export const NEAR_METERS = 50;
export const NAMED_METERS = 3000;
export const COVER = 0.7;
/** Huts this close to the line are listed along it. */
export const HUT_METERS = 350;

const DAYS = /\bdays?\b/i;
const LARGE = /\b(circuit|traverse|crossing|walkway|great walk)\b/i;
const COLLECTION = /\b(tracks|routes|walks|trails)\b/i;
const PAIR = /^[^,]+\s+and\s+[^,]+\s+(tracks|walks|routes)$/i;
/** A DOC page for several tracks ("Wilkin Valley tracks"), not one walk. */
export const collection = (name) => COLLECTION.test(name) && !PAIR.test(name);
/** A circuit, traverse, crossing or multi-day walk. */
export const large = (info) => !collection(info.name) && (DAYS.test(info.time || '') || LARGE.test(info.name));

/** "https://www.doc.govt.nz/link/c073….aspx" → "c073dc44-e9aa-4c36-9b8a-75cb8365f047". */
export function guidOf(link) {
  const h = /\/link\/([0-9a-fA-F]{32})\./.exec(link || '')?.[1]?.toLowerCase();
  return h ? `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}` : null;
}

const GENERIC = new Set(['track', 'tracks', 'walk', 'walks', 'walkway', 'route', 'routes', 'circuit', 'loop', 'the', 'great', 'trail', 'and']);
const nameWords = (name) => normalize(name).split(' ').filter((w) => w && !GENERIC.has(w));
/** The map's name and a DOC walk's name are the same walk. */
export function sameName(a, b) { const x = nameWords(a), y = nameWords(b); return x.length > 0 && x.join(' ') === y.join(' '); }

/** The map's name and DOC's spellings of it ("Mt Iron Track" is DOC's "Mount Iron Track"). */
export function names(name) {
  const n = String(name).trim().replace(/\s+/g, ' ');
  const out = new Set([n, n.replace(/\bMt\.?(?= )/g, 'Mount'), n.replace(/\bMount(?= )/g, 'Mt')]);
  for (const v of [...out]) if (/ Track$/i.test(v)) out.add(v + 's');
  return [...out];
}

function resample(line, step, max) {
  const total = length(line);
  if (total <= 0) return line.slice(0, 1);
  const n = Math.min(max, Math.max(2, Math.ceil(total / step) + 1));
  const out = [];
  let i = 1, walked = 0;
  for (let k = 0; k < n; k++) {
    const want = (total * k) / (n - 1);
    while (i < line.length - 1 && walked + meters(line[i - 1], line[i]) < want) { walked += meters(line[i - 1], line[i]); i++; }
    const seg = meters(line[i - 1], line[i]);
    out.push(lerp(line[i - 1], line[i], seg > 0 ? Math.min(1, (want - walked) / seg) : 0));
  }
  return out;
}
function distToPaths(paths, p) {
  let best = Infinity;
  for (const path of paths) for (let i = 1; i < path.length; i++) { const [d] = segment(p, path[i - 1], path[i]); if (d < best) best = d; }
  return best;
}

/** The DOC walk a map track belongs to, and whether the track carries that walk's
 * name. `lines`: [{guid, meters, paths}], `infos`: guid → {name, time}. */
export function walkFor(track, own, lines, infos) {
  if (track.length < 2 || !lines.length) return null;
  const samples = resample(track, 50, 40);
  const along = lines.filter((l) => infos[l.guid] && samples.filter((p) => distToPaths(l.paths, p) <= NEAR_METERS).length >= samples.length * COVER);
  const info = (l) => infos[l.guid];
  const shortest = (list) => list.sort((a, b) => a.meters - b.meters)[0] || null;
  if (!along.length) {
    if (!own) return null;
    const mid = track[Math.floor(track.length / 2)];
    const hit = shortest(lines.filter((l) => info(l) && sameName(own, info(l).name) && distToPaths(l.paths, mid) <= NAMED_METERS));
    return hit && { line: hit, named: large(info(hit)) };
  }
  const same = own ? shortest(along.filter((l) => sameName(own, info(l).name))) : null;
  if (same) return { line: same, named: large(info(same)) };
  const ownWords = nameWords(own || '');
  if (ownWords.length) {
    const containing = along.filter((l) => { const w = nameWords(info(l).name); return ownWords.every((x) => w.includes(x)); });
    containing.sort((a, b) => {
      const wa = nameWords(info(a).name), wb = nameWords(info(b).name);
      return (wa.length - wb.length) || (wa.indexOf(ownWords[0]) - wb.indexOf(ownWords[0])) || (a.meters - b.meters);
    });
    if (containing.length) return { line: containing[0], named: large(info(containing[0])) };
  }
  const big = shortest(along.filter((l) => large(info(l))));
  if (big) return { line: big, named: true };
  return { line: shortest(along), named: false };
}

/** "Siberia Hut", "Siberia hut " and "Siberia" are one hut; Biv/Bivvy/Bivouac one word. */
export const hutKey = (name) => String(name).toLowerCase().replace(/\b(bivouac|bivvy|biv)\b/g, 'biv').replace(/\bhut\b/g, '').replace(/[^a-z0-9]+/g, ' ').trim();
/** The record for the map's hut `name` at `at`: same name nearest, else the nearest within `within` metres. */
export function matchHut(items, name, at, within = 250) {
  const key = hutKey(name);
  const by = (list) => list.sort((a, b) => meters(a.point, at) - meters(b.point, at))[0] || null;
  return by(items.filter((h) => hutKey(h.name) === key && meters(h.point, at) < 3000)) || by(items.filter((h) => meters(h.point, at) < within));
}

/** Huts within HUT_METERS of `line`, with how far along it they are, in order. */
export function stops(line, huts) {
  const out = [];
  for (const h of huts) {
    const n = nearestOn(line, h.point);
    if (n.d <= HUT_METERS) out.push({ hut: h, along: n.along });
  }
  out.sort((a, b) => a.along - b.along);
  const seen = new Set();
  return out.filter((s) => { const k = hutKey(s.hut.name); if (seen.has(k)) return false; seen.add(k); return true; });
}

/** ArcGIS characteristics CharNameN/CharValueN as an object. */
export function characteristics(a) {
  const out = {};
  for (const k of Object.keys(a)) if (k.startsWith('CharName')) {
    const name = String(a[k] ?? '').trim(), value = String(a['CharValue' + k.slice(8)] ?? '').trim();
    if (name && value) out[name] = value;
  }
  return out;
}
