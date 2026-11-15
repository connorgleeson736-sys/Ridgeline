// Compiles DOC's own summary of every walk (distance, time, dogs) from the walks'
// DOC pages, which browsers cannot read (no CORS): data/walk-facts.json, keyed
// by the walk's line id. Run now and then: node tools/walk-facts.mjs
// (DocPages.overview in the Android app; DOC content is CC BY 4.0.)
import { writeFileSync, readFileSync, existsSync } from 'node:fs';
const LIST = 'https://services1.arcgis.com/3JjYDyG3oajxU6HO/ArcGIS/rest/services/DOC_Walking_Experiences/FeatureServer/0/query?where=1%3D1&outFields=name%2CwalkingAndTrampingWebPage&returnGeometry=false&f=json';
const out = new URL('../data/walk-facts.json', import.meta.url);
const guidOf = (link) => { const h = /\/link\/([0-9a-fA-F]{32})\./.exec(link || '')?.[1]?.toLowerCase(); return h && `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`; };
const ENT = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', ndash: '–', mdash: '—', rsquo: '’', lsquo: '‘', bull: '•', amacr: 'ā', emacr: 'ē', imacr: 'ī', omacr: 'ō', umacr: 'ū', Amacr: 'Ā', Emacr: 'Ē', Imacr: 'Ī', Omacr: 'Ō', Umacr: 'Ū', eacute: 'é', frac12: '½' };
const decode = (s) => s.replace(/&(#x?[0-9a-fA-F]+|\w+);/g, (m, e) => e[0] === '#' ? String.fromCodePoint(e[1] === 'x' ? parseInt(e.slice(2), 16) : +e.slice(1)) : ENT[e] ?? m);
const text = (h) => decode(h.replace(/<br\s*\/?>/gi, '\n').replace(/<[^>]+>/g, '')).split('\n').map((l) => l.replace(/\s+/g, ' ').trim()).join('\n').trim();
const category = (html, name) => new RegExp(`category-name="${name}"[\\s\\S]*?<template #description>([\\s\\S]*?)</template>`).exec(html)?.[1];
function overview(html) {
  const diff = category(html, 'Difficulty');
  const time = diff ? text(/<span>([\s\S]*?)<\/span>/.exec(diff)?.[1] || '') : '';
  const level = diff ? text(/<a\b[^>]*>([\s\S]*?)<\/a>/.exec(diff)?.[1] || '').split('\n').find((l) => l.trim())?.trim() || '' : '';
  const distance = text(category(html, 'DistanceType') || '');
  const dogs = text(category(html, 'DogsAllowed') || '').split(' Other pets')[0].trim();
  return { distance, time, level, dogs };
}
const facts = existsSync(out) ? JSON.parse(readFileSync(out, 'utf8')) : {};
const list = (await (await fetch(LIST)).json()).features.map((f) => f.attributes);
const todo = list.filter((a) => guidOf(a.walkingAndTrampingWebPage) && !facts[guidOf(a.walkingAndTrampingWebPage)]);
let done = 0, failed = 0;
async function one(a) {
  const guid = guidOf(a.walkingAndTrampingWebPage);
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const r = await fetch(a.walkingAndTrampingWebPage, { headers: { 'User-Agent': 'BushlineMapsBuild/1.0' } });
      if (!r.ok) throw new Error(r.status);
      const o = overview(await r.text());
      facts[guid] = [o.distance, o.time, o.level, o.dogs];
      return;
    } catch { await new Promise((r) => setTimeout(r, 1500 * (attempt + 1))); }
  }
  failed++;
}
const queue = todo.slice();
await Promise.all(Array.from({ length: 4 }, async () => { while (queue.length) { await one(queue.shift()); if (++done % 100 === 0) { console.log(done, '/', todo.length); writeFileSync(out, JSON.stringify(facts)); } } }));
writeFileSync(out, JSON.stringify(facts));
console.log(JSON.stringify({ walks: list.length, facts: Object.keys(facts).length, failed }));
