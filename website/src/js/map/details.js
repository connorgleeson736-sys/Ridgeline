// What a tap opens: a DOC walk's summary, a track, a hut or hunting areas.
// The walk summary follows the app's: DOC's own line walked as DOC describes it
// (DocRoute), its elevation profile and the huts along it.
import { el, icon, distance, height, escape } from './ui.js';
import { Chart, stats } from './chart.js';
import { route as docRoute, stated } from '../core/docroute.js';
import { stops, matchHut, guidOf } from '../core/walks.js';
import { length, bounds, chain, nearestOn } from '../core/geo.js';
import { legs } from '../core/docpages.js';
import * as doc from './doc.js';
import * as teAraroa from './te-araroa.js';
import { data as worker, search } from './workers.js';

let facts = null;
const walkFacts = () => facts || (facts = fetch('/data/walks.json').then((r) => (r.ok ? r.json() : {})).catch(() => ({})));

const DOC_NOTE = 'Track information from the Department of Conservation. Check conditions with DOC before you go.';
/** DOC writes bunks as "8 each", "20", "4 (bunks)"… */
const bunks = (b) => { const n = /^\d+/.exec(b || '')?.[0]; return n ? `${n} bunks` : ''; };
const statBox = (label, value) => el('div', { class: 'stat' }, el('span', {}, label), el('b', {}, value));

// ---- DOC's current alerts ----
const KEEP = new Set(['P', 'UL', 'OL', 'LI', 'STRONG', 'B', 'EM', 'I', 'BR', 'A']);
const DROP = new Set(['SCRIPT', 'STYLE', 'IFRAME', 'OBJECT', 'EMBED', 'IMG', 'SVG', 'VIDEO', 'AUDIO', 'FORM', 'INPUT', 'BUTTON', 'TEMPLATE', 'NOSCRIPT']);
/** DOC's alert text (HTML) as plain paragraphs, lists and links only: nothing else
 * of the markup is carried over (no attributes but a checked link). */
function safeHtml(html) {
  const doc = new DOMParser().parseFromString(`<body>${html || ''}</body>`, 'text/html');
  const out = document.createDocumentFragment();
  const copy = (from, to) => {
    for (const node of from.childNodes) {
      if (node.nodeType === Node.TEXT_NODE) { to.append(node.textContent.replace(/\u00a0/g, ' ')); continue; }
      if (node.nodeType !== Node.ELEMENT_NODE || DROP.has(node.tagName)) continue;
      const tag = /^H[1-6]$/.test(node.tagName) ? 'P' : node.tagName;
      if (!KEEP.has(tag)) { copy(node, to); continue; }
      let made;
      if (tag === 'A') {
        let href = node.getAttribute('href') || '';
        if (href.startsWith('/')) href = `https://www.doc.govt.nz${href}`;
        if (!/^(https:|mailto:|tel:)/i.test(href)) { copy(node, to); continue; }
        made = el('a', { href, target: '_blank', rel: 'noopener' });
      } else made = document.createElement(tag.toLowerCase());
      copy(node, made);
      to.append(made);
    }
  };
  copy(doc.body, out);
  return out;
}

/** DOC's current alerts for a page id, filled in when they arrive; nothing shows
 * when there are none. If they cannot be read, a pointer to DOC's alerts. */
function alertsBox(guid, page) {
  const box = el('section', { class: 'doc-alerts', hidden: true, 'aria-label': 'DOC alerts' });
  if (!guid) return box;
  doc.alerts(guid).then((list) => {
    if (list && !list.length) return;
    box.hidden = false;
    if (!list) { box.append(el('p', { class: 'doc-alerts-head' }, 'Couldn’t load DOC’s current alerts. ', docLink(page || 'https://www.doc.govt.nz/parks-and-recreation/know-before-you-go/alerts/', 'Check them on DOC ↗'))); return; }
    box.append(el('p', { class: 'doc-alerts-head' }, list.length === 1 ? 'DOC alert' : `${list.length} DOC alerts`));
    list.forEach((a, i) => {
      const item = el('details', { class: 'doc-alert' }, el('summary', {}, a.summary || 'Alert'),
        el('div', { class: 'doc-alert-body' }, safeHtml(a.description), (a.note || a.date) ? el('p', { class: 'muted' }, a.note || `Published ${a.date}.`) : null));
      if (i === 0 && list.length === 1) item.open = true;
      box.append(item);
    });
  });
  return box;
}

/** Stats and the app's elevation chart for a line, filled in as heights arrive.
 * Inspecting marks the point on the map; a section shows A and B there and
 * dims the rest of the line. node.chart.setMarkers([[distance, name]]) adds huts. */
export function profileBlock(ctx, line, alive, { onReady } = {}) {
  const grid = el('div', { class: 'stats' }, statBox('Distance', distance(length(line))), statBox('Ascent', '…'), statBox('Descent', '…'), statBox('Highest', '…'));
  const canvas = el('canvas', { class: 'chart' });
  const chart = new Chart(canvas, chartEvents(ctx, () => chart));
  worker.call({ type: 'profile', points: line, max: 700 }).then((samples) => {
    if (!alive() || !samples) return;
    chart.set(samples, chart.markers);
    const s = stats(samples);
    whole = [length(line), s.ascent, s.descent, s.high];
    node.setStats();
    onReady?.(samples, s);
  }).catch(() => { if (alive()) chart.set([{ d: 0, h: null, p: line[0] }]); });
  const node = el('div', { class: 'profile' }, grid, el('div', { class: 'chart-wrap' }, canvas));
  node.chart = chart;
  let whole = null;
  /** Stats for a part (or, with no arguments, the whole line). */
  node.setStats = (...v) => {
    const [d, up, down, high] = v.length ? v : whole || [length(line), null, null, null];
    grid.replaceChildren(statBox('Distance', distance(d)), statBox('Ascent', height(up)), statBox('Descent', height(down)), statBox('Highest', height(high)));
  };
  return node;
}
/** The chart's map side (the app's PlannerUi range badges and track highlight). */
export function chartEvents(ctx, chart) {
  return {
    onInspect: (s) => ctx.marker(s?.p || null),
    onRange: (sec, a, b, r) => {
      if (!sec || !a || !b) { ctx.range(null); return; }
      ctx.marker(null);
      const [lo, hi] = [Math.min(...r), Math.max(...r)];
      const part = [a, ...chart().samples.filter((s) => s.d > lo && s.d < hi), b].sort((x, y) => x.d - y.d).map((s) => s.p);
      ctx.range({ a: a.p, b: b.p, part });
    },
  };
}

function photo(src, alt) {
  if (!src || !/^https:\/\//.test(src)) return null;
  return el('img', { class: 'photo', src, alt, loading: 'lazy', decoding: 'async', referrerpolicy: 'no-referrer', onerror: (e) => e.target.remove() });
}
const chips = (...items) => el('div', { class: 'chips' }, items.filter(Boolean).map((t) => el('span', { class: 'chip' }, t)));
const docLink = (href, text = 'DOC page ↗') => (href && /^https:\/\//.test(href) ? el('a', { class: 'doc-page', href, target: '_blank', rel: 'noopener' }, text) : null);
const busy = (text = 'Loading…') => el('p', { class: 'loading' }, el('span', { class: 'spinner' }), text);
const failed = (text) => el('p', { class: 'notice' }, text);

/** DOC's text blocks (the app's fill): headings, paragraphs, bullets, photos with captions. */
function fill(box, blocks) {
  let list = null;
  for (const [type, value, url] of blocks) {
    if (type === 'b') { if (!list) box.append(list = el('ul', { class: 'doc-list' })); list.append(el('li', {}, value)); continue; }
    list = null;
    if (type === 'h') box.append(el('h4', { class: 'doc-heading' }, value));
    else if (type === 'p') box.append(el('p', { class: 'doc-text' }, value));
    else if (type === 'i' && /^https:\/\//.test(url)) box.append(el('figure', { class: 'doc-photo' }, el('img', { src: url, alt: value.split('\n')[0] || '', loading: 'lazy', decoding: 'async', referrerpolicy: 'no-referrer', onerror: (e) => e.target.closest('figure')?.remove() }), value ? el('figcaption', {}, value) : null));
  }
}
let pages = new Map();
const walkPage = (guid) => pages.get(guid) || pages.set(guid, fetch(`/data/walks/${guid}.json`).then((r) => (r.ok ? r.json() : null)).catch(() => null)).get(guid);
/** The part of `line` between distances a and b along it. */
function slice(line, a, b) {
  const out = [];
  let walked = 0;
  for (let i = 1; i < line.length; i++) {
    const p = line[i - 1], q = line[i], len = length([p, q]);
    const t0 = (a - walked) / len, t1 = (b - walked) / len;
    if (walked + len >= a && walked <= b) {
      if (!out.length) out.push(t0 > 0 ? [p[0] + (q[0] - p[0]) * t0, p[1] + (q[1] - p[1]) * t0] : p);
      out.push(t1 < 1 ? [p[0] + (q[0] - p[0]) * t1, p[1] + (q[1] - p[1]) * t1] : q);
    }
    walked += len;
    if (walked > b) break;
  }
  return out;
}

/** A DOC walk by its line id: as the app's track summary – DOC's summary,
 * its legs (pick one to see it on the profile and map), the profile, huts
 * along the way, and DOC's page sections. */
export function openWalk(ctx, guid, { push = false } = {}) {
  ctx.sheet.open({
    kind: 'walk', title: ctx.overlays.infos()[guid]?.name || 'Track', subtitle: 'DOC walk',
    render(body, alive) {
      const controller = new AbortController();
      body.append(busy());
      (async () => {
        const [info, paths, f, page] = await Promise.all([doc.walkByGuid(guid, controller.signal), doc.walkLine(guid, controller.signal), walkFacts(), walkPage(guid)]);
        if (!alive()) return;
        const fact = f[guid] || [], o = page?.o || {};
        const target = stated(o.distance || fact[0] || '');
        const line = paths.length ? docRoute(paths, target) : [];
        body.replaceChildren();
        const name = info?.name || o.title || ctx.overlays.infos()[guid]?.name || 'Track';
        const subtitle = [o.level || info?.difficulty, o.time || fact[1] || info?.time].filter(Boolean).join(' · ') || 'DOC walk';
        ctx.sheet.setTitle(name, subtitle);
        ctx.sheet.setPhoto(info?.thumbnail || o.image, name);
        if (line.length >= 2) { ctx.select([line]); ctx.fit(line); }
        body.append(chips((o.distance || fact[0]) && `DOC: ${o.distance || fact[0]}`, (o.time || fact[1]) && `Time: ${o.time || fact[1]}`, fact[2] === 'loop' ? 'Loop' : fact[2] === 'one_way' ? 'One way' : null, (o.dogs || fact[3]) && (o.dogs || fact[3]).split('.')[0]));
        const partRow = el('div', { class: 'parts', hidden: true });
        const partText = el('div', { class: 'part-text', hidden: true });
        const intro = (o.intro || info?.introduction) ? el('p', { class: 'intro' }, o.intro || info.introduction) : null;
        let profile = null;
        if (line.length >= 2) {
          profile = profileBlock(ctx, line, alive);
          body.append(partRow, profile, partText);
        } else body.append(failed('DOC has no line for this walk.'));
        if (intro) body.append(intro);
        // DOC's current alerts: after the profile and summary, before the huts and DOC's sections.
        body.append(alertsBox(guid, info?.page || ctx.overlays.infos()[guid]?.page));
        const hutsBox = el('div', { class: 'huts' });
        const sectionsBox = el('div', { class: 'doc-sections' });
        body.append(hutsBox, sectionsBox);
        // DOC's page sections, as the app's accordion.
        for (const [title, blocks] of page?.s || []) {
          const content = el('div', { class: 'doc-section-body' });
          const item = el('details', { class: 'doc-section', ontoggle: () => { if (item.open && !content.childElementCount) fill(content, blocks); } }, el('summary', {}, title), content);
          sectionsBox.append(item);
        }
        const actions = el('div', { class: 'sheet-actions' });
        if (line.length >= 2) actions.append(el('button', { class: 'btn', type: 'button', onclick: () => ctx.planner.fromLine(name, line) }, 'Save as route'));
        const link = docLink(info?.page || ctx.overlays.infos()[guid]?.page, 'DOC page ↗');
        if (link) actions.append(link);
        body.append(actions, el('p', { class: 'fine' }, page ? 'From DOC’s page for this walk (CC BY 4.0). Check DOC for alerts and conditions before you go.' : DOC_NOTE));
        if (!profile) return;
        // Huts along the way, on the profile and listed.
        doc.hutsIn(bounds(line, 500), controller.signal).then((huts) => {
          if (!alive()) return;
          const along = stops(line, huts);
          if (!along.length) return;
          profile.chart.setMarkers(along.map((h) => [h.along, h.hut.name]));
          hutsBox.append(el('h3', {}, 'Huts along the way'), el('ul', { class: 'hut-list' }, along.map((h) => el('li', {}, el('button', { type: 'button', onclick: () => openHut(ctx, h.hut.name, h.hut.point, { push: true }) }, el('span', { class: 'hut-dot' }), el('span', { class: 'hut-name' }, h.hut.name, el('small', {}, [h.hut.category, bunks(h.hut.bunks)].filter(Boolean).join(' · '))), el('span', { class: 'hut-km' }, distance(h.along)))))));
        }).catch(() => {});
        // DOC's legs ("A to B"), placed on the line by their ends' names.
        const docLegs = legs(page?.s || []);
        if (!docLegs.length) return;
        const total = length(line), mid = line[Math.floor(line.length / 2)];
        const names = docLegs.flatMap((l) => [l.from, l.to]);
        const located = await search.call({ type: 'locate', url: '/data/places.tsv', names, near: mid, maxKm: 40 }).catch(() => null);
        if (!alive() || !located) return;
        const at = (p) => { if (!p) return null; const n = nearestOn(line, p); return n.d <= 1500 ? n.along : null; };
        const parts = docLegs.map((l, i) => { const a = at(located[i * 2]), b = at(located[i * 2 + 1]); return a == null || b == null || Math.abs(b - a) < 100 ? null : { ...l, span: [Math.min(a, b), Math.max(a, b)] }; }).filter(Boolean);
        if (!parts.length || parts.every((p) => p.span[1] - p.span[0] > total * 0.97)) return;
        let selected = -1;
        const show = () => {
          [...partRow.children].forEach((c, i) => c.setAttribute('aria-pressed', String(i - 1 === selected)));
          if (selected < 0) {
            profile.chart.showAll(); profile.setStats(); ctx.part(null); ctx.sheet.setTitle(null, subtitle);
            partText.hidden = true; if (intro) intro.hidden = false;
            ctx.fit(line);
            return;
          }
          const p = parts[selected], [a, b] = p.span, st = profile.chart.spanStats(a, b);
          profile.chart.focus([p.span]);
          profile.setStats(b - a, st?.complete ? st.ascent : null, st?.complete ? st.descent : null, st?.high);
          ctx.sheet.setTitle(null, [p.time, p.km != null && `${p.km} km`].filter(Boolean).join(' · ') || subtitle);
          const piece = slice(line, a, b);
          ctx.part(piece); ctx.fit(piece);
          partText.replaceChildren(el('h3', {}, p.title), el('p', { class: 'muted' }, [p.time, p.km != null && `${p.km} km`].filter(Boolean).join(' · ')));
          fill(partText, p.blocks);
          partText.hidden = false; if (intro) intro.hidden = true;
        };
        partRow.append(...['Whole', ...parts.map((p) => p.title)].map((t, i) => el('button', { type: 'button', class: 'part', onclick: () => { selected = i - 1; show(); } }, t)));
        partRow.hidden = false;
        show();
      })().catch((e) => { if (alive() && e.name !== 'AbortError') body.replaceChildren(failed('DOC’s track information could not be loaded. Check your connection and try again.')); });
      return () => { controller.abort(); ctx.select(null); ctx.marker(null); ctx.range(null); };
    },
  }, { push });
}

/** A map track by its own name (no DOC walk found): DOC's track record if there is one. */
export function openTrack(ctx, name, tapped, pieces, { push = false } = {}) {
  ctx.sheet.open({
    kind: 'track', title: name || 'Track', subtitle: 'Track',
    render(body, alive) {
      const controller = new AbortController();
      const line = chain(pieces, tapped);
      if (line.length >= 2) ctx.select([line]);
      body.append(line.length >= 2 ? profileBlock(ctx, line, alive) : busy());
      const extra = el('div');
      body.append(extra);
      if (name) doc.tracksNamed(name, tapped[0], tapped[1], controller.signal).then((tracks) => {
        if (!alive() || !tracks.length) return;
        const t = tracks[0];
        ctx.sheet.setTitle(null, [t.category, t.duration].filter(Boolean).join(' · ') || 'Track');
        extra.append(chips(t.type, t.status && !/^open$/i.test(t.status) ? `Status: ${t.status}` : null, t.dogs && `Dogs: ${t.dogs}`));
        extra.append(el('p', { class: 'fine' }, DOC_NOTE));
      }).catch(() => {});
      body.append(el('div', { class: 'sheet-actions' }, line.length >= 2 ? el('button', { class: 'btn', type: 'button', onclick: () => ctx.planner.fromLine(name || 'Track', line) }, 'Save as route') : null));
      return () => { controller.abort(); ctx.select(null); ctx.marker(null); ctx.range(null); };
    },
  }, { push });
}

const km = (v) => (v == null ? '' : v.toLocaleString('en-NZ', { maximumFractionDigits: 1 }));

/** Te Araroa: one piece of it (its kilometres, profile and the pieces either side)
 * or, with no piece, the whole trail. `walk`: the DOC walk the piece follows, if any. */
export function openTeAraroa(ctx, index = null, { push = false, walk = null } = {}) {
  ctx.sheet.open({
    kind: 'te-araroa', title: 'Te Araroa', subtitle: 'Trail',
    render(body, alive) {
      body.append(busy());
      teAraroa.load().then((d) => {
        if (!alive()) return;
        body.replaceChildren();
        const piece = index == null ? null : d.pieces[index];
        const links = el('div', { class: 'sheet-actions' });
        if (!piece) {
          ctx.sheet.setTitle('Te Araroa', `The Long Pathway · ${km(d.total)} km`);
          const all = d.pieces.map((p) => p.line);
          ctx.select(all);
          ctx.fit(all.flat());
          body.append(el('div', { class: 'stats' }, statBox('Distance', `${km(d.total)} km`), statBox('From', 'Cape Reinga'), statBox('To', 'Bluff'), statBox('Season', d.season || '–')));
          body.append(el('p', { class: 'intro' }, 'Te Araroa runs the length of Aotearoa New Zealand, from Cape Reinga to Bluff, along tracks, beaches, rivers, forest roads and towns. Tap any part of the red line for that stretch’s kilometres and elevation profile.'));
          links.append(...[docLink(teAraroa.PAGE, 'Te Araroa ↗'), docLink(teAraroa.NOTES, 'Trail notes & maps ↗'), docLink(teAraroa.ALERTS, 'Trail alerts ↗')].filter(Boolean));
        } else {
          const from = piece.from, to = piece.to;
          ctx.sheet.setTitle(piece.name || 'Te Araroa', from != null ? `Te Araroa · km ${km(from)} to ${km(to)}` : 'Te Araroa');
          ctx.select([piece.line]);
          ctx.fit(piece.line);
          body.append(chips('Kilometres southbound', d.season && `${d.season} season`));
          body.append(profileBlock(ctx, piece.line, alive));
          const prev = d.pieces[index - 1], next = d.pieces[index + 1];
          body.append(el('div', { class: 'ta-steps' },
            el('button', { class: 'btn', type: 'button', disabled: !prev, title: prev?.name || '', onclick: () => openTeAraroa(ctx, index - 1) }, '← Previous'),
            el('button', { class: 'btn', type: 'button', onclick: () => openTeAraroa(ctx, null) }, 'Whole trail'),
            el('button', { class: 'btn', type: 'button', disabled: !next, title: next?.name || '', onclick: () => openTeAraroa(ctx, index + 1) }, 'Next →')));
          const walkName = walk && ctx.overlays.infos()[walk]?.name;
          links.append(...[el('button', { class: 'btn', type: 'button', onclick: () => ctx.planner.fromLine(`Te Araroa: ${piece.name}`, piece.line) }, 'Save as route'),
            walkName ? el('button', { class: 'btn', type: 'button', onclick: () => ctx.openWalk(walk) }, `DOC: ${walkName}`) : null,
            docLink(teAraroa.ALERTS, 'Trail alerts ↗')].filter(Boolean));
        }
        body.append(links, el('p', { class: 'fine' }, `Trail from Te Araroa Trust’s ${d.season || 'current'} GPS files (teararoa.org.nz, CC BY 4.0). Routes change between seasons: check the trail notes and alerts before you go.`));
      }).catch(() => { if (alive()) body.replaceChildren(failed('Te Araroa could not be loaded. Check your connection.')); });
      return () => { ctx.select(null); ctx.marker(null); ctx.range(null); };
    },
  }, { push });
}

/** A hut: DOC's record and page. */
export function openHut(ctx, name, point, { push = false } = {}) {
  ctx.sheet.open({
    kind: 'hut', title: name || 'Hut', subtitle: 'Hut', 
    render(body, alive) {
      const controller = new AbortController();
      ctx.marker(point);
      body.append(busy());
      doc.hutDetails(point[0], point[1], controller.signal).then(({ assets, pages }) => {
        if (!alive()) return;
        const asset = matchHut(assets, name || '', point), page = matchHut(pages, name || '', point);
        body.replaceChildren();
        const title = page?.name || asset?.name || name || 'Hut';
        ctx.sheet.setTitle(title, [asset?.category && `${asset.category} hut`, page?.place].filter(Boolean).join(' · ') || 'Hut');
        ctx.sheet.setPhoto(page?.thumbnail, title);
        if (asset?.status && !/^open$/i.test(asset.status)) body.append(el('p', { class: 'alert' }, `DOC lists this hut as ${asset.status.toLowerCase()}.`));
        body.append(alertsBox(guidOf(page?.page), page?.page));
        body.append(el('div', { class: 'stats' }, statBox('Bunks', /^\d+/.exec(asset?.bunks || '')?.[0] || '–'), statBox('Bookings', page ? (page.bookable ? 'Required' : 'Not needed') : '–'), statBox('Category', asset?.category || '–')));
        if (asset?.roadEnd) body.append(el('p', {}, el('b', {}, 'From the road end: '), asset.roadEnd));
        const facilities = page?.facilities?.length ? page.facilities : (asset?.facilities ? asset.facilities.split(/,\s*/) : []);
        if (facilities.length) body.append(el('h3', {}, 'Facilities'), el('ul', { class: 'plain' }, facilities.map((f) => el('li', {}, f))));
        if (!asset && !page) body.append(el('p', { class: 'muted' }, 'DOC has no details for this hut. It may be privately owned or managed by a club.'));
        body.append(el('div', { class: 'sheet-actions' },
          el('button', { class: 'btn', type: 'button', onclick: () => ctx.planner.startAt(point, title) }, 'Route from here'),
          docLink(page?.page, page?.bookable ? 'Book on DOC ↗' : 'DOC page ↗')));
        body.append(el('p', { class: 'fine' }, 'Hut information from the Department of Conservation.'));
      }).catch(() => { if (alive()) body.replaceChildren(failed('DOC’s hut information could not be loaded.')); });
      return () => { controller.abort(); ctx.marker(null); };
    },
  }, { push });
}

/** Hunting permit areas under a tap. */
export function openHunting(ctx, areas) {
  ctx.sheet.open({
    kind: 'hunting', title: areas.length > 1 ? 'Hunting permit areas' : areas[0].name || 'Hunting permit area', subtitle: 'Recreational hunting', 
    render(body) {
      for (const a of areas) {
        body.append(el('div', { class: 'card-row' },
          el('h3', {}, a.name || 'Hunting area'),
          chips(a.permit && `Permit area: ${a.permit}`, a.type, a.ha ? `${Math.round(a.ha).toLocaleString('en-NZ')} ha` : null),
          a.page && /^https:\/\//.test(a.page) ? docLink(a.page, 'Permit on DOC ↗') : null));
      }
      body.append(el('p', { class: 'fine' }, 'Hunting permit areas from the Department of Conservation. A permit is required; check DOC for the conditions, closures and boundaries before you go.'));
    },
  });
}

export { guidOf, escape };
