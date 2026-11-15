// What a tap opens: a DOC walk's summary, a track, a hut or hunting areas.
// The walk summary follows the app's: DOC's own line walked as DOC describes it
// (DocRoute), its elevation profile and the huts along it.
import { el, icon, distance, height, escape } from './ui.js';
import { Chart, stats } from './chart.js';
import { route as docRoute, stated } from '../core/docroute.js';
import { stops, matchHut, guidOf } from '../core/walks.js';
import { length, bounds, chain } from '../core/geo.js';
import * as doc from './doc.js';
import { data as worker } from './workers.js';

let facts = null;
const walkFacts = () => facts || (facts = fetch('/data/walks.json').then((r) => (r.ok ? r.json() : {})).catch(() => ({})));

const DOC_NOTE = 'Track information from the Department of Conservation. Check DOC for alerts and conditions before you go.';
/** DOC writes bunks as "8 each", "20", "4 (bunks)"… */
const bunks = (b) => { const n = /^\d+/.exec(b || '')?.[0]; return n ? `${n} bunks` : ''; };
const statBox = (label, value) => el('div', { class: 'stat' }, el('span', {}, label), el('b', {}, value));

/** Stats, elevation chart and section measuring for a line, filled in as heights arrive. */
export function profileBlock(ctx, line, alive, { onReady } = {}) {
  const grid = el('div', { class: 'stats' }, statBox('Distance', distance(length(line))), statBox('Ascent', '…'), statBox('Descent', '…'), statBox('Highest', '…'));
  const canvas = el('canvas', { class: 'chart', role: 'img', 'aria-label': 'Elevation profile' });
  const section = el('div', { class: 'section-stats', hidden: true });
  const chart = new Chart(canvas, {
    onPoint: (s) => ctx.marker(s?.p || null),
    onSection: (st, pts) => {
      ctx.section(pts && pts.length > 1 ? pts : null);
      section.hidden = !st;
      if (st) section.replaceChildren(el('span', { class: 'ab' }, 'A → B'), statBox('Distance', distance(st.distance)), statBox('Ascent', height(st.ascent)), statBox('Descent', height(st.descent)), el('button', { class: 'link-button', type: 'button', onclick: () => chart.clearSection() }, 'Clear'));
    },
  });
  worker.call({ type: 'profile', points: line, max: 700 }).then((samples) => {
    if (!alive() || !samples) return;
    chart.set(samples);
    const s = stats(samples);
    grid.replaceChildren(statBox('Distance', distance(length(line))), statBox('Ascent', height(s.ascent)), statBox('Descent', height(s.descent)), statBox('Highest', height(s.high)));
    onReady?.(samples, s);
  }).catch(() => { if (alive()) chart.set([{ d: 0, h: null }]); });
  return el('div', { class: 'profile' }, grid, el('div', { class: 'chart-wrap' }, canvas), section, el('p', { class: 'hint' }, 'Drag across the profile to measure a section.'));
}

function photo(src, alt) {
  if (!src || !/^https:\/\//.test(src)) return null;
  return el('img', { class: 'photo', src, alt, loading: 'lazy', decoding: 'async', referrerpolicy: 'no-referrer', onerror: (e) => e.target.remove() });
}
const chips = (...items) => el('div', { class: 'chips' }, items.filter(Boolean).map((t) => el('span', { class: 'chip' }, t)));
const docLink = (href, text = 'DOC page ↗') => (href && /^https:\/\//.test(href) ? el('a', { class: 'doc-page', href, target: '_blank', rel: 'noopener' }, text) : null);
const busy = (text = 'Loading…') => el('p', { class: 'loading' }, el('span', { class: 'spinner' }), text);
const failed = (text) => el('p', { class: 'notice' }, text);

/** A DOC walk by its line id. */
export function openWalk(ctx, guid, { push = false, near = null } = {}) {
  ctx.sheet.open({
    kind: 'walk', title: ctx.overlays.infos()[guid]?.name || 'Track', subtitle: 'DOC walk',
    render(body, alive) {
      const controller = new AbortController();
      body.append(busy());
      (async () => {
        const [info, paths, f] = await Promise.all([doc.walkByGuid(guid, controller.signal), doc.walkLine(guid, controller.signal), walkFacts()]);
        if (!alive()) return;
        const fact = f[guid] || [];
        const target = stated(fact[0] || '');
        const line = paths.length ? docRoute(paths, target) : [];
        body.replaceChildren();
        const name = info?.name || ctx.overlays.infos()[guid]?.name || 'Track';
        ctx.sheet.setTitle(name, [info?.difficulty, fact[1] || info?.time].filter(Boolean).join(' · ') || 'DOC walk');
        if (line.length >= 2) { ctx.select([line]); ctx.fit(line); }
        const img = photo(info?.thumbnail, name);
        if (img) body.append(img);
        body.append(chips(fact[0] && `DOC: ${fact[0]}`, (fact[1] || info?.time) && `Time: ${fact[1] || info.time}`, fact[2] === 'loop' ? 'Loop' : fact[2] === 'one_way' ? 'One way' : null, fact[3] && fact[3].split('.')[0]));
        if (info?.alerts && /yes|true|1/i.test(info.alerts)) body.append(el('p', { class: 'alert' }, 'DOC has alerts for this track. ', docLink(info.page, 'Read them on DOC ↗')));
        if (line.length >= 2) {
          body.append(profileBlock(ctx, line, alive));
          const hutsBox = el('div', { class: 'huts' });
          body.append(hutsBox);
          doc.hutsIn(bounds(line, 500), controller.signal).then((huts) => {
            if (!alive()) return;
            const along = stops(line, huts);
            if (!along.length) return;
            hutsBox.append(el('h3', {}, 'Huts along the way'), el('ul', { class: 'hut-list' }, along.map((s) => el('li', {}, el('button', { type: 'button', onclick: () => openHut(ctx, s.hut.name, s.hut.point, { push: true }) }, el('span', { class: 'hut-dot' }), el('span', { class: 'hut-name' }, s.hut.name, el('small', {}, [s.hut.category, bunks(s.hut.bunks)].filter(Boolean).join(' · '))), el('span', { class: 'hut-km' }, distance(s.along)))))));
          }).catch(() => {});
        } else body.append(failed('DOC has no line for this walk.'));
        if (info?.introduction) body.append(el('p', { class: 'intro' }, info.introduction));
        const actions = el('div', { class: 'sheet-actions' });
        if (line.length >= 2) actions.append(el('button', { class: 'btn', type: 'button', onclick: () => ctx.planner.fromLine(name, line) }, 'Save as route'));
        const link = docLink(info?.page || ctx.overlays.infos()[guid]?.page);
        if (link) actions.append(link);
        body.append(actions, el('p', { class: 'fine' }, DOC_NOTE));
      })().catch((e) => { if (alive() && e.name !== 'AbortError') body.replaceChildren(failed('DOC’s track information could not be loaded. Check your connection and try again.')); });
      return () => { controller.abort(); ctx.select(null); ctx.marker(null); ctx.section(null); };
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
      return () => { controller.abort(); ctx.select(null); ctx.marker(null); ctx.section(null); };
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
        const img = photo(page?.thumbnail, title);
        if (img) body.append(img);
        if (asset?.status && !/^open$/i.test(asset.status)) body.append(el('p', { class: 'alert' }, `DOC lists this hut as ${asset.status.toLowerCase()}.`));
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
