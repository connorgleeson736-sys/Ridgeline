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
    render(body, alive, { restored = false } = {}) {
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
        if (line.length >= 2) { ctx.select([line]); if (!restored) ctx.fit(line); }
        const taWalk = teAraroaLinks(ctx, line);
        if (taWalk) body.append(taWalk);
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
      const taTrack = teAraroaLinks(ctx, line);
      if (taTrack) body.append(taTrack);
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
      body.append(el('div', { class: 'sheet-actions' }, ...[
        line.length >= 2 ? el('button', { class: 'btn', type: 'button', onclick: () => ctx.planner.fromLine(name || 'Track', line) }, 'Save as route') : null,
      ].filter(Boolean)));
      return () => { controller.abort(); ctx.select(null); ctx.marker(null); ctx.range(null); };
    },
  }, { push });
}

const km = (v) => (v == null ? '' : v.toLocaleString('en-NZ', { maximumFractionDigits: 1 }));
const nobo = (d, v) => (v == null ? null : Math.max(0, d.total - v));

// ---- Te Araroa ----
/** The Trust's time, its first part ("5 days walking, 2–3 days biking" → "5 days walking"). */
const shortTime = (t) => (t || '').split(/[,;(]/)[0].trim();
/** A km range as the walker meets it (southbound or northbound). */
const kmRange = (d, a, b) => { const x = teAraroa.kmIn(d, a), y = teAraroa.kmIn(d, b); return `km ${km(Math.min(x, y))}–${km(Math.max(x, y))}`; };
const dirWord = () => (teAraroa.dir() === 'nobo' ? 'northbound' : 'southbound');

/** Southbound / Northbound. */
function directionSwitch(onChange) {
  const make = (v, label) => el('button', { type: 'button', 'aria-pressed': String(teAraroa.dir() === v), onclick: () => { if (teAraroa.dir() === v) return; teAraroa.setDir(v); onChange(); } }, label);
  return el('div', { class: 'ta-dir', role: 'group', 'aria-label': 'Direction' }, make('sobo', 'Southbound'), make('nobo', 'Northbound'));
}

/** The Trust's current alerts for a section (live), closures first; nothing when none. */
function taAlertsBox(slug) {
  const box = el('section', { class: 'doc-alerts', hidden: true, 'aria-label': 'Te Araroa alerts' });
  teAraroa.alerts(slug).then((list) => {
    if (list && !list.length) return;
    box.hidden = false;
    if (!list) { box.append(el('p', { class: 'doc-alerts-head' }, 'Couldn’t load Te Araroa’s current alerts. ', docLink(teAraroa.ALERTS, 'Check them ↗'))); return; }
    const rank = (a) => (/closure/i.test(a.status) ? 0 : /warning/i.test(a.status) ? 1 : 2);
    list.sort((a, b) => rank(a) - rank(b));
    box.append(el('p', { class: 'doc-alerts-head' }, list.length === 1 ? 'Trail alert' : `${list.length} trail alerts`));
    list.forEach((a, i) => {
      const where = a.from != null ? `km ${km(a.from)}${a.to != null && a.to !== a.from ? `–${km(a.to)}` : ''} SOBO` : '';
      const item = el('details', { class: 'doc-alert' }, el('summary', {}, a.title || 'Alert', el('small', { class: 'alert-meta' }, [a.status, where].filter(Boolean).join(' · '))), el('div', { class: 'doc-alert-body' }, safeHtml(a.description)));
      if (i === 0 && list.length === 1) item.open = true;
      box.append(item);
    });
  });
  return box;
}

/** In a notes tab, an "On map" button after each entry that names a place the map
 * knows: it flies there (and marks it), leaving the panel as it is. */
function mapLinks(ctx, content, known) {
  const fold = (t) => (t || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[’']/g, '').replace(/\s+/g, ' ').trim();
  const items = known.map((p) => ({ p, k: fold(p.name) })).filter((x) => x.k.length >= 4).sort((a, b) => b.k.length - a.k.length);
  for (const entry of content.querySelectorAll('li, p')) {
    const text = fold(entry.textContent);
    const hit = items.find((x) => text.includes(x.k));
    if (!hit || entry.querySelector('.on-map')) continue;
    entry.append(' ', el('button', { type: 'button', class: 'link on-map', onclick: () => { ctx.marker(hit.p.point); ctx.flyTo?.(hit.p.point, 15); } }, 'On map'));
  }
}

/** Opens a panel picked from the Te Araroa list beside it (computers), or in the
 * sheet with Back (phones). Picked from the map, it opens in the sheet. */
function openPicked(ctx, panel, { fromList = false, push = false } = {}) {
  if (fromList && ctx.side?.available) { ctx.side.open({ ...panel, render: (body, alive, o) => panel.render(body, alive, ctx.side, o) }, { push: push && ctx.side.isOpen }); return; }
  ctx.sheet.open({ kind: panel.kind, title: panel.title, subtitle: panel.subtitle, backLabel: panel.backLabel, render: (body, alive, o) => panel.render(body, alive, ctx.sheet, o) }, { push: fromList || push });
}

/** A section (or option): its facts, alerts, profile, food, then the rest folded away. */
function sectionPanel(ctx, d, s, { at = null, walk = null, fromList = false } = {}) {
  const ends = () => (teAraroa.dir() === 'nobo' ? [s.to, s.from] : [s.from, s.to]);
  const sub = () => (s.alternative ? `${teAraroa.optionKind(s).label} · leaves the trail at km ${km(teAraroa.kmIn(d, ends()[0]))}, rejoins at km ${km(teAraroa.kmIn(d, ends()[1]))}` : `Section ${s.n} · ${s.region} · ${kmRange(d, s.from, s.to)}`);
  return {
    kind: 'te-araroa', key: s.slug, title: s.name, subtitle: sub(), backLabel: s.name.length > 26 ? 'Back' : s.name,
    render(body, alive, host, { restored = false } = {}) {
      let first = !restored;
      const draw = () => {
        body.replaceChildren();
        host.setTitle(s.name, sub());
        const lines = teAraroa.sectionLines(d, s), line = teAraroa.facing(teAraroa.sectionLine(d, s));
        if (lines.length) { ctx.select(lines); if (first) ctx.fit(lines.flat()); }
        first = false;
        if (!fromList) body.append(directionSwitch(draw));
        body.append(el('p', { class: 'ta-facts' }, [s.length && `${km(s.length)} km`, shortTime(s.time)].filter(Boolean).join(' · ')));
        if (s.alternative) body.append(el('p', { class: 'ta-why' }, teAraroa.optionKind(s).why));
        body.append(taAlertsBox(s.slug));
        const profile = line.length >= 2 ? profileBlock(ctx, line, alive) : null;
        const intro = el('div', { class: 'intro ta-intro' });
        if (profile) {
          body.append(profile);
          if (!s.alternative && s.to > s.from) {
            const len = length(line), span = s.to - s.from, nobo = teAraroa.dir() === 'nobo';
            const marks = d.places.filter((p) => p.section === s.slug && /hut/i.test(p.type)).map((p) => [((nobo ? s.to - p.km : p.km - s.from) / span) * len, p.name]);
            if (marks.length) profile.chart.setMarkers(marks);
          }
        }
        body.append(intro);
        // Food: what is in the section, and the next place after it.
        if (s.from != null) {
          const here = teAraroa.walking(d.resupply.filter((r) => teAraroa.onTrail(r) && r.km >= s.from - 0.05 && r.km <= s.to + 0.05));
          const end = at ?? (teAraroa.dir() === 'nobo' ? s.from : s.to);
          const next = teAraroa.foodAround(d, end).ahead;
          const stop = (r) => el('button', { type: 'button', class: 'link', onclick: () => openPicked(ctx, stopPanel(ctx, d, d.resupply.indexOf(r), { fromList }), { fromList, push: true }) }, r.name);
          const food = el('div', { class: 'ta-food-line' }, el('span', { class: 'food-dot supermarket', 'aria-hidden': 'true' }),
            el('span', {}, here.length ? ['Food here: ', ...here.flatMap((r, i) => [i ? ', ' : '', stop(r)])] : 'No food in this section.',
              next && !here.includes(next) ? el('small', {}, 'Next: ', stop(next), ` in ${km(Math.abs(next.km - end))} km, ${teAraroa.daysText(teAraroa.days(d, Math.min(end, next.km), Math.max(end, next.km)))}`) : null,
              // Towns a hitch away before the next stop on the trail.
              (() => { if (!next) return null; const lo = Math.min(end, next.km), hi = Math.max(end, next.km); const h = teAraroa.walking(d.resupply.filter((r) => !teAraroa.onTrail(r) && r.km > lo && r.km < hi)); return h.length ? el('small', {}, 'Or hitch to: ', ...h.flatMap((r, i) => [i ? ', ' : '', stop(r), ` (${km(r.off / 1000)} km off)`])) : null; })()));
          body.append(food);
        }
        // Bypasses here (or, for an option, the section it replaces).
        const options = s.alternative ? d.mains.filter((m) => Math.min(m.to, s.to) - Math.max(m.from, s.from) > 2) : d.sections.filter((o) => o.alternative && o.from != null && s.from != null && Math.min(o.to, s.to) - Math.max(o.from, s.from) > 0.3);
        if (options.length) body.append(el('div', { class: 'ta-options' }, el('span', {}, s.alternative ? 'Instead of: ' : 'Bypasses here: '),
          ...options.map((o) => el('button', { type: 'button', class: 'link', onclick: () => openPicked(ctx, sectionPanel(ctx, d, o, { fromList }), { fromList, push: true }) }, o.alternative ? `${o.name} (${km(o.length)} km)` : o.name))));
        // Folded away: places to stay, and the Trust's notes.
        const more = el('div', { class: 'doc-sections' });
        body.append(more);
        teAraroa.notes(s.slug).then((n) => {
          if (!alive() || !n) return;
          if (n.intro) intro.append(safeHtml(n.intro));
          for (const [title, html] of [['The walk', n.walk], ['Getting there', n.access], ['Transport', n.transport], ['Accommodation', n.accommodation], ['Food and supplies', n.food], ['Track conditions and hazards', n.conditions]]) {
            if (!html || !html.replace(/<[^>]+>|&nbsp;|\s/g, '')) continue;
            const content = el('div', { class: 'doc-section-body' });
            const item = el('details', { class: 'doc-section', ontoggle: () => {
              if (!item.open || content.childElementCount) return;
              content.append(safeHtml(html));
              // Places named in the notes that the map knows: "On map" takes you there.
              if (title === 'Accommodation') mapLinks(ctx, content, d.places.filter((p) => !p.approx && (p.section === s.slug || (s.from != null && p.km >= s.from - 2 && p.km <= s.to + 2))));
              if (title === 'Food and supplies') mapLinks(ctx, content, (d.shops || []).filter((x) => { const r = d.resupply[x.stop]; return r && s.from != null && r.km >= s.from - 15 && r.km <= s.to + 15; }));
            } }, el('summary', {}, title), content);
            more.append(item);
          }
        });
        const walkName = walk && ctx.overlays.infos()[walk]?.name;
        body.append(el('div', { class: 'sheet-actions' }, ...[
          line.length >= 2 ? el('button', { class: 'btn', type: 'button', onclick: () => ctx.planner.fromLine(`Te Araroa: ${s.name}`, line) }, 'Save as route') : null,
          walkName ? el('button', { class: 'btn', type: 'button', onclick: () => ctx.openWalk(walk) }, `DOC: ${walkName}`) : null,
          !fromList ? el('button', { class: 'btn', type: 'button', onclick: () => openTeAraroa(ctx, null) }, 'All sections') : null,
          docLink(teAraroa.sectionPage(s.slug), 'Te Araroa page ↗'),
        ].filter(Boolean)));
        body.append(el('p', { class: 'fine' }, 'From Te Araroa Trust (teararoa.org.nz, CC BY 4.0). Alerts are live.'));
      };
      draw();
    },
  };
}

/** The whole trail, as a panel (Back from a section returns here). */
function overviewPanel(ctx, tab = null) {
  return {
    kind: 'te-araroa', overview: true, title: 'Te Araroa', subtitle: 'Cape Reinga to Bluff', backLabel: 'Te Araroa',
    render(body, alive, { restored = false } = {}) {
      body.append(busy());
      teAraroa.load().then((d) => { if (alive()) overview(ctx, d, body, alive, tab, restored); }).catch(() => { if (alive()) body.replaceChildren(failed('Te Araroa could not be loaded. Check your connection.')); });
      return () => { ctx.side?.close(); if (ctx.side) ctx.side.onChange = null; ctx.select(null); ctx.marker(null); };
    },
  };
}

/** Whether the Te Araroa page is open with room beside it (a computer). */
const onTeAraroaPage = (ctx) => !!ctx.sheet.current?.overview && !!ctx.side?.available;

/** Something on Te Araroa tapped on the map: on the Te Araroa page it opens beside the
 * list (the page stays); elsewhere in the sheet. */
export function pickOnTeAraroa(ctx, what, i, { push = false, at = null, walk = null } = {}) {
  return teAraroa.load().then((d) => {
    if (!onTeAraroaPage(ctx)) return false;
    const panel = what === 'section' ? sectionPanel(ctx, d, d.bySlug[i], { at, walk, fromList: true }) : what === 'stop' ? stopPanel(ctx, d, i, { fromList: true }) : what === 'shop' ? shopPanel(ctx, d, i) : placePanel(ctx, d, i);
    openPicked(ctx, panel, { fromList: true });
    return true;
  });
}

/** One section of Te Araroa (from the map: Back leads to the whole trail), or with no
 * section, the whole trail. */
export function openTeAraroa(ctx, slug = null, { push = false, at = null, walk = null, tab = null } = {}) {
  if (!slug) { ctx.sheet.open(overviewPanel(ctx, tab), { push }); return; }
  teAraroa.load().then((d) => {
    const s = d.bySlug[slug];
    if (!s) return;
    const p = sectionPanel(ctx, d, s, { at, walk });
    const panel = { kind: p.kind, title: p.title, subtitle: p.subtitle, backLabel: p.backLabel, render: (body, alive, o) => { p.render(body, alive, ctx.sheet, o); return () => { ctx.select(null); }; } };
    if (push) ctx.sheet.open(panel, { push: true }); else ctx.sheet.openWithBack(panel, overviewPanel(ctx));
  });
}

/** The whole trail: direction, then tabs for its sections, food and bypasses. Picking
 * one opens it beside the list (one at a time). */
function overview(ctx, d, body, alive, tab = 'sections', restored = false) {
  let current = tab || 'sections';
  const all = d.pieces.map((p) => p.line);
  const wholeView = (fit = true) => { ctx.select(all); if (fit) ctx.fit(all.flat()); };
  if (ctx.side) ctx.side.home = 'Te Araroa';
  const rows = new Map();
  // Back to the list: the whole trail highlighted again, the map left where it is.
  if (ctx.side) ctx.side.onChange = (key) => { for (const [k, row] of rows) row.classList.toggle('open', k === key); if (!key && alive()) wholeView(false); };
  const pick = (panel) => openPicked(ctx, panel, { fromList: true });
  const draw = () => {
    body.replaceChildren();
    rows.clear();
    ctx.sheet.setTitle('Te Araroa', `Cape Reinga to Bluff · ${km(d.total)} km`);
    if (!ctx.side?.isOpen) wholeView(!restored);
    restored = true;
    const tabs = el('div', { class: 'ta-tabs', role: 'tablist' });
    const pane = el('div', { class: 'ta-pane' });
    body.append(directionSwitch(() => { draw(); if (ctx.side?.isOpen) ctx.side.close(); }), tabs, pane);
    const show = (name) => {
      current = name;
      for (const b of tabs.children) b.setAttribute('aria-selected', String(b.dataset.tab === name));
      pane.replaceChildren();
      if (name === 'sections') {
        let region = null;
        for (const s of teAraroa.walking(d.mains)) {
          if (s.region !== region) { region = s.region; pane.append(el('h3', {}, region)); }
          const badge = el('span', { class: 'ta-badge', hidden: true });
          const row = el('button', { type: 'button', class: 'ta-row', onclick: () => pick(sectionPanel(ctx, d, s, { fromList: true })) },
            el('span', { class: 'ta-n' }, String(s.n)),
            el('span', { class: 'ta-name' }, s.name, el('small', {}, [`km ${Math.round(teAraroa.kmIn(d, teAraroa.dir() === 'nobo' ? s.to : s.from))}`, s.length && `${km(s.length)} km`].filter(Boolean).join(' · '))), badge);
          rows.set(s.slug, row); badge.dataset.slug = s.slug;
          pane.append(row);
        }
      } else if (name === 'food') {
        pane.append(el('p', { class: 'muted' }, `Carries between places to buy food, ${dirWord()}. Days are rough.`),
          el('p', { class: 'food-key' }, el('span', { class: 'food-dot supermarket' }), 'Supermarket ', el('span', { class: 'food-dot store' }), 'Store ', el('span', { class: 'food-dot cafe' }), 'Café ', el('span', { class: 'food-dot box' }), 'Send a box'));
        for (const c of teAraroa.carries(d)) {
          const row = el('button', { type: 'button', class: `ta-row food${c.km >= 100 ? ' long' : ''}`, onclick: () => pick(stopPanel(ctx, d, d.resupply.indexOf(c.to), { fromList: true })) },
            el('span', { class: 'ta-name' }, el('span', { class: 'food-leg' }, el('span', { class: `food-dot ${c.from.kind || c.from.level}` }), c.from.name, el('span', { class: 'food-arrow' }, '→'), el('span', { class: `food-dot ${c.to.kind || c.to.level}` }), c.to.name),
              el('small', {}, [`${km(c.km)} km`, teAraroa.daysText(c.days), c.hitch.length && `hitch: ${c.hitch.map((r) => r.name).join(', ')}`].filter(Boolean).join(' · '))));
          rows.set(`stop-${d.resupply.indexOf(c.to)}`, row);
          pane.append(row);
        }
      } else {
        pane.append(el('p', { class: 'muted' }, 'Ways around parts of the trail: for rivers in flood, by road, or by water. Dashed red on the map.'));
        for (const o of teAraroa.walking(d.sections.filter((x) => x.alternative))) {
          const badge = el('span', { class: 'ta-badge', hidden: true });
          const row = el('button', { type: 'button', class: 'ta-row', onclick: () => pick(sectionPanel(ctx, d, o, { fromList: true })) },
            el('span', { class: 'ta-n option' }, '↳'),
            el('span', { class: 'ta-name' }, o.name, el('small', {}, [teAraroa.optionKind(o).label, o.length && `${km(o.length)} km`, kmRange(d, o.from, o.to)].filter(Boolean).join(' · '))), badge);
          rows.set(o.slug, row); badge.dataset.slug = o.slug;
          pane.append(row);
        }
      }
      if (ctx.side?.key) rows.get(ctx.side.key)?.classList.add('open');
      teAraroa.status().then((st) => {
        if (!alive() || !st) return;
        for (const b of pane.querySelectorAll('.ta-badge')) {
          const v = st[b.dataset.slug];
          if (!v) continue;
          const shut = /clos/i.test(v.status);
          if (v.alerts || !/^open$/i.test(v.status || 'open')) { b.hidden = false; b.className = `ta-badge${shut ? ' closed' : ''}`; b.textContent = shut ? 'Closed' : v.alerts ? `${v.alerts} alert${v.alerts > 1 ? 's' : ''}` : v.status; }
        }
      });
    };
    for (const [k, label] of [['sections', 'Sections'], ['food', 'Food'], ['options', 'Bypasses']]) tabs.append(el('button', { type: 'button', role: 'tab', 'data-tab': k, onclick: () => show(k) }, label));
    show(current);
    body.append(el('div', { class: 'sheet-actions' }, ...[docLink(teAraroa.NOTES, 'Trail notes ↗'), docLink(teAraroa.ALERTS, 'Trail alerts ↗')].filter(Boolean)),
      el('p', { class: 'fine' }, 'From Te Araroa Trust (teararoa.org.nz, CC BY 4.0). Food stops from walkers’ guides: check shops before relying on them.'));
  };
  draw();
}

/** A place to buy food: what is there, and the carries either side. */
function stopPanel(ctx, d, i, { fromList = false } = {}) {
  const r = d.resupply[i];
  return {
    kind: 'te-araroa-stop', key: `stop-${i}`, title: r?.name || 'Food', subtitle: '', backLabel: r?.name || 'Back',
    render(body, alive, host, { restored = false } = {}) {
      if (!r) return;
      host.setTitle(r.name, `${teAraroa.KINDS[r.kind] || teAraroa.LEVELS[r.level] || 'Food'} · km ${km(teAraroa.kmIn(d, r.km))}${teAraroa.onTrail(r) ? '' : ` · ${km(r.off / 1000)} km off the trail`}`);
      ctx.marker(r.point);
      if (!restored) ctx.flyTo?.(r.point, 11);
      if (r.note) body.append(el('p', { class: 'intro' }, r.note));
      if (r.bounce) body.append(el('p', { class: 'ta-facts' }, 'Walkers send food boxes here.'));
      const { behind, ahead } = teAraroa.foodAround(d, r.km);
      const leg = (label, x) => x && el('li', {}, el('button', { type: 'button', onclick: () => openPicked(ctx, stopPanel(ctx, d, d.resupply.indexOf(x), { fromList }), { fromList, push: true }) }, el('span', { class: `food-dot ${x.kind || x.level}` }), el('span', { class: 'hut-name' }, `${label}${x.name}`, el('small', {}, teAraroa.KINDS[x.kind] || teAraroa.LEVELS[x.level])), el('span', { class: 'hut-km' }, `${km(Math.abs(x.km - r.km))} km · ${teAraroa.daysText(teAraroa.days(d, Math.min(r.km, x.km), Math.max(r.km, x.km)))}`)));
      body.append(el('ul', { class: 'hut-list' }, [leg('Next: ', ahead), leg('Previous: ', behind)].filter(Boolean)));
      const here = (d.shops || []).map((x, k) => ({ x, k })).filter(({ x }) => x.stop === i);
      if (here.length) {
        const order = { supermarket: 0, store: 1, cafe: 2 };
        here.sort((a, b) => order[a.x.kind] - order[b.x.kind] || a.x.name.localeCompare(b.x.name));
        body.append(el('h3', { class: 'ta-sub' }, 'Shops and cafés'), el('ul', { class: 'hut-list' }, here.map(({ x, k }) => el('li', {}, el('button', { type: 'button', onclick: () => openPicked(ctx, shopPanel(ctx, d, k), { fromList, push: true }) },
          el('span', { class: `food-dot ${x.kind}` }), el('span', { class: 'hut-name' }, x.name, el('small', {}, [teAraroa.KINDS[x.kind], x.address].filter(Boolean).join(' · '))))))));
      }
      if (r.shops?.length) body.append(el('details', { class: 'doc-section' }, el('summary', {}, 'Shops in Te Araroa’s notes'), el('div', { class: 'doc-section-body' }, el('ul', { class: 'plain-list' }, r.shops.map((t) => el('li', {}, t))))));
      body.append(el('p', { class: 'fine' }, 'From walkers’ resupply guides and Te Araroa’s notes. Shops and hours change.'));
      return () => { ctx.marker(null); };
    },
  };
}
/** A shop or café (OpenStreetMap). */
function shopPanel(ctx, d, i) {
  const x = d.shops[i];
  return {
    kind: 'te-araroa-shop', key: `shop-${i}`, title: x?.name || 'Shop', subtitle: '', backLabel: x?.name && x.name.length <= 26 ? x.name : 'Back',
    render(body, alive, host, { restored = false } = {}) {
      if (!x) return;
      const r = d.resupply[x.stop];
      host.setTitle(x.name, [teAraroa.KINDS[x.kind], r && r.name].filter(Boolean).join(' · '));
      ctx.marker(x.point);
      if (!restored) ctx.flyTo?.(x.point, 16);
      if (x.address) body.append(el('p', {}, x.address));
      if (x.hours) body.append(el('p', {}, el('b', {}, 'Hours: '), x.hours.replace(/;\s*/g, '; ')));
      if (x.phone) body.append(el('p', {}, el('a', { href: `tel:${x.phone.replace(/[^\d+]/g, '')}` }, x.phone)));
      body.append(el('div', { class: 'sheet-actions' }, ...[
        docLink(/^https:\/\//.test(x.website) ? x.website : '', 'Website ↗'),
        el('a', { class: 'doc-page', href: `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(`${x.name} ${x.point[1]},${x.point[0]}`)}`, target: '_blank', rel: 'noopener' }, 'Google Maps ↗'),
      ].filter(Boolean)));
      body.append(el('p', { class: 'fine' }, '© OpenStreetMap contributors (ODbL). Hours change: check before you rely on them.'));
      return () => { ctx.marker(null); };
    },
  };
}
/** A shop or café tapped on the map. */
export function openTeAraroaShop(ctx, i, { push = false } = {}) {
  teAraroa.load().then((d) => {
    const p = shopPanel(ctx, d, i);
    ctx.sheet.open({ kind: p.kind, title: p.title, backLabel: p.backLabel, render: (body, alive, o) => p.render(body, alive, ctx.sheet, o) }, { push });
  });
}

/** A food stop (from the map, or from a list). */
export function openTeAraroaStop(ctx, i, { push = false, fromList = false } = {}) {
  teAraroa.load().then((d) => {
    const p = stopPanel(ctx, d, i, { fromList });
    if (fromList && ctx.side?.available && ctx.side.isOpen) { openPicked(ctx, p, { fromList: true }); return; }
    ctx.sheet.open({ kind: p.kind, title: p.title, backLabel: p.backLabel, render: (body, alive, o) => p.render(body, alive, ctx.sheet, o) }, { push: push || fromList });
  });
}

/** "Part of Te Araroa": the sections a walk or track runs along, linked. */
export function teAraroaLinks(ctx, line) {
  const d = teAraroa.loaded();
  if (!d || !line || line.length < 2) return null;
  const secs = teAraroa.sectionsAlong(d, line);
  if (!secs.length) return null;
  return el('div', { class: 'ta-link' }, el('span', { class: 'ta-link-mark', 'aria-hidden': 'true' }),
    el('span', {}, el('b', {}, 'Part of Te Araroa'), el('small', {}, secs.length === 1 ? `Section ${secs[0].n} · ${kmRange(d, secs[0].from, secs[0].to)}` : `${secs.length} sections`)),
    el('span', { class: 'ta-link-buttons' }, ...secs.slice(0, 3).map((s) => el('button', { class: 'btn', type: 'button', onclick: () => openTeAraroa(ctx, s.slug, { push: true }) }, s.alternative ? s.name : `${s.n}. ${s.name}`))));
}

/** "On Te Araroa" for a hut the trail passes (within 400 m of one of its places). */
function teAraroaHutLink(ctx, point) {
  const d = teAraroa.loaded();
  if (!d || !point) return null;
  const k = Math.cos((point[1] * Math.PI) / 180);
  const near = d.places.map((p) => ({ p, m: Math.hypot((p.point[0] - point[0]) * k, p.point[1] - point[1]) * 111320 })).sort((a, b) => a.m - b.m)[0];
  if (!near || near.m > 400) return null;
  const s = d.bySlug[near.p.section];
  return el('div', { class: 'ta-link' }, el('span', { class: 'ta-link-mark', 'aria-hidden': 'true' }),
    el('span', {}, el('b', {}, 'On Te Araroa'), el('small', {}, [`km ${km(teAraroa.kmIn(d, near.p.km))}`, s && `Section ${s.n}`, near.p.pass && 'Te Araroa Pass'].filter(Boolean).join(' · '))),
    el('span', { class: 'ta-link-buttons' }, s ? el('button', { class: 'btn', type: 'button', onclick: () => openTeAraroa(ctx, s.slug, { at: near.p.km, push: true }) }, s.name) : null));
}

/** A hut, campsite, holiday park or hostel on Te Araroa. */
function placePanel(ctx, d, i) {
  const p = d.places[i];
  return {
    kind: 'te-araroa-place', key: `place-${i}`, title: p?.name || 'Place to stay', subtitle: '', backLabel: p?.name && p.name.length <= 26 ? p.name : 'Back',
    render(body, alive, host) {
      if (!p) return;
      const s = d.bySlug[p.section];
      host.setTitle(p.name, [p.type, p.approx && p.town ? `in ${p.town}` : `km ${km(teAraroa.kmIn(d, p.km))}`, !p.approx && p.off > 150 && `${km(p.off / 1000)} km off the trail`].filter(Boolean).join(' · '));
      if (!p.approx) ctx.marker(p.point);
      const facts = [p.booking ? 'Booking required' : p.from === 'notes' ? '' : 'No booking', p.pass && 'Te Araroa Pass'].filter(Boolean).join(' · ');
      if (facts) body.append(el('p', { class: 'ta-facts' }, facts));
      if (p.facilities) body.append(el('p', {}, p.facilities));
      if (p.address) body.append(el('p', {}, p.address));
      if (p.phone) body.append(el('p', {}, el('a', { href: `tel:${p.phone.replace(/[^\d+]/g, '')}` }, p.phone)));
      const guid = guidOf(p.page);
      if (guid) body.append(alertsBox(guid, p.page));
      body.append(el('div', { class: 'sheet-actions' }, ...[docLink(p.page, 'DOC page ↗'), docLink(p.website, 'Website ↗')].filter(Boolean)));
      if (p.from === 'notes') body.append(el('p', { class: 'fine' }, `From the accommodation notes for ${s?.name || 'this section'} (teararoa.org.nz).`));
      return () => { ctx.marker(null); };
    },
  };
}
/** A place to stay on Te Araroa, tapped on the map. */
export function openTeAraroaPlace(ctx, i, { push = false } = {}) {
  teAraroa.load().then((d) => {
    const p = placePanel(ctx, d, i);
    ctx.sheet.open({ kind: p.kind, title: p.title, backLabel: p.backLabel, render: (body, alive, o) => p.render(body, alive, ctx.sheet, o) }, { push });
  });
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
        const taHut = teAraroaHutLink(ctx, point);
        if (taHut) body.append(taHut);
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
