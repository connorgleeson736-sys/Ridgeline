// Bushline Maps on the web: the app's map in a browser.
//
// Speed, from the app and the old website's measurements:
// - MapLibre's terrain and imagery tile LOD as the old website tuned it, the
//   drawing buffer sized to a pixel budget (sharper when close), never resized
//   at the start of a gesture, and no antialiasing or alpha.
// - Overview imagery for zooms 5–12 from immutable, cached files; tiles that
//   leave the view are cancelled, not left holding download slots.
// - Data overlays fetched nearest-centre first and drawn once per batch;
//   routing, elevation and search on worker threads.
// - Camera saved in an idle callback, not on every move.
import { Map as MapLibre, setWorkerUrl, addProtocol, getVersion, setMaxParallelImageRequests, ScaleControl, AttributionControl } from '/vendor/maplibre-gl-6.16.0/maplibre-gl.mjs';
import { build, GROUPS, sky, hatch, shown, grade, NZ_BOUNDS } from './style.js';
import { START as A_COLOUR, END as B_COLOUR } from './chart.js';
import { install as installOverview, loadOverview, loadGapIndex } from './overview.js';
import { attach as attachOverlays } from './overlays.js';
import { install as installVector, attach as attachVector } from './vector.js';
import { attach as attachLabels } from './labels.js';
import { attach as attachPlanner } from './planner.js';
import { attach as attachSearch } from './search.js';
import { Sheet } from './sheet.js';
import { SidePanel } from './side.js';
import { openWalk, openTrack, openHut, openHunting, openTeAraroa, openTeAraroaPlace, openTeAraroaStop, openTeAraroaShop, pickOnTeAraroa } from './details.js';
import * as teAraroa from './te-araroa.js';
import { walkFor } from '../core/walks.js';
import { el, icon, store, toast } from './ui.js';
import { dataUrl } from './data-url.js';

setWorkerUrl('/vendor/maplibre-gl-6.16.0/maplibre-gl-worker.mjs');
installOverview({ addProtocol });
installVector({ addProtocol });
loadOverview();
loadGapIndex();
// Imagery, terrain and the app's overview tiles all come over HTTP/2: more requests
// in flight than MapLibre's default 16 shortens the first full view.
try { setMaxParallelImageRequests(24); } catch { /* older engines */ }

const $ = (id) => document.getElementById(id);
const dark = matchMedia('(prefers-color-scheme: dark)');
/** A finger (touch) or a mouse: tap targets are sized for it. */
const coarse = matchMedia('(pointer: coarse)').matches;
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;
const DEFAULTS = { base: 'aerial', natural: true, threeD: true, relief: 1, names: true, peaks: true, roads: true, tracks: true, huts: true, contours: false, hunting: false, teAraroa: true };
const settings = { ...DEFAULTS, ...store.get('settings', {}) };
const saveSettings = () => store.set('settings', settings);

// ---- Pixel budget (the old website's RidgePerformance.pixelRatio) ----
const memory = navigator.deviceMemory || 8, saveData = !!navigator.connection?.saveData;
const lowPower = memory <= 2 || saveData;
let sharp = false;
// A phone: a small, dense screen. Its drawing buffer is small enough to draw at up to
// 2.5× at every zoom, and its imagery is asked for half a zoom finer (style.js).
const phone = !lowPower && (devicePixelRatio || 1) >= 2 && innerWidth * innerHeight <= 600_000;
function pixelRatio(zoom) {
  if (zoom >= 15.3) sharp = true; else if (zoom < 14.7) sharp = false;
  const dpr = devicePixelRatio || 1, w = innerWidth, h = innerHeight;
  if (lowPower) return 1;
  if (settings.base === 'topo') return Math.max(1, Math.min(dpr, 3, Math.sqrt(8_000_000 / (w * h))));
  if (phone) return Math.max(1, Math.min(dpr, 2.5, Math.sqrt(6_000_000 / (w * h))));
  return Math.max(1, Math.min(dpr, 2, sharp ? 2 : 1.5, Math.sqrt((sharp ? 6_000_000 : 2_800_000) / (w * h))));
}

// ---- Where to start: a link, else where you were, else Aoraki ----
function parseView(hash) {
  const m = /(?:^#|&)(?:map=)?(\d+(?:\.\d+)?)\/(-?\d+(?:\.\d+)?)\/(-?\d+(?:\.\d+)?)(?:\/(-?\d+(?:\.\d+)?))?(?:\/(\d+(?:\.\d+)?))?/.exec(hash);
  if (!m) return null;
  const [zoom, lat, lng, bearing = 0, pitch = 0] = m.slice(1).map((v) => (v == null ? undefined : +v));
  if (!(lat < -28 && lat > -53 && lng > 160 && lng < 185)) return null;
  return { zoom, center: [lng, lat], bearing: bearing || 0, pitch: pitch || 0 };
}
const START = { center: [170.105, -43.7], zoom: 11.7, bearing: -18, pitch: 62 };
const view = parseView(location.hash) || store.get('view', null) || START;

async function main() {
  if (!webglSupported()) { $('map-status').hidden = false; $('map-status').textContent = 'Your browser can’t show the 3D map (WebGL is turned off or unsupported). Try an up-to-date Chrome, Edge, Firefox or Safari.'; return; }
  const topography = await fetch(dataUrl('/data/topography-style.json')).then((r) => r.json());
  // NZ Topo at about one map pixel per screen pixel: a full zoom finer on 2-3x
  // screens (128), half a zoom on 1.5x ones (180); about 4x and 2x the tiles.
  const dpr = devicePixelRatio || 1;
  const topoSize = lowPower ? 256 : dpr >= 1.75 ? 128 : dpr >= 1.3 ? 180 : 256;
  const style = build({ topography, settings, dark: dark.matches, fine: phone, topoSize });
  const map = new MapLibre({
    container: 'map',
    style,
    center: view.center, zoom: view.zoom, bearing: view.bearing,
    pitch: settings.threeD ? (view.pitch ?? 0) : 0,
    minZoom: 4.5, maxZoom: 19, maxPitch: 85, maxBounds: NZ_BOUNDS,
    pixelRatio: pixelRatio(view.zoom),
    // Tiles kept after they leave the view, per source, by the device's memory
    // (the app's RAM budget): panning back shows them at once.
    maxTileCacheSize: saveData ? 120 : memory <= 2 ? 160 : memory <= 4 ? 360 : 640,
    maxTileCacheZoomLevels: 8,
    cancelPendingTileRequestsWhileZooming: false,
    centerClampedToGround: true,
    refreshExpiredTiles: false,
    renderWorldCopies: false,
    fadeDuration: 150,
    anisotropicFilterPitch: 35,
    attributionControl: false,
    canvasContextAttributes: { antialias: false, alpha: false, preserveDrawingBuffer: false, powerPreference: 'default' },
    dragRotate: true, pitchWithRotate: true, touchPitch: true,
    locale: { 'NavigationControl.ZoomIn': 'Zoom in', 'NavigationControl.ZoomOut': 'Zoom out' },
  });
  window.bushlineMap = map;
  // Drawn images (hunting hatching), added as soon as the style
  // is in, before any tile asks for them; and again if one is ever missing.
  const images = () => {
    if (!map.hasImage('hatch')) { const h = hatch(2); map.addImage('hatch', h.image, { pixelRatio: h.pixelRatio }); }
  };
  map.on('style.load', images);
  map.on('styleimagemissing', (e) => { if (e.id === 'hatch') images(); });
  map.addControl(new ScaleControl({ maxWidth: 100, unit: 'metric' }), 'bottom-left');
  map.addControl(new AttributionControl({ compact: true, customAttribution: '<a href="/credits/">Data credits</a>' }), 'bottom-right');

  // ---- Shared context for panels ----
  const sheet = new Sheet($('sheet'), { onClose: () => { side.close(); if (planner.active) planner.close(); select(null); marker(null); } });
  // Beside the side panel on computers: what is picked in a list opens there.
  const side = new SidePanel(sheet);
  const sheetPadding = () => {
    const wide = matchMedia('(min-width: 800px)').matches, s = $('sheet');
    const pad = { top: 70, bottom: 50, left: 70, right: 60 };
    if (!s.hidden) {
      if (wide) {
        // Beside the card, wherever it has been moved to.
        const r = s.getBoundingClientRect();
        if (r.left + r.width / 2 < innerWidth / 2) pad.left = Math.max(pad.left, r.right + 20); else pad.right = Math.max(pad.right, innerWidth - r.left + 20);
      } else pad.bottom += Math.min(s.offsetHeight, innerHeight * 0.6);
    }
    // And beside the panel next to it (a picked Te Araroa section).
    const sp = document.querySelector('.side-panel');
    if (wide && sp && !sp.hidden) {
      const r = sp.getBoundingClientRect();
      if (r.left + r.width / 2 < innerWidth / 2) pad.left = Math.max(pad.left, r.right + 20); else pad.right = Math.max(pad.right, innerWidth - r.left + 20);
    }
    return pad;
  };
  const geo = (type, data) => map.getSource(type)?.setData(data);
  let selected = null, part = null;
  function drawSelection() {
    const features = (selected || []).map((l) => ({ type: 'Feature', properties: { dim: !!part }, geometry: { type: 'LineString', coordinates: l } }));
    if (part) features.push({ type: 'Feature', properties: { dim: false }, geometry: { type: 'LineString', coordinates: part } });
    geo('selection', { type: 'FeatureCollection', features });
  }
  function select(lines) { selected = lines; part = null; drawSelection(); range(null); }
  /** A section measured on the profile: A and B on the map, the rest of the line dimmed. */
  function range(r) {
    part = r?.part?.length > 1 ? r.part : null;
    drawSelection();
    geo('range', { type: 'FeatureCollection', features: r ? [['A', A_COLOUR, r.a], ['B', B_COLOUR, r.b]].map(([label, color, p]) => ({ type: 'Feature', properties: { label, color }, geometry: { type: 'Point', coordinates: p } })) : [] });
  }
  function marker(p) { geo('marker', { type: 'FeatureCollection', features: p ? [{ type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: p } }] : [] }); }
  /** Frames a box beside the panel: worked out flat, then tilted (a pitched fit
   * around a half-screen sheet would zoom far out). */
  function fitBox(b) {
    const padding = sheetPadding(), pitch = settings.threeD ? Math.min(map.getPitch() || 50, 55) : 0;
    const cam = map.cameraForBounds([[b[0], b[1]], [b[2], b[3]]], { padding, maxZoom: 15, bearing: map.getBearing(), pitch: 0 });
    if (!cam) return;
    // No padding on the move itself: MapLibre keeps it, and the next fit would count it twice.
    map.easeTo({ center: cam.center, zoom: Math.max(5, cam.zoom - (pitch ? 0.35 : 0)), bearing: map.getBearing(), pitch, duration: reducedMotion ? 0 : 900 });
  }
  function fit(line) { let w = 180, s = 0, e = 0, n = -90; for (const [x, y] of line) { w = Math.min(w, x); e = Math.max(e, x); s = Math.min(s, y); n = Math.max(n, y); } fitBox([w, s, e, n]); }
  const ctx = {
    side,
    map, sheet, select, fit, fitBox,
    marker: (p, fly) => marker(p),
    range,
    /** A leg of a walk: brighter, the rest dimmed (no A and B). */
    part: (pts) => { part = pts?.length > 1 ? pts : null; drawSelection(); geo('range', { type: 'FeatureCollection', features: [] }); },
    flyTo: (p, zoom) => { const pad = sheetPadding(); map.flyTo({ center: p, zoom, pitch: settings.threeD ? Math.max(map.getPitch(), 45) : 0, offset: [(pad.left - pad.right) / 2, (pad.top - pad.bottom) / 2], duration: reducedMotion ? 0 : 1600, essential: true }); },
    openWalk: (guid) => openWalk(ctx, guid),
    openHut: (name, p) => openHut(ctx, name, p),
    openTeAraroa: (slug = null, options) => openTeAraroa(ctx, slug, options),
    closeDetails: () => { if (!planner.active) sheet.close(); },
    note: (text) => { const n = $('drawing-note'); n.textContent = text || ''; n.hidden = !text; },
    openDrawer: () => openDrawer(),
    closeDrawer: () => closeDrawer(),
  };
  const overlays = attachOverlays(map, () => settings);
  // Te Araroa: its line and kilometres, loaded the first time its layer is on.
  let teAraroaLoaded = false;
  const syncTeAraroa = () => {
    if (teAraroaLoaded || !shown('te-araroa', settings)) return;
    teAraroaLoaded = true;
    teAraroa.load().then((d) => {
      const f = teAraroa.features(d);
      const put = () => { map.getSource('te-araroa')?.setData(f.lines); map.getSource('te-araroa-places')?.setData(f.places); map.getSource('te-araroa-food')?.setData(f.stops); map.getSource('te-araroa-names')?.setData(f.names); map.getSource('te-araroa-shops')?.setData(f.shops); };
      if (map.getSource('te-araroa')) put(); else map.once('load', put);
    }).catch(() => { teAraroaLoaded = false; });
  };
  const refreshOverlays = overlays.refresh;
  overlays.refresh = () => { refreshOverlays(); syncTeAraroa(); };
  attachVector(map, () => shown('nz-tracks', settings));
  const labels = attachLabels(map, style.metadata.labels);
  ctx.overlays = overlays;
  const planner = attachPlanner(ctx);
  ctx.planner = planner;

  // ---- The kea: search and saved routes (the app's drawer) ----
  const cover = $('drawer-cover'), drawerBody = $('drawer-body'), launcher = $('launcher');
  const routesBox = document.createElement('div'), resultsBox = document.createElement('ul');
  resultsBox.className = 'results'; resultsBox.hidden = true;
  drawerBody.append(resultsBox, routesBox);
  const search = attachSearch(ctx, { form: $('search'), results: resultsBox, routes: routesBox });
  function openDrawer() {
    layersPanel.close();
    planner.list(routesBox);
    cover.hidden = false;
    launcher.setAttribute('aria-expanded', 'true');
    search.warm();
    if (matchMedia('(pointer: fine)').matches) search.focus();
  }
  function closeDrawer() { cover.hidden = true; launcher.setAttribute('aria-expanded', 'false'); }
  launcher.addEventListener('click', () => (cover.hidden ? openDrawer() : closeDrawer()));
  $('drawer-close').addEventListener('click', closeDrawer);
  // The map stays usable with the drawer open (pan, zoom); a tap on the map closes it.


  map.on('load', () => {
    $('map-status').hidden = true;
    // Credits collapsed behind the (i), as in the app.
    document.querySelector('.maplibregl-ctrl-attrib')?.classList.remove('maplibregl-compact-show');
    for (const [id, levels, ratio] of [['terrain', lowPower ? 9 : 8, lowPower ? 3 : 3.5], ['aerial', lowPower ? 7 : 6, lowPower ? 3 : 3.5], ['topo', 1, 1]]) {
      try { map.setSourceTileLodParams(levels, ratio, id); } catch { /* older engines */ }
    }
    overlays.refresh();
    if (/[#&]r=/.test(location.hash)) planner.openLink(location.hash);
  });
  map.on('error', (e) => { if (/WebGL|context/i.test(String(e?.error?.message))) toast('The map stopped drawing. Reload the page to continue.', 6000); });
  let loadingTimer = 0;
  map.on('dataloading', () => { clearTimeout(loadingTimer); loadingTimer = setTimeout(() => document.body.classList.add('loading'), 400); });
  map.on('idle', () => { clearTimeout(loadingTimer); document.body.classList.remove('loading'); });

  // ---- Drawing buffer: sized at the end of a gesture, never at its start ----
  let ratio = map.getPixelRatio();
  const resize = () => { const next = pixelRatio(map.getZoom()); if (Math.abs(next - ratio) >= 0.08) { ratio = next; map.setPixelRatio(next); } };
  map.on('moveend', resize);
  addEventListener('resize', resize);

  // ---- Camera: saved when the browser is idle ----
  let saveHandle = 0;
  const idle = window.requestIdleCallback || ((f) => setTimeout(f, 250));
  map.on('moveend', () => {
    if (saveHandle) return;
    saveHandle = idle(() => {
      saveHandle = 0;
      const c = map.getCenter(), v = { center: [+c.lng.toFixed(5), +c.lat.toFixed(5)], zoom: +map.getZoom().toFixed(2), bearing: +map.getBearing().toFixed(1), pitch: +map.getPitch().toFixed(1) };
      store.set('view', v);
      if (!/[#&]r=/.test(location.hash)) history.replaceState(null, '', `#${v.zoom}/${v.center[1]}/${v.center[0]}/${v.bearing}/${v.pitch}`);
    }, { timeout: 1000 });
  });
  addEventListener('hashchange', () => {
    if (/[#&]r=/.test(location.hash)) { planner.openLink(location.hash); return; }
    const v = parseView(location.hash);
    if (v) map.jumpTo(v);
  });

  // ---- Taps: route points while drawing; else huts, tracks, hunting areas ----
  map.on('click', (e) => {
    if (!cover.hidden) { closeDrawer(); return; }
    if (planner.drawing) {
      const p = e.point, near = map.queryRenderedFeatures([[p.x - 8, p.y - 8], [p.x + 8, p.y + 8]], { layers: ['waypoints', 'route-line'] });
      if (near.some((f) => f.layer.id === 'waypoints')) return;
      const r = coarse ? 24 : 12, onHut = map.queryRenderedFeatures([[p.x - r, p.y - r], [p.x + r, p.y + r]], { layers: ['nz-huts-dot', 'nz-huts-label'].filter((l) => map.getLayer(l)) }).length > 0;
      if (!onHut && near.some((f) => f.layer.id === 'route-line') && planner.lineTap(e.lngLat)) return;
      planner.tap(e.lngLat);
      return;
    }
    identify(e);
  });
  function nearestFeature(point, layers, px) {
    const hits = map.queryRenderedFeatures([[point.x - px, point.y - px], [point.x + px, point.y + px]], { layers: layers.filter((l) => map.getLayer(l)) });
    let best = null, bestD = Infinity;
    for (const f of hits) {
      const d = pixelDistance(map, f.geometry, point);
      if (d < bestD) { bestD = d; best = f; }
    }
    return best;
  }
  function identify(e) {
    const point = e.point, lngLat = [e.lngLat.lng, e.lngLat.lat];
    // Tapped while on Te Araroa: Back returns to it (and leaves the map where it is).
    const push = /^te-araroa/.test(sheet.current?.kind || '');
    if (shown('nz-huts-dot', settings)) {
      const hut = nearestFeature(point, ['nz-huts-dot', 'nz-huts-label'], 24);
      if (hut) { openHut(ctx, hut.properties.name || '', hut.geometry.type === 'Point' ? hut.geometry.coordinates : lngLat, { push }); return; }
    }
    if (shown('te-araroa', settings) && teAraroa.loaded()) {
      const d = teAraroa.loaded();
      // The "Te Araroa" label: its page.
      if (nearestFeature(point, ['te-araroa-name'], 6)) { if (!sheet.current?.overview) openTeAraroa(ctx, null, { push }); return; }
      const shop = nearestFeature(point, ['te-araroa-shops', 'te-araroa-shops-label'], 12);
      if (shop) { const i = shop.properties.i; pickOnTeAraroa(ctx, 'shop', i).then((done) => { if (!done) openTeAraroaShop(ctx, i, { push }); }); return; }
      const food = nearestFeature(point, ['te-araroa-food', 'te-araroa-food-label'], 14);
      if (food) { const i = food.properties.i; pickOnTeAraroa(ctx, 'stop', i).then((done) => { if (!done) openTeAraroaStop(ctx, i, { push }); }); return; }
      const place = nearestFeature(point, ['te-araroa-places'], 12);
      if (place) { const i = place.properties.i; pickOnTeAraroa(ctx, 'place', i).then((done) => { if (!done) openTeAraroaPlace(ctx, i, { push }); }); return; }
      const ta = nearestFeature(point, ['te-araroa', 'te-araroa-option'], 16);
      const other = ta && shown('nz-tracks', settings) ? nearestFeature(point, ['nz-tracks', 'doc-walks'], 18) : null;
      // Te Araroa when its line is nearest (it is drawn over the tracks it follows).
      if (ta && (!other || pixelDistance(map, ta.geometry, point) <= pixelDistance(map, other.geometry, point) + 4)) {
        const piece = ta.properties.kind === 'main' ? d.pieces[ta.properties.i] : null;
        const slug = piece ? piece.section : ta.properties.section;
        const found = piece ? walkFor(longest(ta.geometry), piece.name, overlays.walkLines(), overlays.infos()) : null;
        const at = piece ? teAraroa.kmOn(piece, lngLat) : null, walk = found?.line.guid || null;
        // On the Te Araroa page: beside the list. On a section opened from the map: in its place.
        const onSection = sheet.current?.kind === 'te-araroa' && !sheet.current.overview;
        pickOnTeAraroa(ctx, 'section', slug, { at, walk }).then((done) => { if (!done) openTeAraroa(ctx, slug, { push: push && !onSection, at, walk }); });
        return;
      }
    }
    if (shown('nz-tracks', settings)) {
      const track = nearestFeature(point, ['nz-tracks', 'nz-tracks-label', 'doc-walks', 'doc-walk-names'], 18);
      if (track) {
        if (track.source === 'doc-walks' || track.layer.id === 'doc-walk-names') { openWalk(ctx, track.properties.guid, { push }); return; }
        const name = (track.properties.name || '').trim();
        let geom = longest(track.geometry);
        // A name is a point (labels.js): the track is the named piece through it.
        if (track.geometry.type === 'Point' && name) {
          const at = track.geometry.coordinates, d = (l) => Math.min(...l.map((p) => (p[0] - at[0]) ** 2 + (p[1] - at[1]) ** 2));
          geom = map.querySourceFeatures('ridgeline-tracks', { sourceLayer: 'streets', filter: ['==', ['get', 'name'], name] }).flatMap((f) => lines(f.geometry)).sort((a, b) => d(a) - d(b))[0] || [at];
        }
        const found = walkFor(geom, name, overlays.walkLines(), overlays.infos());
        // A track on a DOC walk opens the walk; close in (zoom 15), a named section
        // of a large walk opens as itself (the app's DocWalks.CHILD_NAMES).
        const section = found?.named && name && map.getZoom() >= 15 && !sameAsWalk(name, overlays.infos()[found.line.guid]?.name);
        if (found && !section) { openWalk(ctx, found.line.guid, { push }); return; }
        const pieces = name ? map.querySourceFeatures('ridgeline-tracks', { sourceLayer: 'streets', filter: ['==', ['get', 'name'], name] }).flatMap((f) => lines(f.geometry)) : [geom];
        openTrack(ctx, name || 'Track', lngLat, pieces.length ? pieces : [geom], { push });
        return;
      }
    }
    if (settings.hunting && map.getZoom() >= 7) {
      const areas = overlays.huntingAt(e.lngLat);
      if (areas.length) { openHunting(ctx, areas); return; }
    }
    if (sheet.isOpen && !planner.active) sheet.close();
  }
  const sameAsWalk = (a, b) => !!b && a.toLowerCase() === b.toLowerCase();
  // Pointer cursor over what can be tapped.
  const tappable = ['nz-huts-dot', 'nz-huts-label', 'te-araroa', 'te-araroa-name', 'te-araroa-option', 'te-araroa-places', 'te-araroa-food', 'te-araroa-shops', 'nz-tracks', 'nz-tracks-label', 'doc-walks', 'doc-walk-names'];
  for (const l of tappable) {
    map.on('mouseenter', l, () => { if (!planner.drawing) map.getCanvas().style.cursor = 'pointer'; });
    map.on('mouseleave', l, () => { if (!planner.drawing) map.getCanvas().style.cursor = ''; });
  }

  // ---- Map chrome, as the app's ----
  const compass = $('compass'), needle = compass.querySelector('svg');
  const turn = () => { needle.style.transform = `rotate(${-map.getBearing()}deg)`; };
  map.on('rotate', turn); turn();
  compass.addEventListener('click', () => map.easeTo({ bearing: 0, duration: reducedMotion ? 0 : 500 }));
  const views = [...document.querySelectorAll('#perspective button')];
  const showView = () => { for (const b of views) b.setAttribute('aria-pressed', String((b.dataset.view === '3d') === settings.threeD)); };
  showView();
  for (const b of views) b.addEventListener('click', () => setThreeD(b.dataset.view === '3d'));
  // NZ Topo is a flat map (as in the app): 3D returns to aerial imagery.
  function setThreeD(on) {
    if (on === settings.threeD) return;
    settings.threeD = on;
    if (on && settings.base === 'topo') layersPanel.setBase('aerial', { keep3d: true });
    saveSettings(); showView();
    map.setTerrain(on ? { source: 'terrain', exaggeration: settings.relief } : null);
    map.easeTo({ pitch: on ? 58 : 0, duration: reducedMotion ? 0 : 600 });
    layersPanel.sync();
  }
  let watching = null;
  const locate = $('locate');
  locate.addEventListener('click', () => {
    if (watching != null) { navigator.geolocation.clearWatch(watching); watching = null; locate.classList.remove('on'); locate.setAttribute('aria-label', 'Show my location'); geo('you', { type: 'FeatureCollection', features: [] }); return; }
    if (!navigator.geolocation) { toast('This browser can’t share its location.'); return; }
    let first = true;
    locate.classList.add('on');
    watching = navigator.geolocation.watchPosition((pos) => {
      const p = [pos.coords.longitude, pos.coords.latitude];
      geo('you', { type: 'FeatureCollection', features: [{ type: 'Feature', properties: { accuracy: pos.coords.accuracy }, geometry: { type: 'Point', coordinates: p } }] });
      locate.setAttribute('aria-label', `Stop showing my location · accuracy about ${Math.round(pos.coords.accuracy)} m`);
      if (first) { first = false; if (p[0] > 165 && p[0] < 179.5 && p[1] < -33 && p[1] > -48) map.flyTo({ center: p, zoom: Math.max(map.getZoom(), 13), duration: reducedMotion ? 0 : 1500 }); else toast('You’re outside New Zealand.'); }
    }, () => { toast('Couldn’t get your location. Check that location is allowed for this site.'); locate.click(); }, { enableHighAccuracy: true, timeout: 20000, maximumAge: 10000 });
  });
  const layersPanel = layers(map, $('layers-panel'), $('layers-button'), { setThreeD, overlays, labels, resize, closeDrawer });

  dark.addEventListener('change', () => { map.setSky(sky(dark.matches)); map.setPaintProperty('background', 'background-color', dark.matches ? '#16211d' : '#dfe7e1'); });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !cover.hidden) { closeDrawer(); return; }
    const typing = e.target.matches('input:not([type=checkbox]):not([type=range]), textarea, select');
    if (typing) return;
    if ((e.ctrlKey || e.metaKey) && e.key === 'z') { e.preventDefault(); planner.undo(); }
    else if ((e.key === 'Delete' || e.key === 'Backspace') && planner.drawing) { e.preventDefault(); planner.deleteSelected(); }
    else if (e.key === 'Escape') { if (!cover.hidden) closeDrawer(); else if (!$('layers-panel').hidden) layersPanel.close(); else if (planner.drawing) planner.setDrawing(false); else sheet.close(); }
    else if (e.key === '/') { e.preventDefault(); openDrawer(); search.focus(); }
  });
}

/** The layers card, with the app's wording; only the settings that matter. */
function layers(map, panel, button, { setThreeD, overlays, labels, resize, closeDrawer }) {
  const toggles = [['roads', 'Roads & road names'], ['names', 'Place & water names'], ['tracks', 'Mapped walking tracks', 'tracks'], ['huts', 'Huts & bivouacs', 'huts'], ['contours', 'Contour lines & heights'], ['hunting', 'Hunting permit areas', 'hunting'], ['teAraroa', 'Te Araroa trail', 'te-araroa'], ['peaks', 'Peak labels']];
  const base = (id, label) => el('button', { class: 'base', type: 'button', 'data-base': id, onclick: () => setBase(id) }, el('span', { class: `swatch swatch-${id}` }), label);
  const reliefValue = el('output', {}, `${(+settings.relief).toFixed(1)}×`);
  const relief = el('input', { type: 'range', min: 1, max: 2, step: 0.1, value: settings.relief, 'aria-label': 'Terrain relief', oninput: (e) => { settings.relief = +e.target.value; reliefValue.textContent = `${settings.relief.toFixed(1)}×`; if (settings.threeD) map.setTerrain({ source: 'terrain', exaggeration: settings.relief }); saveSettings(); } });
  const topoNote = el('p', { class: 'note', hidden: true }, 'NZ Topo shows its own tracks, huts and names.');
  // The app's "Natural imagery colour": its overview imagery and colour grade, on by default.
  const naturalInput = el('input', { type: 'checkbox', checked: settings.natural, onchange: (e) => setNatural(e.target.checked) });
  const naturalBox = el('label', { class: 'check' }, el('span', {}, 'Natural imagery colour'), naturalInput);
  function setNatural(on) {
    settings.natural = on; saveSettings();
    for (const id of ['aerial', 'satellite']) for (const l of [id, `${id}-cover`]) for (const [k, v] of Object.entries(grade(id, on))) map.setPaintProperty(l, k, v);
    for (const src of ['aerial', 'aerial-cover']) map.getSource(src)?.setTiles([`bushline://aerial/{z}/{x}/{y}${on ? '' : '?raw'}`]);
  }
  panel.append(
    el('div', { class: 'heading' }, el('h2', {}, 'Map layers'), el('button', { class: 'icon-button', type: 'button', 'aria-label': 'Close layers', onclick: () => close() }, '✕')),
    el('div', { class: 'bases' }, base('aerial', 'LINZ aerial'), base('satellite', 'Satellite'), base('topo', 'NZ Topo · 2D')),
    naturalBox,
    el('h3', {}, 'Topographic overlays'),
    topoNote,
    ...toggles.map(([key, label, keyClass]) => el('label', { class: 'check' }, el('span', {}, keyClass ? el('i', { class: `key ${keyClass}`, 'aria-hidden': 'true' }) : null, label), el('input', { type: 'checkbox', 'data-toggle': key, checked: settings[key], onchange: (e) => setGroup(key, e.target.checked) }))),
    el('label', { class: 'relief' }, el('span', {}, 'Terrain relief'), reliefValue, relief),
    el('a', { class: 'doc-link', href: 'https://www.doc.govt.nz/parks-and-recreation/know-before-you-go/alerts/', target: '_blank', rel: 'noopener' }, 'Check DOC alerts ↗'));
  const applyAll = () => { for (const ids of Object.values(GROUPS)) for (const id of ids) if (map.getLayer(id)) map.setLayoutProperty(id, 'visibility', shown(id, settings) ? 'visible' : 'none'); labels.refresh(); };
  function setBase(id, { keep3d = false } = {}) {
    settings.base = id; saveSettings();
    for (const layer of ['aerial', 'satellite', 'topo']) for (const l of [layer, `${layer}-cover`]) map.setLayoutProperty(l, 'visibility', id === layer ? 'visible' : 'none');
    applyAll(); overlays.refresh(); sync(); resize();
    if (id === 'topo' && settings.threeD && !keep3d) setThreeD(false);
  }
  function setGroup(key, on) {
    settings[key] = on; saveSettings();
    applyAll();
    overlays.refresh();
    if (key === 'hunting' && on && map.getZoom() < 7) toast('Hunting areas show from closer in. Zoom in to see them.');
  }
  function sync() {
    for (const b of panel.querySelectorAll('[data-base]')) b.setAttribute('aria-pressed', String(b.dataset.base === settings.base));
    relief.disabled = !settings.threeD;
    const topo = settings.base === 'topo';
    naturalInput.disabled = topo; naturalBox.classList.toggle('disabled', topo);
    for (const input of panel.querySelectorAll('[data-toggle]')) { const off = topo && input.dataset.toggle !== 'hunting'; input.disabled = off; input.closest('label').classList.toggle('disabled', off); }
    topoNote.hidden = !topo;
  }
  function open() { closeDrawer(); panel.hidden = false; button.setAttribute('aria-expanded', 'true'); }
  function close() { panel.hidden = true; button.setAttribute('aria-expanded', 'false'); }
  button.addEventListener('click', () => (panel.hidden ? open() : close()));
  document.addEventListener('pointerdown', (e) => { if (!panel.hidden && !panel.contains(e.target) && !button.contains(e.target)) close(); });
  sync();
  return { sync, close, setBase };
}

function lines(g) { return g.type === 'LineString' ? [g.coordinates] : g.type === 'MultiLineString' ? g.coordinates : []; }
function longest(g) { return lines(g).sort((a, b) => b.length - a.length)[0] || []; }
function pixelDistance(map, g, p) {
  const pts = g.type === 'Point' ? [[g.coordinates]] : lines(g);
  let best = Infinity;
  for (const line of pts) {
    const q = line.map((c) => map.project(c));
    if (q.length === 1) best = Math.min(best, Math.hypot(q[0].x - p.x, q[0].y - p.y));
    for (let i = 1; i < q.length; i++) {
      const a = q[i - 1], b = q[i], dx = b.x - a.x, dy = b.y - a.y, l = dx * dx + dy * dy;
      const t = l ? Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / l)) : 0;
      best = Math.min(best, Math.hypot(a.x + t * dx - p.x, a.y + t * dy - p.y));
    }
  }
  return best;
}
function webglSupported() {
  try { const c = document.createElement('canvas'); return !!(c.getContext('webgl2') || c.getContext('webgl')); } catch { return false; }
}

main().catch((e) => { console.error(e); const s = $('map-status'); s.hidden = false; s.textContent = 'The map could not start. Check your connection and reload the page.'; });
export { getVersion };
