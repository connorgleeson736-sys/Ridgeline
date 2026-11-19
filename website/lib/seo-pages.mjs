// Static pages for search engines and sharing: one per DOC walk, hut and Te Araroa
// section, and an index of each. Built from data already in the repository (DOC's
// open data in data/seo-data.json.gz, DOC's walk pages, the Te Araroa data) at
// build time, in our own words with DOC's and the Trust's text quoted and credited.
// Each page links into the 3D map (/#w=, /#h=, /#ta=).
//
// buildSeoPages({ here, write }) returns [{ rel, html }] (html uses the site's partials).

import { readFileSync, existsSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { join } from 'node:path';

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const slugify = (s) => String(s).normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/&/g, ' and ').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'place';
const strip = (html) => String(html || '').replace(/<br\s*\/?>/gi, ' ').replace(/<\/(p|li|h\d)>/gi, '\n').replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&#39;|&rsquo;/g, '’').replace(/&quot;/g, '"').replace(/&[a-z]+;/g, ' ').replace(/[ \t]+/g, ' ').replace(/\n\s*\n+/g, '\n').trim();
const an = (w) => (/^[aeiou]/i.test(w) ? 'an' : 'a');
const list = (xs) => (xs.length <= 1 ? xs.join('') : `${xs.slice(0, -1).join(', ')} and ${xs[xs.length - 1]}`);
/** "the Routeburn Track" in a sentence; names like "Lake Waikaremoana Great Walk" too. */
const the = (name, cap = false) => (/^the\b/i.test(name) || !/(track|walk|circuit|route|trail|loop|way|walkway|crossing|pass)$/i.test(name) ? name : `${cap ? 'The' : 'the'} ${name}`);
const km = (m) => (m >= 10000 ? `${Math.round(m / 1000)} km` : `${(m / 1000).toFixed(1)} km`);

// ---- Geometry (lon/lat; metres on a local flat projection) ----
const R = 6371008.8, rad = Math.PI / 180;
function metres([ax, ay], [bx, by]) { const x = (bx - ax) * rad * Math.cos(((ay + by) / 2) * rad), y = (by - ay) * rad; return Math.hypot(x, y) * R; }
function toSegment(p, a, b) {
  const k = Math.cos(p[1] * rad) * rad * R, j = rad * R;
  const ax = (a[0] - p[0]) * k, ay = (a[1] - p[1]) * j, bx = (b[0] - p[0]) * k, by = (b[1] - p[1]) * j;
  const dx = bx - ax, dy = by - ay, l = dx * dx + dy * dy;
  const t = l ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l)) : 0;
  return Math.hypot(ax + t * dx, ay + t * dy);
}
function toLines(p, lines) { let best = Infinity; for (const l of lines) for (let i = 1; i < l.length; i++) best = Math.min(best, toSegment(p, l[i - 1], l[i])); return best; }
function bbox(lines) { const b = [180, 90, -180, -90]; for (const l of lines) for (const [x, y] of l) { b[0] = Math.min(b[0], x); b[1] = Math.min(b[1], y); b[2] = Math.max(b[2], x); b[3] = Math.max(b[3], y); } return b; }
const length = (lines) => lines.reduce((s, l) => s + l.slice(1).reduce((t, p, i) => t + metres(l[i], p), 0), 0);
const mercY = (lat) => Math.log(Math.tan(Math.PI / 4 + (lat * rad) / 2));

/** A small line drawing: the lines in mercator, huts as dots, start as a ring. */
function lineSvg(lines, { dots = [], start = null, label = '' } = {}) {
  if (!lines.length) return '';
  const b = bbox(lines.concat(dots.map((d) => [d.point]), start ? [[start]] : []));
  const x0 = b[0], x1 = b[2], y0 = mercY(b[3]), y1 = mercY(b[1]);
  const span = Math.max((x1 - x0) * rad, y0 - y1, 1e-6);
  const W = 640, H = 400, pad = 28, s = Math.min((W - 2 * pad) / Math.max((x1 - x0) * rad, 1e-9), (H - 2 * pad) / Math.max(y0 - y1, 1e-9), (W - 2 * pad) / span * 4);
  const ox = (W - (x1 - x0) * rad * s) / 2, oy = (H - (y0 - y1) * s) / 2;
  const P = ([x, y]) => [ox + (x - x0) * rad * s, oy + (y0 - mercY(y)) * s].map((v) => Math.round(v * 10) / 10);
  const d = lines.map((l) => 'M' + l.map((p) => P(p).join(' ')).join('L')).join('');
  const hut = dots.map((h) => { const [x, y] = P(h.point); return `<g class="hut-dot"><circle cx="${x}" cy="${y}" r="5.5"/><title>${esc(h.name)}</title></g>`; }).join('');
  const st = start ? (() => { const [x, y] = P(start); return `<circle class="start" cx="${x}" cy="${y}" r="6"/>`; })() : '';
  return `<svg class="line-svg" viewBox="0 0 ${W} ${H}" role="img" aria-label="${esc(label)}"><path d="${d}"/>${st}${hut}</svg>`;
}

function page({ path, title, description, h1, crumbs, body, jsonld }) {
  return `<!doctype html>
<html lang="en-NZ">
<head>
{{> head}}
<title>${esc(title)}</title>
<meta name="description" content="${esc(description)}">
<link rel="canonical" href="{{site.domain}}${path}">
<meta property="og:title" content="${esc(h1)}">
<meta property="og:description" content="${esc(description)}">
<meta property="og:url" content="{{site.domain}}${path}">
${jsonld ? `<script type="application/ld+json">${JSON.stringify(jsonld).replace(/</g, '\\u003c').replace(/\{\{/g, '{\\u007b')}</script>` : ''}
</head>
<body>
<a class="skip" href="#main">Skip to content</a>
{{> header}}
<main id="main" class="page place-page"><div class="narrow">
${crumbs ? `<nav class="crumbs" aria-label="Breadcrumb">${crumbs}</nav>` : ''}
<h1>${esc(h1)}</h1>
${body}
</div></main>
{{> footer}}
</body>
</html>
`;
}
const facts = (rows) => `<dl class="facts">${rows.filter(([, v]) => v).map(([k, v]) => `<div><dt>${esc(k)}</dt><dd>${v}</dd></div>`).join('')}</dl>`;
const crumbList = (items) => ({ '@type': 'BreadcrumbList', itemListElement: items.map(([name, url], i) => ({ '@type': 'ListItem', position: i + 1, name, item: url })) });
const linkList = (items) => `<ul class="link-list">${items.join('')}</ul>`;

export function buildSeoPages({ here, appAssets, domain }) {
  const seoFile = join(here, 'data/seo-data.json.gz');
  if (!existsSync(seoFile)) return [];
  const seo = JSON.parse(gunzipSync(readFileSync(seoFile)));
  const docPages = existsSync(join(here, 'data/walk-pages.json.gz')) ? JSON.parse(gunzipSync(readFileSync(join(here, 'data/walk-pages.json.gz')))) : {};
  const facts0 = existsSync(join(here, 'data/walk-facts.json')) ? JSON.parse(readFileSync(join(here, 'data/walk-facts.json'), 'utf8')) : {};
  const compiled = JSON.parse(readFileSync(join(appAssets, 'walks/walks.json'), 'utf8')).walks;
  const ta = existsSync(join(here, 'data/te-araroa.json')) ? JSON.parse(readFileSync(join(here, 'data/te-araroa.json'), 'utf8')) : null;
  const taNotes = existsSync(join(here, 'data/te-araroa-sections.json.gz')) ? JSON.parse(gunzipSync(readFileSync(join(here, 'data/te-araroa-sections.json.gz')))) : {};

  // ---- Huts ----
  // DOC lists some huts twice: one page per hut (same name within 300 m), keeping the
  // record with the most detail.
  const huts = [];
  for (const h of seo.huts.filter((x) => x.name && x.point).sort((a, b) => (b.place ? 1 : 0) + (b.region ? 1 : 0) + (b.link ? 1 : 0) - ((a.place ? 1 : 0) + (a.region ? 1 : 0) + (a.link ? 1 : 0)))) {
    if (huts.some((x) => x.name.toLowerCase() === h.name.toLowerCase() && metres(x.point, h.point) < 300)) continue;
    huts.push({ ...h, slug: slugify(h.name) });
  }
  const bySlug = new Map();
  for (const h of huts) (bySlug.get(h.slug) || bySlug.set(h.slug, []).get(h.slug)).push(h);
  for (const [s, group] of bySlug) if (group.length > 1) {
    const seen = new Set();
    for (const h of group) { let t = `${s}-${slugify(h.place || h.region || 'hut')}`; let n = 2; while (seen.has(t)) t = `${s}-${n++}`; seen.add(t); h.slug = t; }
  }
  const hutUrl = (h) => `/huts/${h.slug}/`;

  // ---- Walks ----
  const walks = [];
  for (const [guid, w] of Object.entries(seo.walks)) {
    const o = docPages[guid]?.o || {}, f = facts0[guid] || [];
    const name = o.title || w.name; if (!name) continue;
    const lines = w.lines.filter((l) => l.length > 1);
    const way = (compiled[guid]?.ways || []).find((x) => x.main) || (compiled[guid]?.ways || [])[0];
    walks.push({ guid, name, slug: slugify(name), lines, point: w.point || lines[0]?.[0] || null, level: o.level || f[2] || w.difficulty, time: o.time || f[1] || w.time, distance: o.distance || f[0] || way?.distance || '', shape: compiled[guid]?.kind || way?.shape || '', dogs: o.dogs || f[3] || '', intro: o.intro || '', image: (o.image && o.image !== 'https://www.doc.govt.nz') ? o.image : w.thumb });
  }
  const walkSeen = new Map();
  for (const w of walks.sort((a, b) => a.name.localeCompare(b.name))) { const n = walkSeen.get(w.slug) || 0; walkSeen.set(w.slug, n + 1); if (n) w.slug = `${w.slug}-${n + 1}`; }
  const walkUrl = (w) => `/walks/${w.slug}/`;

  // Huts along each walk (within 250 m of its line), and walks at each hut.
  for (const h of huts) h.walks = [];
  for (const w of walks) {
    w.huts = [];
    if (!w.lines.length) continue;
    const b = bbox(w.lines), m = 0.005;
    for (const h of huts) {
      const [x, y] = h.point;
      if (x < b[0] - m || x > b[2] + m || y < b[1] - m || y > b[3] + m) continue;
      if (toLines(h.point, w.lines) <= 250) { w.huts.push(h); h.walks.push(w); }
    }
    w.length = length(w.lines);
  }
  // Regions: DOC's region of the nearest hut (huts carry it; walks do not).
  const regionAt = (p) => { let best = null, d = 60000; if (!p) return ''; for (const h of huts) { if (!h.region) continue; const e = metres(p, h.point); if (e < d) { d = e; best = h.region; } } return best || ''; };
  for (const w of walks) w.region = w.huts.find((h) => h.region)?.region || regionAt(w.point);

  // ---- Te Araroa ----
  const sections = ta?.sections || [];
  const sectionBySlug = new Map(sections.map((s) => [s.slug, s]));
  const taUrl = (s) => `/te-araroa/${s.slug}/`;
  const taLines = new Map();
  const walkSections = new Map();
  const walkByName = new Map(walks.map((w) => [w.name.toLowerCase(), w]));
  for (const p of ta?.pieces || []) {
    const [name, , , , slug, c] = p;
    if (Array.isArray(c) && c.length >= 4) {
      const line = []; let x = 0, y = 0;
      for (let i = 0; i < c.length; i += 2) { x += c[i]; y += c[i + 1]; line.push([x / 1e5, y / 1e5]); }
      (taLines.get(slug) || taLines.set(slug, []).get(slug)).push(line);
    }
    const w = walkByName.get(String(name).toLowerCase());
    if (w && sectionBySlug.has(slug)) (walkSections.get(w.guid) || walkSections.set(w.guid, new Set()).get(w.guid)).add(slug);
  }
  const hutPlaces = new Map();
  for (const pl of ta?.places || []) if (/hut/i.test(pl.type || '') && pl.point) hutPlaces.set(`${pl.name.toLowerCase()}|${pl.point[0].toFixed(2)}|${pl.point[1].toFixed(2)}`, pl);
  for (const h of huts) {
    const pl = hutPlaces.get(`${h.name.toLowerCase()}|${h.point[0].toFixed(2)}|${h.point[1].toFixed(2)}`);
    if (pl && sectionBySlug.has(pl.section)) h.ta = { section: sectionBySlug.get(pl.section), km: pl.km };
  }

  const out = [];
  const D = domain;

  // ---- Walk pages ----
  for (const w of walks) {
    const near = walks.filter((v) => v !== w && v.point && w.point).map((v) => [v, metres(w.point, v.point)]).filter(([, d]) => d < 25000).sort((a, b) => a[1] - b[1]).slice(0, 6);
    const secs = [...(walkSections.get(w.guid) || [])].map((s) => sectionBySlug.get(s));
    const shapeText = { loop: 'loop', circuit: 'circuit', return: 'return trip', 'one-way': 'one-way walk', oneway: 'one-way walk', 'out-and-back': 'return trip' }[w.shape] || '';
    const level = (w.level || '').replace(/^(Easiest|Easy|Intermediate|Advanced|Expert):\s*/i, '');
    let about = `${esc(the(w.name, true))} is ${level ? `${an(level)} ${esc(level.toLowerCase())}` : 'a DOC walk'}${w.region ? ` in ${esc(w.region)}` : ''}.`;
    const parts = [w.distance && esc(w.distance), shapeText && `${an(shapeText)} ${shapeText}`].filter(Boolean);
    if (parts.length || w.time) about += parts.length ? ` It is ${parts.join(', ')}${w.time ? ` and takes about ${esc(w.time)}` : ''}.` : ` It takes about ${esc(w.time)}.`;
    if (w.huts.length) about += ` Huts along the way: ${list(w.huts.map((h) => `<a href="${hutUrl(h)}">${esc(h.name)}</a>`))}.`;
    if (secs.length) about += ` It is part of Te Araroa (${list(secs.map((s) => `<a href="${taUrl(s)}">${esc(s.name)}</a>`))}).`;
    const desc = `${w.name}: ${[w.distance, w.time, level].filter(Boolean).join(', ')}${w.region ? `, ${w.region}` : ''}. Map in 3D, huts along the way and the elevation profile.`;
    const body = `
<p class="lead">${about}</p>
${facts([['Distance', esc(w.distance) || (w.length ? `${km(w.length)} of mapped track` : '')], ['Time', esc(w.time)], ['Grade', esc(level)], ['Type', shapeText], ['Region', esc(w.region)], ['Dogs', esc(w.dogs)]])}
<p class="actions"><a class="button" href="/#w=${w.guid}">Open in the 3D map</a> <span class="muted">Elevation profile, huts and DOC alerts</span></p>
${w.lines.length ? `<figure class="line-map">${lineSvg(w.lines, { dots: w.huts, start: w.lines[0][0], label: `Map of the ${w.name}` })}<figcaption>The track as DOC maps it${w.huts.length ? '; huts marked' : ''}. North is up.</figcaption></figure>` : ''}
${w.huts.length ? `<h2>Huts along the way</h2>${linkList(w.huts.map((h) => `<li><a href="${hutUrl(h)}">${esc(h.name)}</a>${h.bookable ? ' <span class="tag">Bookable</span>' : ''}</li>`))}` : ''}
${w.intro ? `<h2>About the walk</h2><blockquote>${esc(w.intro)}</blockquote><p class="fine">From DOC's page for this walk (CC BY 4.0). <a href="https://www.doc.govt.nz/link/${w.guid.replace(/-/g, '')}.aspx" rel="noopener">Full description, alerts and conditions on DOC ↗</a></p>` : `<p class="fine"><a href="https://www.doc.govt.nz/link/${w.guid.replace(/-/g, '')}.aspx" rel="noopener">DOC's page for this walk ↗</a></p>`}
${secs.length ? `<h2>On Te Araroa</h2>${linkList(secs.map((s) => `<li><a href="${taUrl(s)}">Section ${s.n}: ${esc(s.name)}</a></li>`))}` : ''}
${near.length ? `<h2>Walks nearby</h2>${linkList(near.map(([v, d]) => `<li><a href="${walkUrl(v)}">${esc(v.name)}</a> <span class="muted">${km(d)} away</span></li>`))}` : ''}
<p class="fine">Check DOC for alerts and conditions before you go, and carry a map, compass and personal locator beacon.</p>`;
    out.push({ rel: `walks/${w.slug}/index.html`, html: page({
      path: walkUrl(w), title: `${w.name} – map, huts and distance | Razorback Maps`, description: desc, h1: w.name,
      crumbs: `<a href="/walks/">Walks</a>${w.region ? ` › <a href="/walks/#${slugify(w.region)}">${esc(w.region)}</a>` : ''}`,
      jsonld: { '@context': 'https://schema.org', '@graph': [
        { '@type': 'TouristAttraction', name: w.name, url: `${D}${walkUrl(w)}`, description: strip(about), ...(w.point ? { geo: { '@type': 'GeoCoordinates', latitude: w.point[1], longitude: w.point[0] } } : {}), ...(w.image ? { image: w.image } : {}), isAccessibleForFree: true, touristType: 'Trampers' },
        crumbList([['Walks', `${D}/walks/`], [w.name, `${D}${walkUrl(w)}`]])] },
      body }) });
  }

  // ---- Hut pages ----
  for (const h of huts) {
    const near = huts.filter((v) => v !== h).map((v) => [v, metres(h.point, v.point)]).filter(([, d]) => d < 20000).sort((a, b) => a[1] - b[1]).slice(0, 6);
    const where = [h.place, h.region].filter(Boolean).filter((v, i, a) => a.indexOf(v) === i).join(', ');
    let about = `${esc(h.name)} is a DOC hut${where ? ` in ${esc(where)}` : ''}${h.bookable ? ' and must be booked' : ''}.`;
    if (h.walks.length) about += ` It is on ${list(h.walks.map((w) => `<a href="${walkUrl(w)}">${esc(the(w.name))}</a>`))}.`;
    if (h.ta) about += ` Te Araroa passes it at km ${Math.round(h.ta.km)}, in <a href="${taUrl(h.ta.section)}">${esc(h.ta.section.name)}</a>.`;
    const desc = `${h.name}${where ? `, ${where}` : ''}: ${h.bookable ? 'bookable DOC hut' : 'DOC hut'}${h.walks.length ? ` on ${the(h.walks[0].name)}` : ''}. Location, facilities, walks to it and huts nearby, in 3D.`;
    const lines = h.walks.flatMap((w) => w.lines);
    const body = `
<p class="lead">${about}</p>
${facts([['Region', esc(h.region)], ['Place', esc(h.place)], ['Booking', h.bookable ? 'Bookable – book with DOC' : 'Not bookable (first come, first served)'], ['Facilities', esc(h.facilities)], ['Location', `${h.point[1].toFixed(5)}, ${h.point[0].toFixed(5)}`]])}
<p class="actions"><a class="button" href="/#h=${h.point[1]},${h.point[0]},${encodeURIComponent(h.name)}">Open in the 3D map</a></p>
${lines.length ? `<figure class="line-map">${lineSvg(lines, { dots: [h], label: `Tracks to ${h.name}` })}<figcaption>${esc(h.name)} and the tracks to it. North is up.</figcaption></figure>` : ''}
${h.walks.length ? `<h2>Walks to the hut</h2>${linkList(h.walks.map((w) => `<li><a href="${walkUrl(w)}">${esc(w.name)}</a>${w.time ? ` <span class="muted">${esc(w.time)}</span>` : ''}</li>`))}` : ''}
${near.length ? `<h2>Huts nearby</h2>${linkList(near.map(([v, d]) => `<li><a href="${hutUrl(v)}">${esc(v.name)}</a> <span class="muted">${km(d)} away</span></li>`))}` : ''}
<p class="fine">From DOC's hut data (CC BY 4.0).${h.link ? ` <a href="${esc(h.link)}" rel="noopener">DOC's page for this hut, with bookings and alerts ↗</a>` : ''}</p>`;
    out.push({ rel: `huts/${h.slug}/index.html`, html: page({
      path: hutUrl(h), title: `${h.name}${h.region ? `, ${h.region}` : ''} – hut, map and walks | Razorback Maps`, description: desc, h1: h.name,
      crumbs: `<a href="/huts/">Huts</a>${h.region ? ` › <a href="/huts/#${slugify(h.region)}">${esc(h.region)}</a>` : ''}`,
      jsonld: { '@context': 'https://schema.org', '@graph': [
        { '@type': 'Place', name: h.name, url: `${D}${hutUrl(h)}`, description: strip(about), geo: { '@type': 'GeoCoordinates', latitude: h.point[1], longitude: h.point[0] }, ...(h.thumb ? { image: h.thumb } : {}) },
        crumbList([['Huts', `${D}/huts/`], [h.name, `${D}${hutUrl(h)}`]])] },
      body }) });
  }

  // ---- Te Araroa section pages ----
  const main = sections.filter((s) => !s.alternative).sort((a, b) => a.from - b.from);
  for (const s of sections) {
    const notes = taNotes[s.slug] || {};
    const i = main.indexOf(s), prev = i > 0 ? main[i - 1] : null, next = i >= 0 && i < main.length - 1 ? main[i + 1] : null;
    const places = (ta.places || []).filter((p) => p.section === s.slug).sort((a, b) => a.km - b.km);
    const stops = (ta.resupply || []).filter((r) => r.km >= s.from - 0.5 && r.km <= s.to + 0.5 && !s.alternative);
    const dws = walks.filter((w) => walkSections.get(w.guid)?.has(s.slug));
    const intro = strip(notes.intro).replace(/^Te Araroa section:\s*/i, '');
    const island = s.island === 'NI' ? 'North Island' : s.island === 'SI' ? 'South Island' : '';
    let about = `${s.alternative ? 'An alternative to the main trail: ' : `Section ${s.n} of Te Araroa, `}${esc(s.name)} is ${s.length ? `${s.length} km` : 'a stretch'} in ${esc(s.region || island)}${s.time ? `, taking ${esc(s.time)}` : ''}.`;
    if (!s.alternative && s.from != null) about += ` Southbound it runs from km ${Math.round(s.from)} to km ${Math.round(s.to)} of the ${Math.round(ta.total)} km trail.`;
    const hutLink = (p) => { const h = huts.find((x) => x.name.toLowerCase() === p.name.toLowerCase() && metres(x.point, p.point) < 1000); return h ? `<a href="${hutUrl(h)}">${esc(p.name)}</a>` : esc(p.name); };
    const desc = `Te Araroa ${s.alternative ? 'alternative' : `section ${s.n}`}: ${s.name}, ${s.length} km${s.region ? `, ${s.region}` : ''}. Map in 3D, huts and campsites with trail km, food stops and the elevation profile.`;
    const lines = taLines.get(s.slug) || [];
    const body = `
<p class="lead">${about}</p>
${facts([['Length', s.length ? `${s.length} km` : ''], ['Time', esc(s.time)], ['Region', esc(s.region)], ['Island', island], ['Trail km (southbound)', !s.alternative && s.from != null ? `${Math.round(s.from)}–${Math.round(s.to)}` : '']])}
<p class="actions"><a class="button" href="/#ta=${s.slug}">Open in the 3D map</a> <span class="muted">Profile, live trail alerts and food stops</span></p>
${lines.length ? `<figure class="line-map">${lineSvg(lines, { label: `Map of ${s.name}` })}<figcaption>The trail through this section. North is up.</figcaption></figure>` : ''}
${intro ? `<h2>About the section</h2><blockquote>${esc(intro).replace(/\n/g, '</p><p>')}</blockquote><p class="fine">From the Te Araroa Trust's notes (CC BY 4.0).</p>` : ''}
${places.length ? `<h2>Huts and places to stay</h2>${linkList(places.map((p) => `<li>${hutLink(p)} <span class="muted">${esc(p.type || '')}${p.km != null ? ` · km ${Math.round(p.km)}` : ''}</span></li>`))}` : ''}
${stops.length ? `<h2>Food and resupply</h2>${linkList(stops.map((r) => `<li>${esc(r.name)} <span class="muted">km ${Math.round(r.km)}${r.bounce ? ' · bounce box' : ''}</span>${r.note ? `<br><span class="note-line">${esc(r.note)}</span>` : ''}</li>`))}` : ''}
${dws.length ? `<h2>DOC walks on this section</h2>${linkList(dws.map((w) => `<li><a href="${walkUrl(w)}">${esc(w.name)}</a></li>`))}` : ''}
${prev || next ? `<nav class="prev-next">${prev ? `<a href="${taUrl(prev)}">← ${esc(prev.name)}</a>` : '<span></span>'}${next ? `<a href="${taUrl(next)}">${esc(next.name)} →</a>` : ''}</nav>` : ''}
<p class="fine">Check the Te Araroa Trust's trail status and alerts before you go.</p>`;
    out.push({ rel: `te-araroa/${s.slug}/index.html`, html: page({
      path: taUrl(s), title: `Te Araroa: ${s.name} – map, huts and food | Razorback Maps`, description: desc, h1: s.name,
      crumbs: `<a href="/te-araroa/">Te Araroa</a> › ${esc(island)}`,
      jsonld: { '@context': 'https://schema.org', '@graph': [
        { '@type': 'TouristAttraction', name: `Te Araroa: ${s.name}`, url: `${D}${taUrl(s)}`, description: strip(about), isAccessibleForFree: true, touristType: 'Trampers' },
        crumbList([['Te Araroa', `${D}/te-araroa/`], [s.name, `${D}${taUrl(s)}`]])] },
      body }) });
  }

  // ---- Indexes ----
  const grouped = (items, key) => { const m = new Map(); for (const x of items) { const k = key(x) || 'Other'; (m.get(k) || m.set(k, []).get(k)).push(x); } return [...m].sort((a, b) => (a[0] === 'Other') - (b[0] === 'Other') || a[0].localeCompare(b[0])); };
  const regionNav = (groups) => `<nav class="region-nav">${groups.map(([r]) => `<a href="#${slugify(r)}">${esc(r)}</a>`).join('')}</nav>`;
  const wg = grouped(walks, (w) => w.region);
  out.push({ rel: 'walks/index.html', html: page({ path: '/walks/', title: 'DOC walks and tramping tracks in New Zealand – maps | Razorback Maps', h1: 'Walks and tramping tracks',
    description: `All ${walks.length} DOC walks and tramping tracks in Aotearoa New Zealand by region, each with its distance, time, huts along the way and a 3D map.`,
    body: `<p class="lead">Every walk and tramping track on DOC's website – ${walks.length} of them – by region. Each has its distance, time and grade, the huts along it, and opens in the 3D map with an elevation profile.</p>${regionNav(wg)}${wg.map(([r, ws]) => `<h2 id="${slugify(r)}">${esc(r)}</h2>${linkList(ws.sort((a, b) => a.name.localeCompare(b.name)).map((w) => `<li><a href="${walkUrl(w)}">${esc(w.name)}</a>${w.time ? ` <span class="muted">${esc(w.time)}</span>` : ''}</li>`))}`).join('')}` }) });
  const hg = grouped(huts, (h) => h.region);
  out.push({ rel: 'huts/index.html', html: page({ path: '/huts/', title: 'DOC huts in New Zealand – maps and walks | Razorback Maps', h1: 'Huts',
    description: `All ${huts.length} DOC huts in Aotearoa New Zealand by region: location, booking, facilities, the walks to each and a 3D map.`,
    body: `<p class="lead">DOC's ${huts.length} huts by region, with booking, facilities, the walks that reach them and huts nearby.</p>${regionNav(hg)}${hg.map(([r, hs]) => `<h2 id="${slugify(r)}">${esc(r)}</h2>${linkList(hs.sort((a, b) => a.name.localeCompare(b.name)).map((h) => `<li><a href="${hutUrl(h)}">${esc(h.name)}</a>${h.bookable ? ' <span class="tag">Bookable</span>' : ''}</li>`))}`).join('')}` }) });
  if (sections.length) {
    const byIsland = (isl) => main.filter((s) => s.island === isl);
    const row = (s) => `<li><a href="${taUrl(s)}">${s.alternative ? '' : `${s.n}. `}${esc(s.name)}</a> <span class="muted">${s.length} km${s.region ? ` · ${esc(s.region)}` : ''}</span></li>`;
    out.push({ rel: 'te-araroa/index.html', html: page({ path: '/te-araroa/', title: 'Te Araroa trail sections – maps, huts and food | Razorback Maps', h1: 'Te Araroa, section by section',
      description: `All ${main.length} sections of Te Araroa, the ${Math.round(ta.total)} km trail from Cape Reinga to Bluff: maps, huts and campsites with trail km, food stops and profiles.`,
      body: `<p class="lead">Te Araroa runs ${Math.round(ta.total)} km from Cape Reinga to Bluff. Each section has its huts and places to stay with their trail km, the food stops along it, and opens in the 3D map with its profile and the Trust's live alerts.</p>
<h2 id="north-island">North Island</h2>${linkList(byIsland('NI').map(row))}
<h2 id="south-island">South Island</h2>${linkList(byIsland('SI').map(row))}
${sections.some((s) => s.alternative) ? `<h2 id="alternatives">Alternatives and bypasses</h2>${linkList(sections.filter((s) => s.alternative).map(row))}` : ''}` }) });
  }
  // Links between these pages open the place in the 3D map when clicked (js/site.js
  // follows data-map); the href stays the page's, so search engines follow it and a
  // new tab still opens the page.
  const mapOf = new Map();
  for (const w of walks) mapOf.set(walkUrl(w), `/#w=${w.guid}`);
  for (const h of huts) mapOf.set(hutUrl(h), `/#h=${h.point[1]},${h.point[0]},${encodeURIComponent(h.name)}`);
  for (const s of sections) mapOf.set(taUrl(s), `/#ta=${s.slug}`);
  for (const o of out) o.html = o.html.replace(/<a href="(\/(?:walks|huts|te-araroa)\/[^"\/]+\/)"/g, (m, url) => (mapOf.has(url) ? `<a href="${url}" data-map="${esc(mapOf.get(url))}"` : m))
    .replace('</main>', '</main>\n<script type="module" src="/js/site.js"></script>');
  return out;
}
