// Planning a route, as in the app: tap to add points; a tap near a track is
// reached along the tracks (and walkable roads, fording rivers within reason),
// anywhere else the route goes straight. Points lock onto huts, slide along the
// tracks when dragged, and come off when pulled away. Routes are saved in this
// browser and shared as links.
import { el, icon, distance, height, store, toast, metresPerPixel } from './ui.js';
import { Chart, stats } from './chart.js';
import { chartEvents } from './details.js';
import { meters, length, nearestOn, bounds } from '../core/geo.js';
import { encodeRoute, decodeRoute } from '../core/share.js';
import { data as worker, search } from './workers.js';
import { dataUrl } from './data-url.js';

// Taps reach this far: a finger's width on touch screens (the app's 22 and 24 dp),
// less with a mouse, which points precisely.
const coarse = matchMedia('(pointer: coarse)').matches;
const TAP_PX = coarse ? 22 : 14, HUT_PX = coarse ? 24 : 12;
const MAX_ROUTES = 200;
const reachAt = (map, p) => Math.min(150, Math.max(10, TAP_PX * metresPerPixel(p[1], map.getZoom())));
const uid = () => Math.random().toString(36).slice(2, 10) + Date.now().toString(36);

/** A route's legs → its points and which of them were tapped. */
function flatten(legs) {
  const points = [], waypoints = [];
  legs.forEach((leg, i) => {
    if (i === 0) { points.push(leg.p); waypoints.push(0); return; }
    for (const q of leg.path) points.push(q);
    waypoints.push(points.length - 1);
  });
  return { points, waypoints };
}
function unflatten(points, waypoints) {
  const legs = [{ p: points[0], path: [] }];
  for (let k = 1; k < waypoints.length; k++) legs.push({ p: points[waypoints[k]], path: points.slice(waypoints[k - 1] + 1, waypoints[k] + 1) });
  return legs;
}

export function attach(ctx) {
  const { map, sheet } = ctx;
  let route = null;            // {id, name, legs, saved}
  let drawing = false, selected = -1;
  let history = [];
  let queue = Promise.resolve(), pending = 0, pendingLine = null;
  let profileTimer = 0, chart = null, statsBox = null, profileToken = 0;

  const saved = () => store.get('routes', []);
  const persist = () => {
    if (!route || route.legs.length < 2) return;
    const { points, waypoints } = flatten(route.legs);
    const list = saved().filter((r) => r.id !== route.id);
    list.unshift({ id: route.id, name: route.name, points, waypoints, updated: Date.now() });
    if (!store.set('routes', list.slice(0, MAX_ROUTES))) toast('This browser would not save the route. Share it as a link instead.');
    route.saved = true;
  };
  const snapshot = () => { history.push(JSON.stringify(route.legs)); if (history.length > 60) history.shift(); };

  // ---- Drawing on the map ----
  function render() {
    const legs = route?.legs || [];
    const { points } = flatten(legs.length ? legs : [{ p: [0, 0], path: [] }]);
    const lines = [];
    if (legs.length >= 2) lines.push({ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: points } });
    if (pendingLine) lines.push({ type: 'Feature', properties: { pending: true }, geometry: { type: 'LineString', coordinates: pendingLine } });
    map.getSource('route')?.setData({ type: 'FeatureCollection', features: lines });
    const show = drawing || legs.length < 2;
    map.getSource('waypoints')?.setData({ type: 'FeatureCollection', features: legs.map((l, i) => ({ type: 'Feature', id: i, properties: { i, end: i === 0 || i === legs.length - 1, selected: i === selected }, geometry: { type: 'Point', coordinates: l.p } })).filter((f) => show || f.properties.end) });
  }
  /** The route sheet's tools follow the route (Undo, Share, Delete point). */
  function updateTools() {
    const tools = sheet.current?.kind === 'route' ? sheet.body.querySelectorAll('.route-tools .btn') : [];
    if (tools.length !== 4) return;
    tools[1].disabled = !drawing || !history.length;
    tools[2].disabled = !route || route.legs.length < 2;
    tools[3].disabled = !drawing || selected < 0;
  }
  function changed({ save = true } = {}) {
    render();
    updateTools();
    updateStats();
    clearTimeout(profileTimer);
    profileTimer = setTimeout(updateProfile, 350);
    if (save) persist();
  }

  /** The hut under a tap: its dot within a finger's reach, or its name (the app's hutAt). */
  async function hutAt(point) {
    const p = map.project(point);
    const layers = ['nz-huts-dot', 'nz-huts-label'].filter((l) => map.getLayer(l) && map.getLayoutProperty(l, 'visibility') !== 'none');
    if (!layers.length) return null;
    const named = map.queryRenderedFeatures(p, { layers: ['nz-huts-label'].filter((l) => layers.includes(l)) })[0];
    if (named) return { point: named.geometry.coordinates, name: named.properties.name || 'hut' };
    const hits = map.queryRenderedFeatures([[p.x - HUT_PX, p.y - HUT_PX], [p.x + HUT_PX, p.y + HUT_PX]], { layers: ['nz-huts-dot'].filter((l) => layers.includes(l)) });
    // Within HUT_PX of the dot itself (a circle, not the box's corners).
    let best = null, bestD = HUT_PX;
    for (const f of hits) { const c = f.geometry.coordinates, q = map.project(c), d = Math.hypot(q.x - p.x, q.y - p.y); if (d <= bestD) { bestD = d; best = { point: c, name: f.properties.name || 'hut' }; } }
    return best;
  }

  /** A tap while drawing: queued, so fast taps keep their order. */
  function tap(lngLat) {
    let p = [lngLat.lng, lngLat.lat];
    if (!route) start();
    pending++;
    queue = queue.then(async () => {
      const found = await hutAt(p);
      const hut = found?.point || null;
      if (found) { p = hut; toast(`Locked onto ${found.name}`, 1600); }
      snapshot();
      const legs = route.legs;
      if (!legs.length) { legs.push({ p, path: [] }); changed(); return; }
      const from = legs[legs.length - 1].p;
      pendingLine = [from, p];
      render();
      let path = null;
      try {
        path = await Promise.race([worker.call({ type: 'follow', from, to: p, reach: reachAt(map, p) }), new Promise((r) => setTimeout(() => r(null), 20000))]);
      } catch { path = null; }
      pendingLine = null;
      if (path && path.length >= 2) {
        // Ends on the track beside the tap (or at the hut it locked onto).
        const end = hut && meters(path[path.length - 1], hut) < 60 ? hut : path[path.length - 1];
        legs.push({ p: end, path: [...path.slice(1, -1), end], follow: true });
      } else legs.push({ p, path: [p] });
      selected = -1;
      changed();
    }).finally(() => { pending--; });
  }

  function undo() {
    if (!history.length) return;
    route.legs = JSON.parse(history.pop());
    selected = -1;
    changed();
  }
  function deletePoint(i) {
    const legs = route.legs;
    if (i < 0 || i >= legs.length) return;
    snapshot();
    if (i === 0) { legs.shift(); if (legs[0]) legs[0].path = []; }
    else if (i === legs.length - 1) legs.pop();
    else { legs.splice(i, 1); legs[i].path = [legs[i].p]; }
    selected = -1;
    changed();
  }
  /** A tap on the route's line while editing: a new point there. */
  function insertAt(lngLat) {
    const p = [lngLat.lng, lngLat.lat];
    const legs = route.legs;
    let best = -1, bestD = Infinity, at = null, cut = 0;
    for (let i = 1; i < legs.length; i++) {
      const line = [legs[i - 1].p, ...legs[i].path];
      const n = nearestOn(line, p);
      if (n.d < bestD) { bestD = n.d; best = i; at = n.at; cut = n.along; }
    }
    if (best < 0) return false;
    snapshot();
    const leg = legs[best], line = [legs[best - 1].p, ...leg.path];
    const before = [], after = [];
    let walked = 0;
    for (let k = 1; k < line.length; k++) { walked += meters(line[k - 1], line[k]); (walked < cut ? before : after).push(line[k]); }
    legs.splice(best, 0, { p: at, path: [...before, at] });
    leg.path = after.length ? after : [leg.p];
    selected = best;
    changed();
    return true;
  }

  // ---- Dragging a point: slides along the tracks, comes off when pulled away ----
  let drag = null;
  function dragStart(e, i) {
    if (!drawing) return;
    e.preventDefault();
    const legs = route.legs;
    snapshot();
    const token = Math.random();
    const around = legs[i].p, near = [legs[i - 1]?.p, around, legs[i + 1]?.p].filter(Boolean);
    drag = { i, token, ready: false, moved: false };
    worker.call({ type: 'dragStart', points: near, around, token }).then((ok) => { if (drag?.token === token) drag.ready = !!ok; }).catch(() => {});
    map.getCanvas().style.cursor = 'grabbing';
  }
  async function dragMove(e) {
    if (!drag) return;
    drag.moved = true;
    const { i } = drag, legs = route.legs, p = [e.lngLat.lng, e.lngLat.lat];
    const prev = legs[i - 1]?.p || null, next = legs[i + 1]?.p || null;
    // Straight until the tracks are to hand.
    const straight = () => { legs[i].p = p; legs[i].path = prev ? [p] : []; if (next) legs[i + 1].path = [next]; render(); };
    if (!drag.ready) { straight(); return; }
    const token = drag.token;
    const r = await worker.call({ type: 'drag', prev, p, next, reach: reachAt(map, p), token }).catch(() => null);
    if (!drag || drag.token !== token || r === undefined) return;
    if (!r) { straight(); return; }
    legs[i].p = r.point;
    legs[i].path = prev ? r.before : [];
    if (next) legs[i + 1].path = [...r.after, next];
    render();
  }
  function dragEnd() {
    if (!drag) return;
    const moved = drag.moved, i = drag.i;
    drag = null;
    worker.post({ type: 'dragEnd' });
    map.getCanvas().style.cursor = '';
    if (!moved) { history.pop(); selected = selected === i ? -1 : i; render(); updateTools(); return; }
    changed();
  }
  for (const type of ['mousedown', 'touchstart']) map.on(type, 'waypoints', (e) => {
    if (!drawing || (e.originalEvent.touches && e.originalEvent.touches.length > 1)) return;
    const f = e.features?.[0];
    if (f) dragStart(e, f.properties.i);
  });
  map.on('mousemove', dragMove);
  map.on('touchmove', dragMove);
  map.on('mouseup', dragEnd);
  map.on('touchend', dragEnd);
  map.on('mouseenter', 'waypoints', () => { if (drawing) map.getCanvas().style.cursor = 'grab'; });
  map.on('mouseleave', 'waypoints', () => { if (!drag) map.getCanvas().style.cursor = ''; });

  // ---- The route panel ----
  function updateStats() {
    if (!statsBox || !route) return;
    const { points } = flatten(route.legs.length ? route.legs : [{ p: [0, 0], path: [] }]);
    statsBox.querySelector('[data-stat=distance]').textContent = distance(route.legs.length > 1 ? length(points) : 0);
  }
  async function updateProfile() {
    if (!chart || !route) return;
    const token = ++profileToken;
    if (route.legs.length < 2) { chart.set([]); return; }
    const { points } = flatten(route.legs);
    const samples = await worker.call({ type: 'profile', points, max: 700 }).catch(() => null);
    if (token !== profileToken || !samples || !chart) return;
    chart.set(samples);
    // Huts on the route (at its start or end, or passed on the way): marked on the profile.
    const line = samples.filter((s) => s.p);
    search.call({ type: 'huts', url: dataUrl('/data/places.tsv'), points: line.map((s) => s.p), along: line.map((s) => s.d), reach: 200 }).then((marks) => {
      if (token === profileToken && chart && marks?.length) chart.setMarkers(marks);
    }).catch(() => {});
    const s = stats(samples);
    statsBox.querySelector('[data-stat=ascent]').textContent = height(s.ascent);
    statsBox.querySelector('[data-stat=descent]').textContent = height(s.descent);
    statsBox.querySelector('[data-stat=high]').textContent = height(s.high);
  }
  const stat = (label, key, value = '—') => el('div', { class: 'stat' }, el('span', {}, label), el('b', key ? { 'data-stat': key } : {}, value));
  function renderPanel({ size } = {}) {
    if (!route) return;
    const was = sheet.current?.kind === 'route' ? sheet.root.dataset.size : null;
    sheet.open({
      kind: 'route', size: size || was || 'expanded', backLabel: route.name || 'Route',
      render(body) {
        // As the app's route sheet: name, stats, chart, then Edit / Undo / Share / Delete point.
        const name = el('input', { class: 'route-name', value: route.name, 'aria-label': 'Route name', placeholder: 'Route name', maxlength: 80, oninput: (e) => { route.name = e.target.value.trim() || 'Untitled route'; persist(); } });
        // The stats drag the sheet too (as the app's route sheet: grip, name and stats).
        statsBox = el('div', { class: 'stats', 'data-drag': '' }, stat('Distance', 'distance'), stat('Ascent', 'ascent'), stat('Descent', 'descent'), stat('Highest', 'high'));
        const canvas = el('canvas', { class: 'chart', role: 'img', 'aria-label': 'Elevation profile' });
        const view = new Chart(canvas, chartEvents(ctx, () => view));
        chart = view;
        const tools = el('div', { class: 'route-tools' },
          el('button', { class: 'btn', type: 'button', onclick: () => setDrawing(!drawing) }, drawing ? 'Done' : 'Edit'),
          el('button', { class: 'btn', type: 'button', onclick: undo, disabled: !drawing || !history.length }, 'Undo'),
          el('button', { class: 'btn', type: 'button', onclick: share, disabled: route.legs.length < 2 }, 'Share'),
          el('button', { class: 'btn danger', type: 'button', onclick: () => deletePoint(selected), disabled: !drawing || selected < 0 }, 'Delete point'));
        body.append(name, statsBox, el('div', { class: 'chart-wrap grow' }, canvas), tools);
        if (!drawing) body.append(el('div', { class: 'app-cta route-extra' }, el('p', {}, 'Take your routes offline on the hill with the Razorback Maps Android app.'), el('a', { class: 'btn', href: '/about/#app' }, 'Get the app')));
        updateStats();
        updateProfile();
        return () => { chart = null; statsBox = null; ctx.marker(null); ctx.range(null); };
      },
    });
  }

  function start(name = 'Untitled route') {
    route = { id: uid(), name, legs: [], saved: false };
    history = [];
    selected = -1;
  }
  function setDrawing(on) {
    drawing = on;
    document.body.classList.toggle('drawing', on);
    if (!on) { selected = -1; ctx.note(null); if (route && route.legs.length < 2) { route = null; render(); sheet.close(); return; } }
    render();
    renderPanel({ size: on ? 'compact' : 'expanded' });
    ctx.note(on ? 'Tap to add points · near a track, the route follows it' : null);
  }
  function share() {
    if (!route || route.legs.length < 2) return;
    const { points, waypoints } = flatten(route.legs);
    const link = `${location.origin}/#${encodeRoute({ name: route.name, points, waypoints })}`;
    const done = () => toast('Link copied. Open it on your phone to take the route with you.');
    if (navigator.share && matchMedia('(pointer: coarse)').matches) navigator.share({ title: route.name, text: `${route.name} – Razorback Maps`, url: link }).catch(() => {});
    else navigator.clipboard?.writeText(link).then(done, () => prompt('Copy this link:', link));
  }
  function reverse() {
    const { points, waypoints } = flatten(route.legs);
    const n = points.length - 1;
    route.legs = unflatten(points.slice().reverse(), waypoints.map((i) => n - i).reverse());
    changed();
  }
  function remove() {
    if (!route) return;
    store.set('routes', saved().filter((r) => r.id !== route.id));
    clear();
    sheet.close();
  }
  function clear() { route = null; drawing = false; document.body.classList.remove('drawing'); pendingLine = null; ctx.note(null); render(); }
  function fit() {
    if (!route || route.legs.length < 2) return;
    const b = bounds(flatten(route.legs).points, 0);
    ctx.fitBox(b);
  }
  function load(r, { fitTo = true, edit = false } = {}) {
    route = { id: r.id || uid(), name: r.name || 'Untitled route', legs: unflatten(r.points, r.waypoints?.length ? r.waypoints : [0, r.points.length - 1]), saved: !!r.id };
    history = []; selected = -1; drawing = edit;
    document.body.classList.toggle('drawing', edit);
    render();
    renderPanel();
    if (fitTo) fit();
  }

  // ---- Saved routes, in the drawer (as the app's cards) ----
  function thumbnail(points) {
    const c = el('canvas', { width: 100, height: 84, 'aria-hidden': 'true' });
    const g = c.getContext('2d');
    let w = Infinity, so = Infinity, e = -Infinity, n = -Infinity;
    for (const [x, y] of points) { w = Math.min(w, x); e = Math.max(e, x); so = Math.min(so, y); n = Math.max(n, y); }
    const k = Math.cos(((so + n) / 2) * Math.PI / 180), sx = (e - w) * k || 1e-9, sy = n - so || 1e-9, scale = Math.min(80 / sx, 64 / sy);
    g.lineWidth = 3; g.lineJoin = 'round'; g.lineCap = 'round'; g.strokeStyle = '#e87944';
    g.beginPath();
    points.forEach(([x, y], i) => { const px = 50 + ((x - (w + e) / 2) * k) * scale, py = 42 - (y - (so + n) / 2) * scale; if (i) g.lineTo(px, py); else g.moveTo(px, py); });
    g.stroke();
    return c;
  }
  /** The app's "Saved routes" drawer: Import GPX, Create new, then a card per route. */
  function list(container) {
    const routes = saved();
    const day = (t) => new Date(t).toLocaleDateString('en-NZ', { day: 'numeric', month: 'short', year: 'numeric' });
    const file = el('input', { type: 'file', accept: '.gpx,application/gpx+xml', hidden: true, onchange: (e) => { const f = e.target.files[0]; if (f) importGpx(f).then(() => list(container)); e.target.value = ''; } });
    const rename = (r) => { const name = prompt('Route name', r.name); if (!name || !name.trim()) return; r.name = name.trim().slice(0, 80); store.set('routes', saved().map((x) => (x.id === r.id ? { ...x, name: r.name } : x))); if (route?.id === r.id) route.name = r.name; list(container); };
    const remove = (r) => { store.set('routes', saved().filter((x) => x.id !== r.id)); if (route?.id === r.id) { clear(); sheet.close(); } list(container); };
    const open = (r) => { ctx.closeDrawer(); load(r); };
    container.replaceChildren(...[
      el('h2', {}, 'Saved routes'),
      el('button', { class: 'btn block', type: 'button', onclick: () => file.click() }, 'Import GPX'), file,
      el('button', { class: 'btn block', type: 'button', onclick: () => { ctx.closeDrawer(); newRoute(); } }, '＋  Create new'),
      routes.length ? null : el('p', { class: 'empty' }, 'No saved routes yet. Create one, then tap the map where you want to go. Near a track, the route follows it.'),
      ...routes.map((r) => el('div', { class: 'route-card' },
        el('div', { class: 'head' }, thumbnail(r.points), el('div', {}, el('b', {}, r.name), el('small', {}, `${distance(length(r.points))} · ${r.points.length > 1 ? `${r.waypoints?.length || 2} points` : ''} · ${day(r.updated)}`.replace(' ·  · ', ' · ')))),
        el('div', { class: 'actions' },
          el('button', { class: 'link', type: 'button', onclick: () => open(r) }, route?.id === r.id ? 'Opened' : 'Open'),
          el('button', { class: 'link', type: 'button', onclick: () => rename(r) }, 'Rename'),
          el('button', { class: 'link', type: 'button', onclick: () => shareRoute(r) }, 'Share'),
          el('button', { class: 'link', type: 'button', onclick: () => remove(r) }, 'Delete')))),
    ].filter(Boolean));
  }
  /** A GPX file's track (or route) as a saved route: its name, points and route points. */
  async function importGpx(fileObj) {
    try {
      const doc = new DOMParser().parseFromString(await fileObj.text(), 'application/xml');
      const pick = (sel) => [...doc.getElementsByTagName(sel)].map((n) => [+n.getAttribute('lon'), +n.getAttribute('lat')]).filter(([x, y]) => isFinite(x) && isFinite(y));
      let points = pick('trkpt'), waypoints = null;
      if (points.length < 2) { points = pick('rtept'); waypoints = points.map((_, i) => i); }
      if (points.length < 2) { toast('That GPX file has no track or route.'); return; }
      if (points.length > 20000) points = points.filter((_, i) => i % Math.ceil(points.length / 20000) === 0);
      const name = (doc.querySelector('trk > name, rte > name, metadata > name')?.textContent || fileObj.name.replace(/\.gpx$/i, '')).trim().slice(0, 80) || 'Imported route';
      const r = { id: uid(), name, points, waypoints: waypoints || [0, points.length - 1], updated: Date.now() };
      store.set('routes', [r, ...saved()].slice(0, MAX_ROUTES));
      toast(`Imported “${name}”.`);
    } catch { toast('That file could not be read as GPX.'); }
  }
  function shareRoute(r) {
    const link = `${location.origin}/#${encodeRoute({ name: r.name, points: r.points, waypoints: r.waypoints })}`;
    if (navigator.share && matchMedia('(pointer: coarse)').matches) navigator.share({ title: r.name, url: link }).catch(() => {});
    else navigator.clipboard?.writeText(link).then(() => toast('Link copied.'), () => prompt('Copy this link:', link));
  }
  function newRoute() {
    ctx.closeDetails?.();
    start();
    setDrawing(true);
  }

  // ---- From a walk or a hut ----
  function fromLine(name, line) {
    start(name);
    route.legs = unflatten(line, [0, line.length - 1]);
    persist();
    drawing = false;
    render();
    renderPanel();
    toast('Saved to your routes. Edit it to change it.');
  }
  function startAt(point, name) {
    start(`From ${name}`);
    route.legs.push({ p: point, path: [] });
    setDrawing(true);
  }

  // A shared link.
  function openLink(hash) {
    const r = decodeRoute(hash);
    if (!r) return false;
    load({ ...r, id: null });
    route.saved = false;
    toast('Shared route opened.');
    return true;
  }

  return {
    get drawing() { return drawing; },
    get active() { return !!route; },
    tap,
    lineTap: (lngLat) => (drawing && route && route.legs.length > 1 ? insertAt(lngLat) : false),
    newRoute, list, fromLine, startAt, openLink, setDrawing, clear,
    undo: () => drawing && undo(),
    deleteSelected: () => drawing && selected >= 0 && deletePoint(selected),
    close() { if (drawing) setDrawing(false); clear(); },
  };
}
