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

/** One section of Te Araroa (or, with no section, the whole trail and its sections).
 * `at`: the kilometre tapped, if any. */
export function openTeAraroa(ctx, slug = null, { push = false, at = null, walk = null } = {}) {
  ctx.sheet.open({
    kind: 'te-araroa', title: 'Te Araroa', subtitle: 'Trail',
    render(body, alive) {
      body.append(busy());
      teAraroa.load().then((d) => {
        if (!alive()) return;
        body.replaceChildren();
        const s = slug && d.bySlug[slug];
        if (!s) return overview(ctx, d, body, alive);
        const index = d.mains.indexOf(s);
        ctx.sheet.setTitle(s.name, s.alternative ? `Te Araroa option · ${s.region || (s.island === 'SI' ? 'South Island' : 'North Island')}` : `Te Araroa · Section ${s.n} of ${d.sections.length} · ${s.region}`);
        const line = teAraroa.sectionLine(d, s);
        if (line.length >= 2) { ctx.select([line]); ctx.fit(line); }
        // Where it is on the trail.
        const range = s.from != null ? (s.alternative ? `Leaves the trail at km ${km(s.from)}, rejoins at km ${km(s.to)}` : `km ${km(s.from)}–${km(s.to)} southbound · ${km(nobo(d, s.to))}–${km(nobo(d, s.from))} northbound`) : '';
        body.append(chips(range, s.length && `Te Araroa: ${km(s.length)} km`, at != null && `Tapped: km ${km(at)} (NOBO ${km(nobo(d, at))})`));
        const road = teAraroa.roadShare(d, s);
        body.append(el('div', { class: 'stats' },
          statBox('Time', s.time || '–'), statBox('Grade', teAraroa.GRADES[s.grade] || '–'),
          statBox('Type', s.kind === 'multi' ? 'Multi-day' : s.kind === 'day' ? 'Day walk' : '–'), statBox('On roads', road == null ? '–' : `${Math.round(road * 100)}%`)));
        const profile = line.length >= 2 ? profileBlock(ctx, line, alive) : null;
        if (profile) body.append(profile);
        const intro = el('div', { class: 'intro' });
        body.append(intro);
        // The Trust's current alerts, after the profile and summary (as DOC's on walks).
        body.append(taAlertsBox(s.slug));
        // The options beside this section, and the section an option replaces.
        const options = d.sections.filter((o) => o.alternative && o.from != null && s.from != null && o !== s && (s.alternative ? Math.abs(o.from - s.from) < 1 : o.from < s.to && o.to > s.from - 0.1));
        const replaces = s.alternative ? d.mains.filter((m) => m.from < s.to + 0.1 && m.to > s.from - 0.1) : [];
        if (options.length || replaces.length) body.append(el('div', { class: 'ta-options' }, el('h3', {}, s.alternative ? 'Instead of' : 'Options on this section'),
          ...[...replaces, ...options].map((o) => el('button', { class: 'btn', type: 'button', onclick: () => openTeAraroa(ctx, o.slug) }, o.name))));
        const places = taPlaces(ctx, d, s, at);
        if (places) body.append(places);
        if (profile) {
          const total = s.to - s.from;
          const marks = d.places.filter((p) => p.section === s.slug && /hut/i.test(p.type) && !s.alternative && total > 0).map((p) => [((p.km - s.from) / total) * length(line), p.name]);
          if (marks.length) profile.chart.setMarkers(marks);
        }
        // The Trust's notes for the section, as collapsible parts.
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
        // Along the trail.
        const prev = d.mains[index - 1], next = d.mains[index + 1];
        if (!s.alternative) body.append(el('div', { class: 'ta-steps' },
          el('button', { class: 'btn', type: 'button', disabled: !prev, title: prev?.name || '', onclick: () => openTeAraroa(ctx, prev.slug) }, '← Previous'),
          el('button', { class: 'btn', type: 'button', onclick: () => openTeAraroa(ctx, null) }, 'All sections'),
          el('button', { class: 'btn', type: 'button', disabled: !next, title: next?.name || '', onclick: () => openTeAraroa(ctx, next.slug) }, 'Next →')));
        const walkName = walk && ctx.overlays.infos()[walk]?.name;
        body.append(el('div', { class: 'sheet-actions' }, ...[
          line.length >= 2 ? el('button', { class: 'btn', type: 'button', onclick: () => ctx.planner.fromLine(`Te Araroa: ${s.name}`, line) }, 'Save as route') : null,
          walkName ? el('button', { class: 'btn', type: 'button', onclick: () => ctx.openWalk(walk) }, `DOC: ${walkName}`) : null,
          docLink(teAraroa.sectionPage(s.slug), 'Section on Te Araroa ↗'),
        ].filter(Boolean)));
        body.append(el('p', { class: 'fine' }, `From Te Araroa Trust’s ${d.season} trail data and section notes (teararoa.org.nz, CC BY 4.0). Alerts are live from Te Araroa; check them, and the Trail App, before you go.`));
      }).catch(() => { if (alive()) body.replaceChildren(failed('Te Araroa could not be loaded. Check your connection.')); });
      return () => { ctx.select(null); ctx.marker(null); ctx.range(null); };
    },
  }, { push });
}

/** The whole trail: its sections by region, with live status. */
function overview(ctx, d, body, alive) {
  ctx.sheet.setTitle('Te Araroa', `Cape Reinga to Bluff · ${km(d.total)} km · ${d.sections.length} sections`);
  const all = d.pieces.map((p) => p.line);
  ctx.select(all);
  ctx.fit(all.flat());
  const north = d.mains.filter((s) => s.island === 'NI'), south = d.mains.filter((s) => s.island === 'SI');
  const sum = (list) => list.reduce((t, s) => t + (s.to - s.from), 0);
  body.append(el('div', { class: 'stats' }, statBox('Distance', `${km(d.total)} km`), statBox('North Island', `${Math.round(sum(north))} km`), statBox('South Island', `${Math.round(sum(south))} km`), statBox('Season', d.season || '–')));
  body.append(el('p', { class: 'intro' }, 'Te Araroa, the Long Pathway, runs the length of Aotearoa New Zealand. Pick a section for its notes, profile, places to stay and current alerts, or tap the red line on the map. Dashed red lines are bypasses and water or ferry options.'));
  const counts = el('p', { class: 'muted ta-status-line' });
  body.append(counts);
  const rows = new Map();
  const list = el('div', { class: 'ta-sections' });
  let region = null;
  for (const s of d.sections) {
    if (!s.alternative && s.region !== region) { region = s.region; list.append(el('h3', {}, region)); }
    const badge = el('span', { class: 'ta-badge', hidden: true });
    const row = el('button', { type: 'button', class: `ta-row${s.alternative ? ' option' : ''}`, onclick: () => openTeAraroa(ctx, s.slug, { push: true }) },
      el('span', { class: 'ta-n' }, s.alternative ? '↳' : String(s.n)),
      el('span', { class: 'ta-name' }, s.name, el('small', {}, [s.length && `${km(s.length)} km`, s.time, teAraroa.GRADES[s.grade]].filter(Boolean).join(' · '))),
      el('span', { class: 'ta-km' }, s.from != null ? `km ${Math.round(s.from)}` : ''), badge);
    rows.set(s.slug, badge);
    list.append(row);
  }
  body.append(list);
  teAraroa.status().then((st) => {
    if (!alive() || !st) return;
    let closed = 0, alerted = 0;
    for (const [slug, v] of Object.entries(st)) {
      const badge = rows.get(slug);
      if (!badge) continue;
      const shut = /clos/i.test(v.status), warn = !/^open$/i.test(v.status || 'open');
      if (shut) closed++;
      if (v.alerts) alerted++;
      if (v.alerts || warn) { badge.hidden = false; badge.className = `ta-badge${shut ? ' closed' : ''}`; badge.textContent = shut ? 'Closed' : v.alerts ? `${v.alerts} alert${v.alerts > 1 ? 's' : ''}` : v.status; }
    }
    counts.textContent = alerted || closed ? `Live from Te Araroa: ${alerted} section${alerted === 1 ? '' : 's'} with alerts${closed ? `, ${closed} closed` : ''}.` : 'Live from Te Araroa: no section alerts right now.';
  });
  body.append(el('div', { class: 'sheet-actions' }, ...[docLink(teAraroa.SITE, 'Te Araroa ↗'), docLink(teAraroa.NOTES, 'Trail notes & maps ↗'), docLink(teAraroa.ALERTS, 'Trail alerts ↗')].filter(Boolean)));
  body.append(el('p', { class: 'fine' }, `From Te Araroa Trust’s ${d.season} trail data (teararoa.org.nz, CC BY 4.0).`));
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
