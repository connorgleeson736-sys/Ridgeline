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
        line.length >= 2 ? el('button', { class: 'btn', type: 'button', onclick: () => ctx.planner.startTemp(nearestOn(line, tapped).at || tapped, name ? `From ${name}` : 'New route') }, 'Start a route here') : null,
        line.length >= 2 ? el('button', { class: 'btn', type: 'button', onclick: () => ctx.planner.fromLine(name || 'Track', line) }, 'Save as route') : null,
      ].filter(Boolean)));
      return () => { controller.abort(); ctx.select(null); ctx.marker(null); ctx.range(null); };
    },
  }, { push });
}

const km = (v) => (v == null ? '' : v.toLocaleString('en-NZ', { maximumFractionDigits: 1 }));
const nobo = (d, v) => (v == null ? null : Math.max(0, d.total - v));

// ---- Te Araroa ----
/** The Trust's current alerts for a section (live), closures first; nothing when none. */
function taAlertsBox(slug) {
  const box = el('section', { class: 'doc-alerts', hidden: true, 'aria-label': 'Te Araroa alerts' });
  teAraroa.alerts(slug).then((list) => {
    if (list && !list.length) return;
    box.hidden = false;
    if (!list) { box.append(el('p', { class: 'doc-alerts-head' }, 'Couldn’t load Te Araroa’s current alerts. ', docLink(teAraroa.ALERTS, 'Check them ↗'))); return; }
    const rank = (a) => (/closure/i.test(a.status) ? 0 : /warning/i.test(a.status) ? 1 : 2);
    list.sort((a, b) => rank(a) - rank(b) || (a.from ?? 0) - (b.from ?? 0));
    box.append(el('p', { class: 'doc-alerts-head' }, list.length === 1 ? 'Trail alert' : `${list.length} trail alerts`));
    list.forEach((a, i) => {
      const where = a.from != null ? (a.to != null && a.to !== a.from ? `km ${km(a.from)}–${km(a.to)}` : `km ${km(a.from)}`) : '';
      const until = a.end ? new Date(a.end) : null;
      const item = el('details', { class: 'doc-alert' },
        el('summary', {}, a.title || 'Alert', el('small', { class: 'alert-meta' }, [a.status, where].filter(Boolean).join(' · '))),
        el('div', { class: 'doc-alert-body' }, safeHtml(a.description), until && !isNaN(until) ? el('p', { class: 'muted' }, `Until ${until.toLocaleDateString('en-NZ', { day: 'numeric', month: 'long', year: 'numeric' })}.`) : null));
      if (i === 0 && list.length === 1) item.open = true;
      box.append(item);
    });
  });
  return box;
}

/** The Trust's time, its first part ("5 days walking, 2–3 days biking" → "5 days walking"). */
const shortTime = (t) => (t || '').split(/[,;(]/)[0].trim();
const stopLine = (r) => [teAraroa.LEVELS[r.level], !teAraroa.onTrail(r) && `${km(r.off / 1000)} km off the trail`, r.bounce && 'boxes'].filter(Boolean).join(' · ');

/** Food for a section: stops in it, and the nearest ones behind and ahead. */
function taFood(ctx, d, s, at) {
  if (s.from == null) return null;
  const here = d.resupply.filter((r) => r.km >= s.from - 0.05 && r.km <= s.to + 0.05);
  const from = at ?? s.from, to = at ?? s.to;
  const behind = teAraroa.foodAround(d, from).behind, ahead = teAraroa.foodAround(d, to).ahead;
  const row = (label, r, dist) => el('li', {}, el('button', { type: 'button', onclick: () => openTeAraroaStop(ctx, d.resupply.indexOf(r), { push: true }) },
    el('span', { class: `food-dot ${r.level}` }),
    el('span', { class: 'hut-name' }, `${label}${r.name}`, el('small', {}, stopLine(r))),
    el('span', { class: 'hut-km' }, dist)));
  const rows = [];
  if (behind && !here.includes(behind)) rows.push(row('Behind: ', behind, `${km(from - behind.km)} km back`));
  for (const r of here) rows.push(row('', r, at != null ? `${r.km >= at ? '+' : '−'}${km(Math.abs(r.km - at))} km` : `km ${km(r.km)}`));
  if (ahead && !here.includes(ahead)) rows.push(row('Next: ', ahead, `${km(ahead.km - to)} km · ${teAraroa.daysText(teAraroa.days(d, to, ahead.km))}`));
  const hitch = d.resupply.filter((r) => !teAraroa.onTrail(r) && r.km >= s.from - 0.05 && r.km <= s.to + 0.05 && !here.includes(r));
  for (const r of hitch) rows.push(row('Hitch: ', r, `km ${km(r.km)}`));
  return rows.length ? el('div', { class: 'huts ta-food' }, el('h3', {}, 'Food'), el('ul', { class: 'hut-list' }, rows)) : null;
}

/** Huts, campsites and holiday parks along a section, with their kilometres. */
function taPlaces(ctx, d, s, at) {
  const list = d.places.filter((p) => p.section === s.slug);
  if (!list.length) return null;
  const line = (p) => [p.type, p.facilities && p.facilities.length < 60 ? p.facilities : '', p.booking ? 'booking required' : '', p.pass ? 'Te Araroa Pass' : ''].filter(Boolean).join(' · ');
  return el('div', { class: 'huts' }, el('h3', {}, 'Places to stay'), el('ul', { class: 'hut-list ta-places' }, list.map((p) => el('li', {},
    el('button', { type: 'button', onclick: () => openTeAraroaPlace(ctx, d.places.indexOf(p), { push: true }) },
      el('span', { class: `hut-dot${/hut/i.test(p.type) ? '' : ' ta-camp'}` }),
      el('span', { class: 'hut-name' }, p.name, el('small', {}, line(p))),
      el('span', { class: 'hut-km' }, at != null && Math.abs(p.km - at) >= 0.1 ? `${p.km > at ? '+' : '−'}${km(Math.abs(p.km - at))} km` : `km ${km(p.km)}`))))));
}

/** An option (bypass, water or ferry leg) as a card: what it is, why, where, how long. */
function optionCard(ctx, d, o, onOpen) {
  const kind = teAraroa.optionKind(o);
  const main = d.mains.filter((m) => o.from != null && Math.min(m.to, o.to) - Math.max(m.from, o.from) > 0.3);
  const instead = o.to > o.from ? o.to - o.from : null;
  const badge = el('span', { class: 'ta-badge', hidden: true });
  teAraroa.status().then((st) => { const v = st?.[o.slug]; if (v && (v.alerts || !/^open$/i.test(v.status))) { badge.hidden = false; badge.textContent = v.alerts ? `${v.alerts} alert${v.alerts > 1 ? 's' : ''}` : v.status; } });
  return el('button', { type: 'button', class: 'ta-option', onclick: () => onOpen(o) },
    el('span', { class: 'ta-option-kind' }, kind.label, badge),
    el('b', {}, o.name),
    el('small', {}, [o.length && `${km(o.length)} km`, shortTime(o.time), instead && `instead of km ${km(o.from)}–${km(o.to)} (${km(instead)} km)`].filter(Boolean).join(' · ')),
    el('small', {}, kind.why, main.length ? ` Replaces part of ${main.map((m) => m.name).join(', ')}.` : ''));
}

/** A section's contents (in its own panel, or inline under its row in the list). */
function renderSection(ctx, d, s, body, alive, { at = null, walk = null, inline = false } = {}) {
  const line = teAraroa.sectionLine(d, s);
  if (line.length >= 2) { ctx.select([line]); ctx.fit(line); }
  const range = s.from != null ? (s.alternative ? `Leaves the trail at km ${km(s.from)}, rejoins at km ${km(s.to)}` : `km ${km(s.from)}–${km(s.to)} southbound · ${km(nobo(d, s.to))}–${km(nobo(d, s.from))} northbound`) : '';
  body.append(chips(range, s.length && `Te Araroa: ${km(s.length)} km`, at != null && `Tapped: km ${km(at)} (NOBO ${km(nobo(d, at))})`));
  if (s.alternative) { const k = teAraroa.optionKind(s); body.append(el('p', { class: 'ta-why' }, el('b', {}, `${k.label}. `), k.why)); }
  const road = teAraroa.roadShare(d, s);
  body.append(el('div', { class: 'stats' },
    statBox('Time', shortTime(s.time) || '–'), statBox('Grade', teAraroa.GRADES[s.grade] || '–'),
    statBox('Type', s.kind === 'multi' ? 'Multi-day' : s.kind === 'day' ? 'Day walk' : '–'), statBox('On roads', road == null ? '–' : `${Math.round(road * 100)}%`)));
  const profile = line.length >= 2 ? profileBlock(ctx, line, alive) : null;
  if (profile) body.append(profile);
  const intro = el('div', { class: 'intro' });
  body.append(intro);
  body.append(taAlertsBox(s.slug));
  const food = taFood(ctx, d, s, at);
  if (food) body.append(food);
  // Options on this section (or, for an option, the sections it replaces), as cards.
  const options = d.sections.filter((o) => o.alternative && o.from != null && s.from != null && o !== s && (s.alternative ? Math.abs(o.from - s.from) < 1 : o.from < s.to && o.to > s.from - 0.1));
  const replaces = s.alternative ? d.mains.filter((m) => Math.min(m.to, s.to) - Math.max(m.from, s.from) > 0.3) : [];
  if (replaces.length) body.append(el('div', { class: 'ta-options' }, el('h3', {}, 'The trail it replaces'), ...replaces.map((m) => el('button', { class: 'btn', type: 'button', onclick: () => openTeAraroa(ctx, m.slug) }, `${m.n}. ${m.name}`))));
  if (options.length) body.append(el('div', { class: 'ta-options' }, el('h3', {}, s.alternative ? 'Other options here' : 'Bypasses and options'), ...options.map((o) => optionCard(ctx, d, o, (x) => openTeAraroa(ctx, x.slug, { push: true })))));
  const places = taPlaces(ctx, d, s, at);
  if (places) body.append(places);
  if (profile && !s.alternative && s.to > s.from) {
    const total = s.to - s.from;
    const marks = d.places.filter((p) => p.section === s.slug && /hut/i.test(p.type)).map((p) => [((p.km - s.from) / total) * length(line), p.name]);
    if (marks.length) profile.chart.setMarkers(marks);
  }
  const parts = el('div', { class: 'doc-sections' });
  body.append(parts);
  teAraroa.notes(s.slug).then((n) => {
    if (!alive() || !n) return;
    if (n.intro) intro.append(safeHtml(n.intro));
    for (const [title, html] of [['The walk', n.walk], ['Getting there', n.access], ['Transport', n.transport], ['Accommodation', n.accommodation], ['Food and supplies', n.food], ['Track conditions and hazards', n.conditions]]) {
      if (!html || !html.replace(/<[^>]+>|&nbsp;|\s/g, '')) continue;
      const content = el('div', { class: 'doc-section-body' });
      const item = el('details', { class: 'doc-section', ontoggle: () => { if (item.open && !content.childElementCount) content.append(safeHtml(html)); } }, el('summary', {}, title), content);
      parts.append(item);
    }
  });
  const index = d.mains.indexOf(s), prev = d.mains[index - 1], next = d.mains[index + 1];
  if (!s.alternative && !inline) body.append(el('div', { class: 'ta-steps' },
    el('button', { class: 'btn', type: 'button', disabled: !prev, title: prev?.name || '', onclick: () => openTeAraroa(ctx, prev.slug) }, '← Previous'),
    el('button', { class: 'btn', type: 'button', onclick: () => openTeAraroa(ctx, null) }, 'All sections'),
    el('button', { class: 'btn', type: 'button', disabled: !next, title: next?.name || '', onclick: () => openTeAraroa(ctx, next.slug) }, 'Next →')));
  const walkName = walk && ctx.overlays.infos()[walk]?.name;
  body.append(el('div', { class: 'sheet-actions' }, ...[
    inline ? el('button', { class: 'btn', type: 'button', onclick: () => openTeAraroa(ctx, s.slug, { push: true }) }, 'Open on its own') : null,
    line.length >= 2 ? el('button', { class: 'btn', type: 'button', onclick: () => ctx.planner.fromLine(`Te Araroa: ${s.name}`, line) }, 'Save as route') : null,
    walkName ? el('button', { class: 'btn', type: 'button', onclick: () => ctx.openWalk(walk) }, `DOC: ${walkName}`) : null,
    docLink(teAraroa.sectionPage(s.slug), 'Section on Te Araroa ↗'),
  ].filter(Boolean)));
  if (!inline) body.append(el('p', { class: 'fine' }, `From Te Araroa Trust’s ${d.season} trail data and section notes (teararoa.org.nz, CC BY 4.0); food stops from walkers’ resupply guides. Alerts are live from Te Araroa; check them, and the Trail App, before you go.`));
}

/** One section of Te Araroa (or, with no section, the whole trail). `at`: the km tapped. */
export function openTeAraroa(ctx, slug = null, { push = false, at = null, walk = null, tab = null } = {}) {
  ctx.sheet.open({
    kind: 'te-araroa', title: 'Te Araroa', subtitle: 'Trail',
    render(body, alive) {
      body.append(busy());
      teAraroa.load().then((d) => {
        if (!alive()) return;
        body.replaceChildren();
        const s = slug && d.bySlug[slug];
        if (!s) return overview(ctx, d, body, alive, tab);
        ctx.sheet.setTitle(s.name, s.alternative ? `Te Araroa · ${teAraroa.optionKind(s).label} · ${s.region || (s.island === 'SI' ? 'South Island' : 'North Island')}` : `Te Araroa · Section ${s.n} of ${d.sections.length} · ${s.region}`);
        renderSection(ctx, d, s, body, alive, { at, walk });
      }).catch(() => { if (alive()) body.replaceChildren(failed('Te Araroa could not be loaded. Check your connection.')); });
      return () => { ctx.select(null); ctx.marker(null); ctx.range(null); };
    },
  }, { push });
}

/** The whole trail: tabs for its sections (opening inline), its bypasses and options,
 * and its food carries. */
function overview(ctx, d, body, alive, tab) {
  ctx.sheet.setTitle('Te Araroa', `Cape Reinga to Bluff · ${km(d.total)} km · ${d.mains.length} sections`);
  const wholeView = () => { const all = d.pieces.map((p) => p.line); ctx.select(all); ctx.fit(all.flat()); };
  wholeView();
  const north = d.mains.filter((s) => s.island === 'NI'), south = d.mains.filter((s) => s.island === 'SI');
  const sum = (list) => list.reduce((t, s) => t + (s.to - s.from), 0);
  body.append(el('div', { class: 'stats' }, statBox('Distance', `${km(d.total)} km`), statBox('North Island', `${Math.round(sum(north))} km`), statBox('South Island', `${Math.round(sum(south))} km`), statBox('Season', d.season || '–')));
  const counts = el('p', { class: 'muted ta-status-line' });
  body.append(counts);
  const panes = { sections: el('div', { class: 'ta-pane' }), options: el('div', { class: 'ta-pane' }), food: el('div', { class: 'ta-pane' }) };
  const tabs = el('div', { class: 'ta-tabs', role: 'tablist' });
  const show = (name) => {
    for (const [k, pane] of Object.entries(panes)) pane.hidden = k !== name;
    for (const b of tabs.children) b.setAttribute('aria-selected', String(b.dataset.tab === name));
  };
  for (const [k, label] of [['sections', 'Sections'], ['options', 'Bypasses & options'], ['food', 'Food carries']]) tabs.append(el('button', { type: 'button', role: 'tab', 'data-tab': k, onclick: () => show(k) }, label));
  body.append(tabs, ...Object.values(panes));

  // Sections: a row each, opening inline under itself (one at a time).
  const badges = new Map();
  let open = null;
  const toggle = (s, row, slot) => {
    if (open) { open.slot.replaceChildren(); open.slot.hidden = true; open.row.classList.remove('open'); open.alive.v = false; }
    if (open?.s === s) { open = null; wholeView(); return; }
    const flag = { v: true };
    slot.hidden = false;
    row.classList.add('open');
    renderSection(ctx, d, s, slot, () => alive() && flag.v, { inline: true });
    open = { s, row, slot, alive: flag };
    requestAnimationFrame(() => row.scrollIntoView({ block: 'nearest', behavior: 'smooth' }));
  };
  const rowFor = (s, pane) => {
    const badge = el('span', { class: 'ta-badge', hidden: true });
    const slot = el('div', { class: 'ta-inline', hidden: true });
    const row = el('button', { type: 'button', class: 'ta-row', 'aria-expanded': 'false', onclick: () => { toggle(s, row, slot); row.setAttribute('aria-expanded', String(open?.s === s)); } },
      el('span', { class: 'ta-n' }, s.alternative ? '↳' : String(s.n)),
      el('span', { class: 'ta-name' }, s.name, el('small', {}, [s.length && `${km(s.length)} km`, shortTime(s.time), teAraroa.GRADES[s.grade]].filter(Boolean).join(' · '))),
      el('span', { class: 'ta-km' }, s.from != null ? `km ${Math.round(s.from)}` : ''), badge);
    badges.set(s.slug, badge);
    pane.append(row, slot);
  };
  let region = null;
  for (const s of d.mains) {
    if (s.region !== region) { region = s.region; panes.sections.append(el('h3', {}, region)); }
    rowFor(s, panes.sections);
  }
  // Bypasses and options: cards by island, each opening inline.
  panes.options.append(el('p', { class: 'muted' }, 'Ways around parts of the trail: flood bypasses for rivers too high to cross, road bypasses, and the water and ferry legs. Dashed red on the map.'));
  for (const island of ['NI', 'SI']) {
    const list = d.sections.filter((o) => o.alternative && o.island === island);
    if (!list.length) continue;
    panes.options.append(el('h3', {}, island === 'NI' ? 'North Island' : 'South Island'));
    for (const o of list) {
      const slot = el('div', { class: 'ta-inline', hidden: true });
      const card = optionCard(ctx, d, o, () => toggle(o, card, slot));
      panes.options.append(card, slot);
    }
  }
  // Food carries: from each stop on the trail to the next.
  const carries = teAraroa.carries(d);
  panes.food.append(el('p', { class: 'muted' }, 'How far you carry food between places to buy it, southbound. Days are rough, from the Trust’s section times. Stops a hitch away are listed under the carry they fall in.'),
    el('p', { class: 'food-key' }, el('span', { class: 'food-dot full' }), 'Supermarket ', el('span', { class: 'food-dot limited' }), 'Small store ', el('span', { class: 'food-dot box' }), 'Send a box'));
  const stopBtn = (r) => el('button', { type: 'button', class: 'food-stop', onclick: () => openTeAraroaStop(ctx, d.resupply.indexOf(r), { push: true }) }, el('span', { class: `food-dot ${r.level}` }), r.name, r.bounce ? el('small', {}, ' · boxes') : null);
  panes.food.append(el('ol', { class: 'food-carries' }, carries.map((c) => el('li', { class: c.km >= 100 ? 'long' : '' },
    el('div', { class: 'food-leg' }, stopBtn(c.from), el('span', { class: 'food-arrow' }, '→'), stopBtn(c.to)),
    el('small', {}, `km ${Math.round(c.from.km)}–${Math.round(c.to.km)} · ${km(c.km)} km · ${teAraroa.daysText(c.days)}`),
    c.hitch.length ? el('small', { class: 'food-hitch' }, 'Hitch: ', ...c.hitch.flatMap((r, i) => [i ? ', ' : '', el('button', { type: 'button', class: 'link', onclick: () => openTeAraroaStop(ctx, d.resupply.indexOf(r), { push: true }) }, `${r.name} (${km(r.off / 1000)} km)`)])) : null))));
  show(tab || 'sections');

  teAraroa.status().then((st) => {
    if (!alive() || !st) return;
    let closed = 0, alerted = 0;
    for (const [slug, v] of Object.entries(st)) {
      const badge = badges.get(slug);
      if (!badge) continue;
      const shut = /clos/i.test(v.status), warn = !/^open$/i.test(v.status || 'open');
      if (shut) closed++;
      if (v.alerts) alerted++;
      if (v.alerts || warn) { badge.hidden = false; badge.className = `ta-badge${shut ? ' closed' : ''}`; badge.textContent = shut ? 'Closed' : v.alerts ? `${v.alerts} alert${v.alerts > 1 ? 's' : ''}` : v.status; }
    }
    counts.textContent = alerted || closed ? `Live from Te Araroa: ${alerted} section${alerted === 1 ? '' : 's'} with alerts${closed ? `, ${closed} closed` : ''}.` : 'Live from Te Araroa: no section alerts right now.';
  });
  body.append(el('div', { class: 'sheet-actions' }, ...[docLink(teAraroa.SITE, 'Te Araroa ↗'), docLink(teAraroa.NOTES, 'Trail notes & maps ↗'), docLink(teAraroa.ALERTS, 'Trail alerts ↗')].filter(Boolean)));
  body.append(el('p', { class: 'fine' }, `From Te Araroa Trust’s ${d.season} trail data (teararoa.org.nz, CC BY 4.0). Food stops from walkers’ resupply guides and the Trust’s notes: shops and hours change, so check before relying on one.`));
}

/** A place to buy food on Te Araroa: what is there, and the carries either side. */
export function openTeAraroaStop(ctx, i, { push = false } = {}) {
  ctx.sheet.open({
    kind: 'te-araroa-stop', title: 'Te Araroa', subtitle: 'Food',
    render(body, alive) {
      teAraroa.load().then((d) => {
        if (!alive()) return;
        const r = d.resupply[i];
        if (!r) return;
        ctx.sheet.setTitle(r.name, `Te Araroa food · ${teAraroa.LEVELS[r.level] || ''}`);
        ctx.marker(r.point);
        ctx.flyTo?.(r.point, 12);
        body.append(chips(`km ${km(r.km)} (NOBO ${km(nobo(d, r.km))})`, teAraroa.onTrail(r) ? 'On the trail' : `${km(r.off / 1000)} km off the trail: a hitch or detour`, r.bounce && 'Walkers send boxes here'));
        if (r.note) body.append(el('p', { class: 'intro' }, r.note));
        if (r.shops?.length) body.append(el('div', { class: 'huts' }, el('h3', {}, 'From the Trust’s notes'), el('ul', { class: 'plain-list' }, r.shops.map((t) => el('li', {}, t)))));
        const on = d.resupply.filter(teAraroa.onTrail);
        const prev = [...on].reverse().find((x) => x.km < r.km - 0.05), next = on.find((x) => x.km > r.km + 0.05);
        const leg = (label, x, dist) => el('li', {}, el('button', { type: 'button', onclick: () => openTeAraroaStop(ctx, d.resupply.indexOf(x)) }, el('span', { class: `food-dot ${x.level}` }), el('span', { class: 'hut-name' }, `${label}${x.name}`, el('small', {}, stopLine(x))), el('span', { class: 'hut-km' }, dist)));
        body.append(el('div', { class: 'huts' }, el('h3', {}, 'Carries from here'), el('ul', { class: 'hut-list' }, [
          next && leg('Next south: ', next, `${km(next.km - r.km)} km · ${teAraroa.daysText(teAraroa.days(d, r.km, next.km))}`),
          prev && leg('Next north: ', prev, `${km(r.km - prev.km)} km · ${teAraroa.daysText(teAraroa.days(d, prev.km, r.km))}`),
        ].filter(Boolean))));
        const s = d.mains.find((m) => r.km >= m.from - 0.05 && r.km <= m.to + 0.05);
        body.append(el('div', { class: 'sheet-actions' }, ...[
          s ? el('button', { class: 'btn', type: 'button', onclick: () => openTeAraroa(ctx, s.slug, { at: r.km, push: true }) }, `Section: ${s.name}`) : null,
          el('button', { class: 'btn', type: 'button', onclick: () => openTeAraroa(ctx, null, { tab: 'food', push: true }) }, 'All food carries'),
        ].filter(Boolean)));
        body.append(el('p', { class: 'fine' }, 'From walkers’ resupply guides and Te Araroa Trust’s section notes (teararoa.org.nz, CC BY 4.0). Shops, hours and box arrangements change: check before relying on them.'));
      });
      return () => { ctx.marker(null); };
    },
  }, { push });
}

/** "Part of Te Araroa": the sections a walk or track runs along, linked. */
export function teAraroaLinks(ctx, line) {
  const d = teAraroa.loaded();
  if (!d || !line || line.length < 2) return null;
  const secs = teAraroa.sectionsAlong(d, line);
  if (!secs.length) return null;
  return el('div', { class: 'ta-link' }, el('span', { class: 'ta-link-mark', 'aria-hidden': 'true' }),
    el('span', {}, el('b', {}, 'Part of Te Araroa'), el('small', {}, secs.length === 1 ? `Section ${secs[0].n} · km ${km(secs[0].from)}–${km(secs[0].to)}` : `${secs.length} sections`)),
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
    el('span', {}, el('b', {}, 'On Te Araroa'), el('small', {}, [`km ${km(near.p.km)}`, s && `Section ${s.n}`, near.p.pass && 'Te Araroa Pass'].filter(Boolean).join(' · '))),
    el('span', { class: 'ta-link-buttons' }, s ? el('button', { class: 'btn', type: 'button', onclick: () => openTeAraroa(ctx, s.slug, { at: near.p.km, push: true }) }, s.name) : null));
}

/** A hut, campsite or holiday park on Te Araroa. */
export function openTeAraroaPlace(ctx, i, { push = false } = {}) {
  ctx.sheet.open({
    kind: 'te-araroa-place', title: 'Te Araroa', subtitle: 'Place to stay',
    render(body, alive) {
      teAraroa.load().then((d) => {
        if (!alive()) return;
        const p = d.places[i];
        if (!p) return;
        const s = d.bySlug[p.section];
        ctx.sheet.setTitle(p.name, [p.type, p.category].filter(Boolean).join(' · '));
        ctx.marker(p.point);
        body.append(chips(`Te Araroa km ${km(p.km)} (NOBO ${km(nobo(d, p.km))})`, p.off > 150 && `${km(p.off / 1000)} km off the trail`, p.booking && 'Booking required', p.pass && 'Te Araroa Pass'));
        if (p.facilities) body.append(el('p', {}, el('b', {}, p.type === 'Holiday park' ? 'Offers: ' : 'Facilities: '), p.facilities));
        if (p.address) body.append(el('p', {}, el('b', {}, 'Address: '), p.address));
        if (p.phone) body.append(el('p', {}, el('b', {}, 'Phone: '), el('a', { href: `tel:${p.phone.replace(/[^\d+]/g, '')}` }, p.phone)));
        const guid = guidOf(p.page);
        if (guid) body.append(alertsBox(guid, p.page));
        const { behind, ahead } = teAraroa.foodAround(d, p.km);
        if (ahead || behind) body.append(el('p', { class: 'muted' }, [ahead && `Next food south: ${ahead.name} (${km(ahead.km - p.km)} km)`, behind && `north: ${behind.name} (${km(p.km - behind.km)} km)`].filter(Boolean).join(' · ')));
        body.append(el('div', { class: 'sheet-actions' }, ...[
          s ? el('button', { class: 'btn', type: 'button', onclick: () => openTeAraroa(ctx, s.slug, { at: p.km }) }, `Section: ${s.name}`) : null,
          docLink(p.page, 'DOC page ↗'), docLink(p.website, 'Website ↗'),
        ].filter(Boolean)));
        body.append(el('p', { class: 'fine' }, 'From Te Araroa Trust’s trail data (teararoa.org.nz, CC BY 4.0). Check with the provider before you rely on it.'));
      });
      return () => { ctx.marker(null); };
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
