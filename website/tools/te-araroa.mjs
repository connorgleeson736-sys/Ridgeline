// Te Araroa, from the Te Araroa Trust's official "Tracks GPX" (teararoa.org.nz,
// Notes & Maps; CC BY 4.0, attributed to teararoa.org.nz): every piece of the
// trail in order with its kilometre range, and the kilometre markers.
//
//   node tools/te-araroa.mjs [zip URL]
//
// Writes data/te-araroa.json. The Trust refreshes the files in November,
// February, May and August; the download page is
// https://www.teararoa.org.nz/notes-and-maps/ ("Tracks GPX").
//
// Output: { name, updated, source, pieces: [[order, name, kmFrom, kmTo, coords]],
// km: [km, lon, lat, ...] } where coords is a flat list of integer degrees×1e5,
// the first point absolute and each next one a difference from the last.
import { writeFileSync } from 'node:fs';
import { inflateRawSync } from 'node:zlib';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = join(dirname(fileURLToPath(import.meta.url)), '..');
const url = process.argv[2] || 'https://www.teararoa.org.nz/teararoatrail_2026_27/';

/** The first .gpx in a ZIP (central directory, stored or deflated). */
function gpxFromZip(buf) {
  let eocd = buf.length - 22;
  while (eocd >= 0 && buf.readUInt32LE(eocd) !== 0x06054b50) eocd--;
  if (eocd < 0) throw new Error('Not a ZIP file');
  let p = buf.readUInt32LE(eocd + 16);
  const count = buf.readUInt16LE(eocd + 10);
  for (let i = 0; i < count; i++) {
    const method = buf.readUInt16LE(p + 10), size = buf.readUInt32LE(p + 20), nameLen = buf.readUInt16LE(p + 28);
    const extra = buf.readUInt16LE(p + 30), comment = buf.readUInt16LE(p + 32), local = buf.readUInt32LE(p + 42);
    const name = buf.toString('utf8', p + 46, p + 46 + nameLen);
    p += 46 + nameLen + extra + comment;
    if (!/\.gpx$/i.test(name)) continue;
    const start = local + 30 + buf.readUInt16LE(local + 26) + buf.readUInt16LE(local + 28);
    const data = buf.subarray(start, start + size);
    return (method === 0 ? data : inflateRawSync(data)).toString('utf8');
  }
  throw new Error('No GPX in the ZIP');
}

const unescape = (s) => s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&').trim();
const tag = (xml, name) => { const m = new RegExp(`<${name}>([\\s\\S]*?)</${name}>`).exec(xml); return m ? unescape(m[1]) : ''; };
const E5 = (v) => Math.round(+v * 1e5);

const response = await fetch(url);
if (!response.ok) throw new Error(`Download failed: ${response.status}`);
const gpx = gpxFromZip(Buffer.from(await response.arrayBuffer()));

const pieces = [];
for (const m of gpx.matchAll(/<trk>([\s\S]*?)<\/trk>/g)) {
  const trk = m[1];
  const range = /([\d.]+)\s*km\s*to\s*([\d.]+)\s*km/.exec(tag(trk, 'cmt'));
  for (const seg of trk.matchAll(/<trkseg>([\s\S]*?)<\/trkseg>/g)) {
    const coords = [];
    let lx = 0, ly = 0;
    for (const pt of seg[1].matchAll(/<trkpt\b[^>]*?\blat="([-\d.]+)"[^>]*?\blon="([-\d.]+)"|<trkpt\b[^>]*?\blon="([-\d.]+)"[^>]*?\blat="([-\d.]+)"/g)) {
      const x = E5(pt[2] ?? pt[3]), y = E5(pt[1] ?? pt[4]);
      if (coords.length && x === lx && y === ly) continue;
      coords.push(coords.length ? x - lx : x, coords.length ? y - ly : y);
      lx = x; ly = y;
    }
    if (coords.length >= 4) pieces.push([+tag(trk, 'number') || 0, tag(trk, 'name'), range ? +range[1] : null, range ? +range[2] : null, coords]);
  }
}
pieces.sort((a, b) => a[0] - b[0] || (a[2] ?? 0) - (b[2] ?? 0));

const km = [];
for (const m of gpx.matchAll(/<wpt\b([^>]*)>([\s\S]*?)<\/wpt>/g)) {
  const n = /^([\d.]+)\s*km$/.exec(tag(m[2], 'name'));
  const lat = /\blat="([-\d.]+)"/.exec(m[1])?.[1], lon = /\blon="([-\d.]+)"/.exec(m[1])?.[1];
  if (n && lat && lon) km.push(+n[1], E5(lon) / 1e5, E5(lat) / 1e5);
}

const meta = /<metadata>([\s\S]*?)<\/metadata>/.exec(gpx)?.[1] || '';
const out = { name: tag(meta, 'name'), updated: tag(meta, 'time').slice(0, 10), source: 'https://www.teararoa.org.nz/notes-and-maps/', pieces, km };
writeFileSync(join(here, 'data/te-araroa.json'), JSON.stringify(out));
const points = pieces.reduce((s, p) => s + p[4].length / 2, 0);
console.log(JSON.stringify({ name: out.name, updated: out.updated, pieces: pieces.length, points, markers: km.length / 3, kmTo: Math.max(...pieces.map((p) => p[3] || 0)) }));
