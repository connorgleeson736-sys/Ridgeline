// A DOC track page (doc.govt.nz, CC BY 4.0) as the app reads it (DocPages):
// its summary, sections ("Description", "Getting there"…) as headings,
// paragraphs, bullets and photos, and the legs DOC describes ("A to B" with
// a time or distance). Used at build time (pages) and in the browser (legs).

const ENT = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', ndash: '–', mdash: '—', rsquo: '’', lsquo: '‘', ldquo: '“', rdquo: '”', bull: '•', hellip: '…', deg: '°', amacr: 'ā', emacr: 'ē', imacr: 'ī', omacr: 'ō', umacr: 'ū', Amacr: 'Ā', Emacr: 'Ē', Imacr: 'Ī', Omacr: 'Ō', Umacr: 'Ū', eacute: 'é', frac12: '½', rarr: '→', times: '×' };
export const decode = (s) => s.replace(/&(#x?[0-9a-fA-F]+|\w+);/g, (m, e) => (e[0] === '#' ? String.fromCodePoint(e[1] === 'x' || e[1] === 'X' ? parseInt(e.slice(2), 16) : +e.slice(1)) : ENT[e] ?? m));
export const text = (h) => decode(h.replace(/<br\s*\/?>/gi, '\n').replace(/<[^>]+>/g, '')).split('\n').map((l) => l.replace(/[ \t\r\f ]+/g, ' ').trim()).join('\n').trim();
const category = (html, name) => new RegExp(`category-name="${name}"[\\s\\S]*?<template #description>([\\s\\S]*?)</template>`).exec(html)?.[1];
const meta = (html, p) => text(new RegExp(`<meta\\s+property="${p}"\\s+content="([^"]*)"`).exec(html)?.[1] || '');

export function overview(html) {
  const diff = category(html, 'Difficulty');
  const time = diff ? text(/<span>([\s\S]*?)<\/span>/.exec(diff)?.[1] || '') : '';
  const level = diff ? text(/<a\b[^>]*>([\s\S]*?)<\/a>/.exec(diff)?.[1] || '').split('\n').find((l) => l.trim())?.trim() || '' : '';
  const intro = text(/<doc-introduction-text\b[^>]*>([\s\S]*?)<\/doc-introduction-text>/.exec(html)?.[1] || '') || meta(html, 'og:description');
  return { title: meta(html, 'og:title'), intro, image: meta(html, 'og:image'), distance: text(category(html, 'DistanceType') || ''), time, level, dogs: text(category(html, 'DogsAllowed') || '').split(' Other pets')[0].trim() };
}

const SITE = 'https://www.doc.govt.nz/';
/** Sections: [title, blocks]; a block is ['h'|'p'|'b', text] or ['i', caption, url]. */
export function parse(html) {
  const out = [];
  for (const m of html.matchAll(/<doc-accordion-item\b[^>]*\bheader-text="([^"]*)"[^>]*>([\s\S]*?)<\/doc-accordion-item>/g)) {
    const title = text(m[1]), blocks = [], body = m[2];
    let texts = body.split(/<div class="block\s+/).filter((b) => b.startsWith('textblock'));
    if (!texts.length) texts = [body];
    for (const b of texts) for (const e of b.matchAll(/<(h[2-6]|p|li)\b[^>]*>([\s\S]*?)<\/\1>/gi)) {
      const img = /<img\b[^>]*?\bsrc\s*=\s*"?([^"\s>]+)"?[^>]*?(?:\bwidth\s*=\s*"?(\d+))?[^>]*>/i.exec(e[2]);
      if (img && (+img[2] || 400) >= 120) {
        const src = (/<a\b[^>]*?\bhref\s*=\s*"?([^"\s>]+\.(?:jpe?g|png|webp))"?/i.exec(e[2])?.[1] || img[1]).trim();
        const url = src.startsWith('/') ? SITE + src.slice(1) : src;
        const caption = text(e[2]).split('\n').map((l) => l.replace(/\s*See larger\s*\([^)]*\)\s*/i, '').trim()).filter(Boolean).join('\n');
        if (url.startsWith('https://')) blocks.push(['i', caption, url]);
        continue;
      }
      const value = text(e[2]);
      if (!value) continue;
      const tag = e[1].toLowerCase();
      if (tag === 'p') {
        if (value.split('\n').some((l) => l.trimStart().startsWith('•'))) for (const line of value.split('\n').filter((l) => l.trim())) { const t = line.trim(); blocks.push(t.startsWith('•') ? ['b', t.slice(1).trim()] : ['p', t]); }
        else blocks.push(['p', value]);
      } else if (tag === 'li') blocks.push(['b', value]);
      else blocks.push(['h', value]);
    }
    if (title && blocks.length) out.push([title, blocks]);
  }
  return out;
}

const notLeg = /^(side trip|campsites?|walking options|places to stay|understand|highlights|what to expect|description|getting there|from )/i;
const dayPrefix = /^(day|stage|section|part|leg)\s*[0-9]+\s*[:–—-]?\s*/i;
/** DOC's legs in its description: {title, from, to, stops, time, km, blocks}. */
export function legs(sections) {
  const d = sections.find(([t]) => /description/i.test(t));
  if (!d) return [];
  const b = d[1], out = [];
  for (let i = 0; i < b.length;) {
    const h = b[i++];
    if (h[0] !== 'h' || notLeg.test(h[1])) continue;
    const heading = h[1].replace(dayPrefix, '');
    const stops = (/\s+to\s+/i.test(heading) ? heading.split(/\s+to\s+/i) : heading.split(/\s+[-–—]\s+/)).map((s) => s.trim()).filter(Boolean);
    if (stops.length < 2) continue;
    const body = [];
    while (i < b.length && b[i][0] !== 'h') body.push(b[i++]);
    const facts = body.filter((x) => x[0] !== 'i').slice(0, 3).map((x) => x[1]).join('\n');
    const time = /Time:\s*([^\n]+?)\s*(?=Distance:|\n|$)/i.exec(facts)?.[1]?.trim() || '';
    const dist = /Distance:\s*([^\n]+)/i.exec(facts)?.[1] ?? time;
    const kms = [...dist.matchAll(/([0-9]+(?:[.,][0-9]+)?)\s*km/gi)];
    const km = kms.length ? +kms[kms.length - 1][1].replace(',', '.') : null;
    if (!time && km == null) continue;
    out.push({ title: heading, from: stops[0], to: stops[stops.length - 1], stops, time, km, blocks: body.filter((x) => !((/^(Time|Distance):/i.test(x[1])) && x[1].length < 160)) });
  }
  return out;
}
