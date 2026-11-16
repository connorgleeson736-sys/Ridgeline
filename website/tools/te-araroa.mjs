// Te Araroa for the map, from the Te Araroa Trust (teararoa.org.nz; its content and
// GIS data are CC BY 4.0, attributed to teararoa.org.nz):
// - the trail itself: the official "Tracks GPX" (every piece in order with its
//   kilometres), joined to the Google Earth file's land status per piece;
// - its 93 sections (and the bypasses and water options among them): name, region,
//   distance, time, grade and type from the site's section list, and each section's
//   notes (introduction, the walk, getting there, transport, accommodation, food and
//   supplies, track conditions) and line, from the site's section pages' data;
// - huts, campsites and holiday parks on the trail, from the Google Earth file.
// Photos are not taken (the site's photos belong to their photographers), nor are
// alerts and track status, which the map reads live (worker/te-araroa.js).
//
//   node tools/te-araroa.mjs
//
// Writes data/te-araroa.json (the map's index: trail, sections, places) and
// data/te-araroa-sections.json.gz (each section's notes, split per
// section by build.mjs). The Trust refreshes its files in November, February, May
// and August: the season's download links are on https://www.teararoa.org.nz/notes-and-maps/.
import { writeFileSync, readFileSync, existsSync, mkdirSync } from 'node:fs';
import { inflateRawSync, gzipSync } from 'node:zlib';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = join(dirname(fileURLToPath(import.meta.url)), '..');
const SITE = 'https://www.teararoa.org.nz';
const SEASON = process.env.TA_SEASON || '2026_27';
const TRACKS_ZIP = process.env.TA_TRACKS || `${SITE}/teararoatrail_${SEASON}/`;
const EARTH_ZIP = process.env.TA_EARTH || `${SITE}/te_araroa_${SEASON.replace('_', '-')}_google_earth/`;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function get(url, as = 'buffer') {
  // TA_CACHE=<folder>: keep downloads between runs (for working on this tool).
  const cacheFile = process.env.TA_CACHE && join(process.env.TA_CACHE, url.replace(/[^a-z0-9]+/gi, '_').slice(-180));
  if (cacheFile && existsSync(cacheFile)) { const b = readFileSync(cacheFile); return as === 'json' ? JSON.parse(b) : b; }
  const value = await fetchRetry(url, as);
  if (cacheFile) { mkdirSync(process.env.TA_CACHE, { recursive: true }); writeFileSync(cacheFile, as === 'json' ? JSON.stringify(value) : value); }
  return value;
}
async function fetchRetry(url, as) {
  for (let attempt = 0; ; attempt++) {
    try {
      const r = await fetch(url, { headers: { 'User-Agent': 'Bushline Maps data refresh' } });
      if (!r.ok) throw new Error(`${r.status} ${url}`);
      return as === 'json' ? await r.json() : Buffer.from(await r.arrayBuffer());
    } catch (e) {
      if (attempt >= 3) throw e;
      await sleep(1500 * (attempt + 1));
    }
  }
}

/** Files in a ZIP, by name (stored or deflated); `want` picks them. */
function unzip(buf, want) {
  let eocd = buf.length - 22;
  while (eocd >= 0 && buf.readUInt32LE(eocd) !== 0x06054b50) eocd--;
  if (eocd < 0) throw new Error('Not a ZIP file');
  let p = buf.readUInt32LE(eocd + 16);
  const out = {};
  for (let i = 0, n = buf.readUInt16LE(eocd + 10); i < n; i++) {
    const method = buf.readUInt16LE(p + 10), size = buf.readUInt32LE(p + 20), nameLen = buf.readUInt16LE(p + 28);
    const extra = buf.readUInt16LE(p + 30), comment = buf.readUInt16LE(p + 32), local = buf.readUInt32LE(p + 42);
    const name = buf.toString('utf8', p + 46, p + 46 + nameLen);
    p += 46 + nameLen + extra + comment;
    if (!want(name)) continue;
    const start = local + 30 + buf.readUInt16LE(local + 26) + buf.readUInt16LE(local + 28);
    const data = buf.subarray(start, start + size);
    out[name] = method === 0 ? Buffer.from(data) : inflateRawSync(data);
  }
  return out;
}

const unescape = (s) => s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&#39;/g, "'").replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').trim();
const tag = (xml, name) => { const m = new RegExp(`<${name}>([\\s\\S]*?)</${name}>`).exec(xml); return m ? unescape(m[1]) : ''; };
const r5 = (v) => Math.round(v * 1e5) / 1e5;

// ---- Geometry (flat metres, as the map's own core/geo.js) ----
const M = 111_320;
function nearestOn(line, p) {
  let best = { d: Infinity, i: 0, t: 0 };
  const k = Math.cos((p[1] * Math.PI) / 180) * M;
  for (let i = 1; i < line.length; i++) {
    const a = line[i - 1], b = line[i];
    const ax = (a[0] - p[0]) * k, ay = (a[1] - p[1]) * M, dx = (b[0] - a[0]) * k, dy = (b[1] - a[1]) * M;
    const l = dx * dx + dy * dy, t = l ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l)) : 0;
    const d = Math.hypot(ax + dx * t, ay + dy * t);
    if (d < best.d) best = { d, i, t };
  }
  return best;
}
const metres = (a, b) => Math.hypot((a[0] - b[0]) * Math.cos((((a[1] + b[1]) / 2) * Math.PI) / 180), a[1] - b[1]) * M;
const lengthOf = (line) => line.reduce((s, p, i) => (i ? s + metres(line[i - 1], p) : 0), 0);
/** Douglas–Peucker to `tol` metres (keeps any third value, e.g. height, on kept points). */
function simplify(line, tol) {
  if (line.length < 3) return line;
  const keep = new Uint8Array(line.length); keep[0] = keep[line.length - 1] = 1;
  const stack = [[0, line.length - 1]];
  while (stack.length) {
    const [a, b] = stack.pop();
    let worst = -1, at = -1;
    for (let i = a + 1; i < b; i++) { const d = nearestOn([line[a], line[b]], line[i]).d; if (d > worst) { worst = d; at = i; } }
    if (worst > tol) { keep[at] = 1; stack.push([a, at], [at, b]); }
  }
  return line.filter((_, i) => keep[i]);
}
/** A line as integer differences: [x0, y0, dx, dy, …] in 1e-5 degrees (and heights in m when `z`). */
function pack(line, z = false) {
  const out = [];
  let lx = 0, ly = 0, lz = 0;
  for (const p of line) {
    const X = Math.round(p[0] * 1e5), Y = Math.round(p[1] * 1e5), Z = Math.round(p[2] || 0);
    out.push(out.length ? X - lx : X, out.length ? Y - ly : Y);
    if (z) out.push(out.length > 3 ? Z - lz : Z);
    lx = X; ly = Y; lz = Z;
  }
  return out;
}

// ---- 1. The trail: Tracks GPX ----
console.log('Tracks GPX…');
const gpx = Object.values(unzip(await get(TRACKS_ZIP), (n) => /\.gpx$/i.test(n)))[0].toString('utf8');
const trailName = tag(/<metadata>([\s\S]*?)<\/metadata>/.exec(gpx)?.[1] || '', 'name');
const pieces = [];
for (const m of gpx.matchAll(/<trk>([\s\S]*?)<\/trk>/g)) {
  const trk = m[1], range = /([\d.]+)\s*km\s*to\s*([\d.]+)\s*km/.exec(tag(trk, 'cmt'));
  const line = [];
  for (const pt of trk.matchAll(/<trkpt\b([^>]*)>/g)) {
    const lat = /\blat="([-\d.]+)"/.exec(pt[1])?.[1], lon = /\blon="([-\d.]+)"/.exec(pt[1])?.[1];
    const q = [r5(+lon), r5(+lat)];
    if (!line.length || q[0] !== line[line.length - 1][0] || q[1] !== line[line.length - 1][1]) line.push(q);
  }
  if (line.length >= 2 && range) pieces.push({ seq: +tag(trk, 'number') || 0, name: tag(trk, 'name'), from: +range[1], to: +range[2], line });
}
pieces.sort((a, b) => a.from - b.from || a.seq - b.seq);
// Each piece southbound: the GPX holds some pieces the other way round. The first
// starts at Cape Reinga; each next one starts at the end nearer the last one's end.
{
  let end = [172.6776, -34.4267];
  for (const p of pieces) {
    if (metres(p.line[p.line.length - 1], end) < metres(p.line[0], end)) p.line.reverse();
    end = p.line[p.line.length - 1];
  }
}
const total = Math.max(...pieces.map((p) => p.to));

/** Kilometre (southbound) of the trail nearest p, and how far p is from it. */
function kmAt(p) {
  let best = { d: Infinity, km: 0, piece: -1 };
  for (let i = 0; i < pieces.length; i++) {
    const pc = pieces[i];
    const n = nearestOn(pc.line, p);
    if (n.d >= best.d) continue;
    const len = lengthOf(pc.line) || 1;
    let along = 0;
    for (let j = 1; j < n.i; j++) along += metres(pc.line[j - 1], pc.line[j]);
    along += metres(pc.line[n.i - 1], pc.line[n.i]) * n.t;
    best = { d: n.d, km: pc.from + ((pc.to - pc.from) * along) / len, piece: i };
  }
  return best;
}

// ---- 2. Google Earth file: land status per piece; huts, campsites, holiday parks ----
console.log('Google Earth file…');
const kmz = Object.values(unzip(await get(EARTH_ZIP), (n) => /\.kmz$/i.test(n)))[0];
const kml = Object.values(unzip(kmz, (n) => /\.kml$/i.test(n)))[0].toString('utf8');
const cells = (pm) => Object.fromEntries([...pm.matchAll(/<td>([^<]{1,40})<\/td>\s*<td>([\s\S]*?)<\/td>/g)].map(([, k, v]) => [k.trim(), unescape(v)]));
const value = (v) => (v && v !== '<Null>' ? v : '');
const href = (v) => /href="([^"]+)"/.exec(v || '')?.[1] || (/^https?:\/\//.test(value(v)) ? value(v) : '');
const legal = new Map(), places = [], bypassLines = [];
for (const m of kml.matchAll(/<Placemark\b[\s\S]*?<\/Placemark>/g)) {
  const pm = m[0], f = cells(pm);
  if (pm.includes('<LineString>') && f.SEQUENCE && f.LEGALSTAT) legal.set(+f.SEQUENCE, value(f.LEGALSTAT));
  // The official bypass lines: each one clean and whole (the section pages' lines for
  // options come in loose parts, a boat leg among them).
  if (pm.includes('<LineString>') && /bypass/i.test(f.Type || '')) {
    const lines = [...pm.matchAll(/<coordinates>([\s\S]*?)<\/coordinates>/g)].map((c) => c[1].trim().split(/\s+/).map((t) => t.split(',').map(Number)).map(([x, y]) => [r5(x), r5(y)])).filter((l) => l.length >= 2);
    if (lines.length) bypassLines.push({ name: value(f.Name) || tag(pm, 'name'), lines });
  }
  if (!pm.includes('<Point>')) continue;
  const at = /<coordinates>\s*([-\d.]+),([-\d.]+)/.exec(pm);
  if (!at) continue;
  const point = [r5(+at[1]), r5(+at[2])];
  if (f.Type && /hut|campsite/i.test(f.Type)) {
    places.push({ name: value(f['Name of site']) || tag(pm, 'name'), type: f.Type, category: value(f.Category), facilities: value(f.Facilities), booking: value(f['Booking Required']) === 'Yes', pass: value(f['Te Araroa Pass']) === 'Yes', page: href(f['URL to webpage']), point });
  } else if (f.categories && f.name) {
    places.push({ name: value(f.name), type: 'Holiday park', facilities: value(f.tags), phone: value(f.phone), address: value(f.physical_address), website: href(f.website), point });
  }
}

/** An option's line: the official bypass line of the same name where there is one;
 * otherwise its parts, less any boat leg on a walking or road option. */
const STOP = new Set(['bypass', 'to', 'the', 'by', 'via', 'and', 'walking', 'north', 'south']);
const words = (n) => n.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/ngungruru/g, 'ngunguru').split(/[^a-z0-9]+/).filter((w) => w && !STOP.has(w));
function optionLines(name, parts) {
  const own = words(name);
  // Shared words, less a little for each of the bypass's own words not in the name
  // (so "Whanganui River Road Bypass" takes the walking bypass, not the kayak one).
  const scored = bypassLines.map((b) => { const w = words(b.name), hit = w.filter((x) => own.includes(x)).length; return { b, hit, n: hit - 0.1 * (w.length - hit) }; }).filter((x) => x.hit >= 2);
  const top = Math.max(-Infinity, ...scored.map((x) => x.n));
  if (scored.length) return scored.filter((x) => Math.abs(x.n - top) < 1e-9).flatMap((x) => x.b.lines);
  const water = /kayak|water|ferry|interislander|canoe|boat/i.test(name);
  const boat = (l) => l.length < 40 && lengthOf(l) / (l.length - 1) > 800;
  const kept = water ? parts : parts.filter((l) => !boat(l));
  return (kept.length ? kept : parts).map((l) => l.map(([x, y]) => [x, y]));
}

// ---- 3. Sections: the site's section list, then each section's page data ----
console.log('Sections…');
const overview = await get(`${SITE}/wp-json/ta/v1/overview`, 'json');
const sections = [], notes = {};
for (const [n, t] of overview.tracks.entries()) {
  await sleep(250);
  const page = (await get(`${SITE}/wp-json/ta/v1/track/${t.slug}`, 'json')).data || {};
  const coords = (page.geojson?.geometry?.coordinates || []).filter((l) => l.length >= 2);
  const lines = coords.map((l) => l.map(([x, y, z]) => [r5(x), r5(y), Math.round(z || 0)]));
  // Where it runs on the trail: the kilometres of points along it (every part's ends
  // and about 200 between) that lie on the trail. An option (bypass, water or ferry
  // leg) is mostly off it: its range is where it leaves and rejoins.
  const flat = lines.flat();
  const step = Math.max(1, Math.floor(flat.length / 200));
  const samples = [...flat.filter((_, i) => i % step === 0), ...lines.flatMap((l) => [l[0], l[l.length - 1]])].map((p) => kmAt(p));
  const alternative = samples.filter((k) => k.d > 150).length > samples.length * 0.3;
  let on = samples.filter((k) => k.d <= (alternative ? 600 : 150));
  if (!on.length) on = samples;
  const a = on.length ? Math.min(...on.map((k) => k.km)) : null, b = on.length ? Math.max(...on.map((k) => k.km)) : null;
  sections.push({
    slug: t.slug, n: n + 1, name: unescape(t.name || ''), region: t.region || '', island: t.island || '',
    length: t.len ? Math.round(t.len * 10) / 10 : null, time: unescape(t.time || ''), kind: t.tc || '', grade: t.diff || null,
    from: a == null ? null : Math.round(a * 10) / 10, to: b == null ? null : Math.round(b * 10) / 10, alternative, on: on.map((k) => k.km),
    // An option's own line is drawn beside the trail (to about 5 m).
    line: alternative ? optionLines(t.name || '', lines).map((l) => simplify(l, 5)) : null,
  });
  // Text only: pictures in the notes (embedded or linked) belong to their photographers.
  const words = (html) => (html || '').replace(/<img\b[^>]*>/gi, '').replace(/<(figure|picture|svg)\b[\s\S]*?<\/\1>/gi, '');
  notes[t.slug] = {
    intro: words(page.intro), walk: words(page.description), access: words(page.access), transport: words(page.transport),
    accommodation: words(page.accommodation), food: words(page.foodAndSupply), conditions: words(page.conditions),
    website: page.link?.website || '',
  };
  process.stdout.write(`\r${n + 1}/${overview.tracks.length} ${t.slug.padEnd(50)}`);
}
console.log();

// The trail passes some places twice (the Mangatepopo valley, up to Tongariro's crater
// and back along the Northern Circuit), so a section's points can match two stretches.
// In walking order, each section starts where the last ended and runs about its length.
{
  let end = 0;
  for (const s of sections) {
    if (s.alternative || !s.on.length) continue;
    const after = s.on.filter((k) => k >= end - 1);
    const from = after.length ? Math.min(...after) : s.from;
    const within = s.on.filter((k) => k >= from && k <= from + (s.length || 0) * 1.3 + 1);
    // Contiguous: a section starts where the last one ended (its own line can take
    // a slightly different way from the official track file in places).
    s.from = end ? end : Math.round(from * 10) / 10;
    s.to = Math.max(s.from, Math.round((within.length ? Math.max(...within) : s.to) * 10) / 10);
    end = s.to;
  }
  for (const s of sections) delete s.on;
}
// Main-trail pieces to their section (the section whose range holds the piece's middle).
const mains = sections.filter((s) => !s.alternative && s.from != null).sort((x, y) => x.from - y.from);
const sectionAt = (km) => mains.find((s) => km >= s.from - 0.05 && km <= s.to + 0.05) || null;
// Places within 1.5 km of the trail (or of an option), with their kilometre.
const placed = [];
for (const p of places) {
  const k = kmAt(p.point);
  let section = k.d <= 1500 ? sectionAt(k.km) : null, dist = k.d;
  for (const s of sections.filter((x) => x.alternative && x.line)) {
    const d = Math.min(...s.line.map((l) => nearestOn(l, p.point).d));
    if (d < dist && d <= 1500) { section = s; dist = d; }
  }
  if (!section) continue;
  placed.push({ ...p, km: Math.round(k.km * 10) / 10, off: Math.round(dist), section: section.slug });
}
placed.sort((a, b) => a.km - b.km);

// ---- 4. Resupply: the usual stops (data/te-araroa-resupply.json, from walkers'
// published resupply guides), placed on the trail, with the shops the Trust's section
// notes name; and small stops near the trail that only the notes mention ----
console.log('Resupply…');
const gazetteer = readFileSync(join(here, '..', 'native-wgpu/android/app/src/main/assets/search/places.tsv'), 'utf8')
  .split('\n').map((l) => l.split('\t')).filter((c) => c[0] === 'place' && /^(Town|City|Village|Locality|Suburb|Place)$/.test(c[6] || ''))
  .map((c) => ({ name: c[1], point: [+c[4], +c[3]], type: c[6] }));
const fold = (t) => t.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[’']/g, '');
const RANK = { City: 0, Town: 1, Village: 2, Locality: 3, Suburb: 4, Place: 5 };
const towns = new Map();
for (const g of gazetteer) if (g.name.length >= 4) { const k = fold(g.name); if (!towns.has(k)) towns.set(k, []); towns.get(k).push(g); }
const plain = (html) => unescape((html || '').replace(/<\/(h\d|li|p|div|ul)>/gi, '\n').replace(/<br\s*\/?>/gi, '\n').replace(/<[^>]+>/g, ' ')).replace(/[ \t]+/g, ' ').replace(/\n\s*/g, '\n').trim();
const SHOP = /supermarket|new world|countdown|woolworths|pak.?n.?save|four square|4 square|freshchoice|fresh choice|supervalue|super value|foodway|superette/i;
const SMALL = /dairy|general store|\bstore\b|shop|service (station|centre)|petrol|groceries|grocery|tearooms|caf[eé]|bakery/i;
const curated = JSON.parse(readFileSync(join(here, 'data/te-araroa-resupply.json'), 'utf8')).stops;
const stops = [];
for (const c of curated) {
  const list = (towns.get(fold(c.place || c.name)) || []).slice().sort((a, b) => (RANK[a.type] ?? 9) - (RANK[b.type] ?? 9));
  const best = list.map((g) => ({ g, k: kmAt(g.point) })).sort((a, b) => (RANK[a.g.type] ?? 9) - (RANK[b.g.type] ?? 9) || a.k.d - b.k.d).find((x) => x.k.d < 60_000);
  if (!best) { console.log(`  not placed: ${c.name}`); continue; }
  stops.push({ name: c.name, point: best.g.point, km: c.km ?? Math.round(best.k.km * 10) / 10, off: Math.round(best.k.d), level: c.level, note: [c.note, c.via].filter(Boolean).join(' '), bounce: !!c.bounce, via: !!c.via, shops: [], from: 'guides' });
}
for (const s of sections) {
  const text = plain(notes[s.slug]?.food);
  if (!text) continue;
  let heading = '';
  const lines = text.split('\n').map((t) => t.trim()).filter(Boolean).map((t) => { if (t.length < 60 && !/[-–:]\s|\d{3}/.test(t)) heading = t; return { t, heading }; });
  for (const { t, heading: h } of lines) {
    // "There is no shop…", "no supplies": not a stop.
    const none = /\b(no|not any|nor)\s+(retail\s+)?(shops?|stores?|supermarkets?|supplies|food|groceries|resupply)\b|there (is|are) no\b|closed (down|permanently)/i.test(t);
    const level = none ? '' : SHOP.test(t) ? 'full' : SMALL.test(t) ? 'limited' : '';
    const hay = fold(`${h} ${t}`);
    for (const [k, list] of towns) {
      if (!hay.includes(k) || !new RegExp(`(^|[^a-z])${k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}([^a-z]|$)`).test(hay)) continue;
      const near = list.map((g) => ({ g, k: kmAt(g.point) })).sort((a, b) => a.k.d - b.k.d)[0];
      if (!near) continue;
      // The same stop as one already listed (by name, or within 5 km).
      let e = stops.find((x) => fold(x.name) === fold(near.g.name) || metres(x.point, near.g.point) < 5000);
      if (!e) {
        // A stop only the notes name: a shop within 3 km of the trail.
        if (!level || near.k.d > 3000) continue;
        e = { name: near.g.name, point: near.g.point, km: Math.round(near.k.km * 10) / 10, off: Math.round(near.k.d), level, note: '', bounce: false, shops: [], from: 'notes' };
        stops.push(e);
      } else if (e.from === 'notes' && level === 'full') e.level = 'full';
      const line = t.replace(/\s*-\s*P:.*$/, '').replace(/\s+/g, ' ').slice(0, 140);
      if ((level || /bounce/i.test(line)) && fold(line) !== k && !e.shops.includes(line) && e.shops.length < 5) e.shops.push(line);
    }
  }
}
const resupply = stops.sort((a, b) => a.km - b.km);
// What kind of food stop: a supermarket, a store, only a café, or nothing (send a box).
for (const r of resupply) {
  const text = `${r.note} ${r.shops.join(' ')}`;
  r.kind = r.level === 'full' ? 'supermarket' : r.level === 'box' ? 'box'
    : /caf[eé]|tearoom|bakery|coffee|restaurant/i.test(text) && !/\bstore|shop\b|dairy|square|superette|grocer|general/i.test(text) ? 'cafe' : 'store';
}

// ---- 5. More places to stay, from each section's Accommodation notes: campsites,
// holiday parks, hostels, bunkhouses and walkers' lodges (not hotels or motels) ----
console.log('Accommodation notes…');
const names = readFileSync(join(here, '..', 'native-wgpu/android/app/src/main/assets/search/places.tsv'), 'utf8')
  .split('\n').map((l) => l.split('\t')).filter((c) => /^(place|camp|hut|area|coast)$/.test(c[0]) && (c[1] || '').length >= 5)
  .map((c) => ({ key: fold(c[1]), point: [+c[4], +c[3]] }));
const byKey = new Map();
for (const n of names) { if (!byKey.has(n.key)) byKey.set(n.key, []); byKey.get(n.key).push(n.point); }
function pointAtKm(km) {
  const p = pieces.find((x) => km >= x.from && km <= x.to);
  if (!p) return null;
  const total = lengthOf(p.line), want = ((km - p.from) / Math.max(1e-9, p.to - p.from)) * total;
  let walked = 0;
  for (let i = 1; i < p.line.length; i++) {
    const seg = metres(p.line[i - 1], p.line[i]);
    if (walked + seg >= want) { const t = seg ? (want - walked) / seg : 0; return [r5(p.line[i - 1][0] + (p.line[i][0] - p.line[i - 1][0]) * t), r5(p.line[i - 1][1] + (p.line[i][1] - p.line[i - 1][1]) * t)]; }
    walked += seg;
  }
  return p.line[p.line.length - 1];
}
/** The place a name names (the longest gazetteer name in it), nearest the trail. */
function locate(text) {
  const f = fold(text);
  let best = null;
  for (const [key, pts] of byKey) {
    if (key.length <= (best?.key.length || 0) || !f.includes(key) || !new RegExp(`(^|[^a-z])${key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}([^a-z]|$)`).test(f)) continue;
    const near = pts.map((p) => ({ p, k: kmAt(p) })).sort((a, b) => a.k.d - b.k.d)[0];
    if (near.k.d <= 15_000) best = { key, point: near.p, k: near.k };
  }
  return best;
}
const STAY = /camp(ground|site|ing)?\b|holiday park|motor camp|top ?10|backpacker|hostel|\byha\b|bunk ?house|lodge|\bhut\b|cabins?\b/i;
const NOT = /\bmotel|\bhotel|motor lodge|econo|microtel|b ?& ?b\b|bed and breakfast|apartment|resort|chalets?\b|villas?\b/i;
const typeOf = (t) => (/holiday park|motor camp|top ?10/i.test(t) ? 'Holiday park' : /camp/i.test(t) ? 'Campsite' : /backpacker|hostel|\byha\b/i.test(t) ? 'Hostel' : /\bhut\b/i.test(t) ? 'Hut' : 'Lodge');
const famous = (a, b) => { const strip = (x) => fold(x).replace(/\b(campsite|campground|camp|holiday park|motor camp|top 10|hut|lodge|backpackers?|the)\b/g, '').replace(/[^a-z]/g, ''); const x = strip(a), y = strip(b); return x.length >= 4 && y.length >= 4 && (x.includes(y) || y.includes(x)); };
let added = 0;
for (const s of sections) {
  const html = notes[s.slug]?.accommodation || '';
  if (!html) continue;
  let town = '';
  for (const m of html.matchAll(/<h\d[^>]*>([\s\S]*?)<\/h\d>|<(li|p)\b[^>]*>([\s\S]*?)<\/\2>/gi)) {
    if (m[1] != null) { town = unescape(m[1].replace(/<[^>]+>/g, '')).trim(); continue; }
    const raw = m[3];
    if (/text-decoration:\s*line-through/i.test(raw) && !/<\/span>\s*\S/.test(raw.replace(/<span[^>]*line-through[^>]*>[\s\S]*?<\/span>/gi, ''))) continue;
    const text = unescape(raw.replace(/<span[^>]*line-through[^>]*>[\s\S]*?<\/span>/gi, '').replace(/<br\s*\/?>/gi, ' ').replace(/<[^>]+>/g, ' ')).replace(/\s+/g, ' ').trim();
    if (!text) continue;
    const link = /<a\b[^>]*href="(https?:[^"]+)"[^>]*>([\s\S]*?)<\/a>/i.exec(raw);
    let name = link ? unescape(link[2].replace(/<[^>]+>/g, '')).replace(/\s+/g, ' ').trim() : text.split(/\s[-–—]\s|,\s/)[0].trim();
    name = name.replace(/[\s()\-–—:.,]+$/, '').replace(/^\W+/, '').replace(/\s*\($/, '').trim();
    if (!name || name.length > 60 || name.length < 5 || /^(there|the|a|an|this|book|turn|take|check|hot|please|note|see|call|ask|via|from|at|on|in)\b/i.test(name) || /airbnb|shower|book online|website|phone|email/i.test(name)) continue;
    const desc = text.slice(text.indexOf(name) + name.length).replace(/^[\s,\-–—]+/, '');
    if (NOT.test(name) && !/camp/i.test(name)) continue;
    // The name says what it is (a campsite, holiday park, hut, lodge, hostel…).
    if (!STAY.test(name) || name.split(/\s+/).length > 8 || /\b(has|have|offers?|range of|is|are)\b/i.test(name)) continue;
    if ((name.match(/\(/g) || []).length > (name.match(/\)/g) || []).length) name = name.replace(/\s*\([^)]*$/, '').trim();
    if (placed.some((p) => famous(p.name, name))) continue;
    // Where: a km on the trail if the notes give one, else its own name, else its town.
    let point = null, approx = false, kmv = null;
    const kmText = /\bkm\s*([\d,]+(?:\.\d+)?)/i.exec(text);
    if (kmText) { kmv = +kmText[1].replace(/,/g, ''); if (kmv > total || kmv < 0) kmv = null; else point = pointAtKm(kmv); }
    if (!point) { const own = locate(name); if (own) point = own.point; }
    if (!point) { const where = locate(`${town} ${desc}`); if (where) { point = where.point; approx = true; } }
    if (!point) continue;
    const k = kmAt(point);
    if (k.d > 15_000) continue;
    if (placed.some((p) => metres(p.point, point) < 250 && typeOf(name) === p.type)) continue;
    const phone = /\bP:\s*([0-9 +()]{7,20})/.exec(text)?.[1]?.trim() || '';
    placed.push({ name, type: typeOf(`${name} ${STAY.test(name) ? '' : desc}`), facilities: desc.replace(/\bP:\s*[0-9 +()]{7,20}/g, '').replace(/\bE:\s*\S+@\S+/g, '').replace(/\s*[-–—]\s*$/,'').replace(/\s+[-–—]\s+[-–—]\s+/g, ' – ').trim().slice(0, 160),
      phone, website: link ? link[1] : '', point, km: Math.round((kmv ?? k.km) * 10) / 10, off: Math.round(k.d), section: s.slug, from: 'notes', approx, town: approx ? town : '' });
    added++;
  }
}
placed.sort((a, b) => a.km - b.km);
console.log(`  ${added} more places to stay from the notes (${placed.filter((p) => p.approx).length} placed by town only)`);
const index = {
  name: trailName, season: (/(\d{4}-\d{2})/.exec(trailName) || [])[1] || '', total,
  pieces: pieces.map((p) => {
    const flat = [];
    let lx = 0, ly = 0;
    for (const [x, y] of p.line) { const X = Math.round(x * 1e5), Y = Math.round(y * 1e5); flat.push(flat.length ? X - lx : X, flat.length ? Y - ly : Y); lx = X; ly = Y; }
    return [p.name, p.from, p.to, legal.get(p.seq) || '', sectionAt((p.from + p.to) / 2)?.slug || '', flat];
  }),
  sections: sections.map(({ line, ...s }) => ({ ...s, line: line && line.map((l) => pack(l)) })),
  places: placed,
  resupply,
};
writeFileSync(join(here, 'data/te-araroa.json'), JSON.stringify(index));
writeFileSync(join(here, 'data/te-araroa-sections.json.gz'), gzipSync(JSON.stringify(notes)));
console.log(JSON.stringify({ trail: trailName, km: total, pieces: pieces.length, sections: sections.length, options: sections.filter((s) => s.alternative).map((s) => s.slug), unplaced: sections.filter((s) => s.from == null).map((s) => s.slug), places: placed.length, resupply: resupply.length }));
for (const r of resupply) console.log(`  km ${String(r.km).padStart(6)}  ${r.off > 2000 ? `${Math.round(r.off / 1000)} km off`.padEnd(9) : 'on trail '}  ${r.level.padEnd(8)} ${r.bounce ? 'box ' : '    '}${r.name}  [${r.from}${r.shops.length ? `, ${r.shops.length} shops` : ''}]`);
