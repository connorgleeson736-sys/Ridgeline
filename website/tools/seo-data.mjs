// Downloads what the walk and hut pages need from DOC's open data (ArcGIS, CC BY 4.0)
// into data/seo-data.json.gz: every walk's line (simplified to about 20 m) and
// attributes, and every hut with its region, place, facilities and booking.
//   NODE_USE_ENV_PROXY=1 node tools/seo-data.mjs
import { writeFileSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { fileURLToPath } from 'node:url';

const ORG = 'https://services1.arcgis.com/3JjYDyG3oajxU6HO/ArcGIS/rest/services/';
const out = fileURLToPath(new URL('../data/seo-data.json.gz', import.meta.url));
async function all(service, fields, extra = '') {
  const rows = [];
  for (let offset = 0; ; offset += 1000) {
    const url = `${ORG}${service}/FeatureServer/0/query?where=1%3D1&outFields=${encodeURIComponent(fields)}&returnGeometry=true&outSR=4326&resultOffset=${offset}&resultRecordCount=1000${extra}&f=json`;
    let j;
    for (let a = 0; ; a++) { try { const r = await fetch(url); j = await r.json(); if (j.features) break; } catch {} if (a > 4) throw new Error(`${service} ${offset}`); await new Promise((s) => setTimeout(s, 2000 * 2 ** a)); }
    rows.push(...j.features);
    if (!j.exceededTransferLimit && j.features.length < 1000) return rows;
  }
}
const r5 = (v) => Math.round(v * 1e5) / 1e5;
const text = (v) => (v == null ? '' : String(v).trim());
const guid = (s) => { const m = /([0-9a-f]{32})/i.exec(s || ''); return m ? m[1].toLowerCase().replace(/(.{8})(.{4})(.{4})(.{4})(.{12})/, '$1-$2-$3-$4-$5') : null; };

const walks = {};
for (const f of await all('DOC_Walking_Experiences', 'name,difficulty,completionTime,introductionThumbnail,walkingAndTrampingWebPage')) {
  const g = guid(f.attributes.walkingAndTrampingWebPage); if (!g) continue;
  walks[g] = { name: text(f.attributes.name), difficulty: text(f.attributes.difficulty), time: text(f.attributes.completionTime), thumb: text(f.attributes.introductionThumbnail), point: f.geometry ? [r5(f.geometry.x), r5(f.geometry.y)] : null, lines: [] };
}
for (const f of await all('DOC_WebsiteWalkingandTrampingLns_DTO', 'EPISERVERGUID', '&maxAllowableOffset=0.0002')) {
  const g = text(f.attributes.EPISERVERGUID).toLowerCase(); const w = walks[g]; if (!w || !f.geometry?.paths) continue;
  for (const p of f.geometry.paths) w.lines.push(p.map(([x, y]) => [r5(x), r5(y)]));
}
const huts = [];
for (const f of await all('DOC_Huts', 'name,place,region,bookable,facilities,staticLink,introductionThumbnail')) {
  const a = f.attributes; if (!f.geometry) continue;
  huts.push({ name: text(a.name), place: text(a.place), region: text(a.region), bookable: /yes|true/i.test(text(a.bookable)), facilities: text(a.facilities), link: text(a.staticLink), thumb: text(a.introductionThumbnail), point: [r5(f.geometry.x), r5(f.geometry.y)] });
}
writeFileSync(out, gzipSync(JSON.stringify({ fetched: new Date().toISOString().slice(0, 10), walks, huts })));
console.log(JSON.stringify({ walks: Object.keys(walks).length, withLines: Object.values(walks).filter((w) => w.lines.length).length, huts: huts.length }));
