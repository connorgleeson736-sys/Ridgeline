// Compiles every DOC walk's page (browsers cannot read doc.govt.nz: no CORS)
// into data/walk-pages.json.gz: summary, introduction and sections, keyed by
// the walk's line id. Also writes data/walk-facts.json (distance, time,
// difficulty, dogs). DOC content is CC BY 4.0; the site credits DOC.
// Run now and then: node tools/walk-pages.mjs   (FRESH=1 to refetch all)
import { writeFileSync, readFileSync, existsSync } from 'node:fs';
import { gzipSync, gunzipSync } from 'node:zlib';
import { overview, parse } from '../src/js/core/docpages.js';
const LIST = 'https://services1.arcgis.com/3JjYDyG3oajxU6HO/ArcGIS/rest/services/DOC_Walking_Experiences/FeatureServer/0/query?where=1%3D1&outFields=name%2CwalkingAndTrampingWebPage&returnGeometry=false&f=json';
const pagesFile = new URL('../data/walk-pages.json.gz', import.meta.url), factsFile = new URL('../data/walk-facts.json', import.meta.url);
const guidOf = (link) => { const h = /\/link\/([0-9a-fA-F]{32})\./.exec(link || '')?.[1]?.toLowerCase(); return h && `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`; };
const pages = !process.env.FRESH && existsSync(pagesFile) ? JSON.parse(gunzipSync(readFileSync(pagesFile))) : {};
const list = (await (await fetch(LIST)).json()).features.map((f) => f.attributes);
const todo = list.filter((a) => guidOf(a.walkingAndTrampingWebPage) && !pages[guidOf(a.walkingAndTrampingWebPage)]);
const save = () => {
  writeFileSync(pagesFile, gzipSync(JSON.stringify(pages), { level: 9 }));
  writeFileSync(factsFile, JSON.stringify(Object.fromEntries(Object.entries(pages).map(([g, p]) => [g, [p.o.distance, p.o.time, p.o.level, p.o.dogs]]))));
};
let done = 0, failed = 0;
const queue = todo.slice();
await Promise.all(Array.from({ length: 4 }, async () => {
  while (queue.length) {
    const a = queue.shift(), guid = guidOf(a.walkingAndTrampingWebPage);
    let ok = false;
    for (let attempt = 0; attempt < 3 && !ok; attempt++) {
      try {
        const r = await fetch(a.walkingAndTrampingWebPage, { headers: { 'User-Agent': 'BushlineMapsBuild/1.0' } });
        if (!r.ok) throw new Error(r.status);
        const html = await r.text();
        pages[guid] = { o: overview(html), s: parse(html) };
        ok = true;
      } catch { await new Promise((s) => setTimeout(s, 1500 * (attempt + 1))); }
    }
    if (!ok) failed++;
    if (++done % 100 === 0) { console.log(done, '/', todo.length); save(); }
  }
}));
save();
console.log(JSON.stringify({ walks: list.length, pages: Object.keys(pages).length, failed }));
