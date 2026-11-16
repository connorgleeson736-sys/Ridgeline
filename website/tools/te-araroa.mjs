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
import { writeFileSync } from 'node:fs';
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
const legal = new Map(), places = [];
for (const m of kml.matchAll(/<Placemark\b[\s\S]*?<\/Placemark>/g)) {
  const pm = m[0], f = cells(pm);
  if (pm.includes('<LineString>') && f.SEQUENCE && f.LEGALSTAT) legal.set(+f.SEQUENCE, value(f.LEGALSTAT));
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
    line: alternative ? lines.map((l) => simplify(l, 5)) : null,
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
};
writeFileSync(join(here, 'data/te-araroa.json'), JSON.stringify(index));
writeFileSync(join(here, 'data/te-araroa-sections.json.gz'), gzipSync(JSON.stringify(notes)));
console.log(JSON.stringify({ trail: trailName, km: total, pieces: pieces.length, sections: sections.length, options: sections.filter((s) => s.alternative).map((s) => s.slug), unplaced: sections.filter((s) => s.from == null).map((s) => s.slug), places: placed.length }));
