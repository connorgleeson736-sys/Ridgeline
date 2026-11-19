// The map's style: LINZ aerial photography (with the app's overview imagery
// for zooms 5–12), LINZ Topo50, LINZ elevation in 3D, and the app's topographic
// layers over them (LINZ topographic-v2 vector tiles).
import config from './config.js';

const LINZ = 'https://basemaps.linz.govt.nz/v1';
const key = encodeURIComponent(config.linzKey);
export const NZ_BOUNDS = [[150, -58], [195, -24]];
export const LINZ_ATTRIBUTION = '<a href="https://www.linz.govt.nz/" target="_blank" rel="noopener">© Toitū Te Whenua LINZ</a> CC BY 4.0';
const TA_ATTRIBUTION = '<a href="https://www.teararoa.org.nz/" target="_blank" rel="noopener">Te Araroa Trust</a> CC BY 4.0';
export const TE_ARAROA_COLOUR = '#ef3b5d';
const DOC_ATTRIBUTION = '<a href="https://www.doc.govt.nz/" target="_blank" rel="noopener">Department of Conservation</a> CC BY 4.0';

export const urls = {
  aerial: `${LINZ}/tiles/aerial/WebMercatorQuad/{z}/{x}/{y}.webp?api=${key}`,
  // LINZ's aerial tiles to zoom 14 are darkened by these two layers (black with
  // alpha); overview.js takes them back out so every zoom has zoom 15's colours.
  shade: ['geographx-nz-dem-2012-8m', 'geographx-nz-texture-shade-2012-8m'].map((l) => `${LINZ}/tiles/${l}/WebMercatorQuad/{z}/{x}/{y}.png?api=${key}`),
  topo: `${LINZ}/tiles/topo-raster/WebMercatorQuad/{z}/{x}/{y}.webp?api=${key}`,
  // The two maps inside LINZ's topo-raster (Topo250 to its zoom 12, Topo50 from 13),
  // each on its own, so the zoom where one gives way to the other can be chosen.
  topo250: `${LINZ}/tiles/nz-topo250-new-zealand-mainland-gridless/WebMercatorQuad/{z}/{x}/{y}.webp?api=${key}`,
  topo50: `${LINZ}/tiles/nz-topo50-new-zealand-mainland-gridless/WebMercatorQuad/{z}/{x}/{y}.webp?api=${key}`,
  elevation: `${LINZ}/tiles/elevation/WebMercatorQuad/{z}/{x}/{y}.png?pipeline=terrain-rgb&api=${key}`,
  vector: `${LINZ}/tiles/topographic-v2/3857/{z}/{x}/{y}.pbf?api=${key}`,
  glyphs: `${LINZ}/fonts/{fontstack}/{range}.pbf?api=${key}`,
};

/** The clear-sky gradient (the app's default sky). */
export function sky(dark) {
  return dark
    ? { 'sky-color': '#0d1a26', 'horizon-color': '#3b4c5c', 'fog-color': '#2b3844', 'sky-horizon-blend': 0.6, 'horizon-fog-blend': 0.5, 'fog-ground-blend': 0.85, 'atmosphere-blend': 0 }
    : { 'sky-color': '#78b5fe', 'horizon-color': '#dceaf6', 'fog-color': '#e6eef4', 'sky-horizon-blend': 0.55, 'horizon-fog-blend': 0.45, 'fog-ground-blend': 0.88, 'atmosphere-blend': 0 };
}

/** Which of the app's topographic layers each layer switch controls. */
export const GROUPS = {
  names: ['nz-places', 'nz-lakes-overview', 'nz-lakes', 'nz-rivers', 'nz-river-polygons'],
  peaks: ['nz-peaks', 'nz-peaks-secondary'],
  roads: ['nz-roads-local-halo', 'nz-roads-local', 'nz-roads-highway-halo', 'nz-roads-highway', 'nz-roads-vehicle-tracks', 'nz-roads-highway-label', 'nz-roads-local-label'],
  tracks: ['nz-tracks-halo', 'nz-tracks', 'nz-tracks-label', 'doc-walks-halo', 'doc-walks', 'doc-walk-names'],
  huts: ['nz-huts-dot', 'nz-huts-label'],
  contours: ['nz-contours-minor', 'nz-contours-index', 'nz-contours-label'],
  hunting: ['hunting-fill', 'hunting-line', 'hunting-names'],
  teAraroa: ['te-araroa-halo', 'te-araroa-option', 'te-araroa', 'te-araroa-places', 'te-araroa-places-label', 'te-araroa-food', 'te-araroa-food-label', 'te-araroa-name', 'te-araroa-shops', 'te-araroa-shops-label'],
};

/** Whether a layer shows: its switch is on, and on Topo50 (which has its own tracks,
 * huts and names) only hunting areas are drawn over it, as in the app. */
export function shown(id, settings) {
  for (const [group, ids] of Object.entries(GROUPS)) if (ids.includes(id)) return !!settings[group] && (group === 'hunting' || group === 'teAraroa' || settings.base !== 'topo');
  return true;
}

/** The app's colour grade (renderer appearance.rs) as MapLibre raster paint:
 * natural colour on LINZ photography and, more lightly, on Esri's. */
export function grade(layer, natural) {
  const on = natural && layer !== 'topo';
  const linz = layer === 'aerial';
  return {
    'raster-saturation': on ? (linz ? -0.25 : -0.12) : 0,
    'raster-contrast': on ? (linz ? 0.26 : 0.015) : 0,
    'raster-brightness-min': on && linz ? 0.058 : 0,
    'raster-brightness-max': on && !linz ? 0.99 : 1,
  };
}
export const ESRI_ATTRIBUTION = '<a href="https://www.arcgis.com/home/item.html?id=10df2279f9684e4a9f6a7f08febac2a9" target="_blank" rel="noopener">Esri World Imagery &amp; contributors</a>';

const TRACK_LAYERS = new Set(['nz-tracks-halo', 'nz-tracks', 'nz-tracks-label', 'nz-huts-dot', 'nz-huts-label']);

/** Names placed by labels.js, one per road, track, river or lake in view, and what
 * their geometry is. MapLibre names a line or area once per map tile it crosses, so a
 * long track or a big lake would carry its name several times over. */
export const LABELS = {
  'nz-roads-highway-label': 'line', 'nz-roads-local-label': 'line', 'nz-tracks-label': 'line', 'nz-rivers': 'line',
  'nz-lakes': 'polygon', 'nz-river-polygons': 'polygon', 'doc-walk-names': 'line',
};

/** The whole style. `topography`: the app's layers (data/topography-style.json).
 * `metadata.labels`: where labels.js finds the features each name layer labels. */
export function build(options) {
  const style = buildStyle(options), labels = [];
  style.layers = style.layers.map((l) => {
    if (!LABELS[l.id]) return l;
    labels.push({ id: l.id, kind: LABELS[l.id], source: l.source, sourceLayer: l['source-layer'], filter: l.filter });
    const { 'symbol-placement': _p, 'text-max-angle': _a, 'symbol-spacing': _s, ...layout } = l.layout || {};
    const { 'source-layer': _l, ...rest } = l;
    return { ...rest, source: 'labels', filter: ['==', ['get', '_layer'], l.id], layout };
  });
  style.sources.labels = { type: 'geojson', data: { type: 'FeatureCollection', features: [] }, maxzoom: 16 };
  style.metadata = { labels };
  return style;
}

function buildStyle({ topography, settings, dark, fine = false, topoSize = 256, topoSwitch = 11.5, imageSize: forcedSize }) {
  // Phones (small, dense screens): aerial and satellite imagery half a zoom finer.
  // A raster source picks its tile zoom as round(zoom + log2(512 / tileSize)), so 180
  // in place of 256 asks for the finer tile from the half-way point (about twice the
  // tiles), which a 2–3× screen needs to look as sharp as a computer's.
  const imageSize = forcedSize || (fine ? 180 : 256);
  const visible = (on) => (on ? 'visible' : 'none');
  const topoLayers = topography.map((l) => {
    const layer = { ...l, layout: { ...(l.layout || {}), visibility: visible(shown(l.id, settings)) } };
    // Tracks and huts read their own copy of the tiles, never coarser than zoom 12
    // (LINZ's tiles carry tracks only from 12).
    if (TRACK_LAYERS.has(l.id)) {
      layer.source = 'ridgeline-tracks';
      // The lines are drawn wherever a zoom-12-or-finer tile is: a tilted view's near
      // tiles are a level or two finer than the map's zoom. A map-zoom switch here
      // would leave the near ground with no lines for a zoom level.
      if (l.type === 'line') layer.minzoom = 9;
    }
    return layer;
  });
  // Names and lines go above routes' lines but labels stay on top: split the app's
  // layers into lines and symbols so DOC walks and routes sit between them.
  const lines = topoLayers.filter((l) => l.type !== 'symbol' && l.type !== 'circle');
  const symbols = topoLayers.filter((l) => l.type === 'symbol' || l.type === 'circle');
  return {
    version: 8,
    name: 'Razorback Maps',
    glyphs: urls.glyphs,
    // Fonts drawn on the device: the old-map lettering of the Te Araroa name (OFL).
    'font-faces': { 'IM Fell English SC': new URL('/fonts/im-fell-english-sc/IMFellEnglishSC-Regular.ttf', location.href).href },
    sky: sky(dark),
    sources: {
      // Overview imagery for zooms 5–12 (the app's, averaged from LINZ's zoom-13 photography), LINZ beyond.
      aerial: { type: 'raster', tiles: [`bushline://aerial/{z}/{x}/{y}${settings.natural ? '' : '?raw'}`], tileSize: imageSize, maxzoom: 19, bounds: [160, -53, 185, -28], attribution: config.esri ? `${LINZ_ATTRIBUTION} · gaps: ${ESRI_ATTRIBUTION}` : LINZ_ATTRIBUTION },
      // Coarse covers, always resident under each base (the app's and the old website's
      // coarse cover): where finer tiles have not arrived, slightly blurred imagery shows
      // instead of a gap. A few tiles per view, from the same cached URLs.
      'aerial-cover': { type: 'raster', tiles: [`bushline://aerial/{z}/{x}/{y}${settings.natural ? '' : '?raw'}`], tileSize: 256, maxzoom: 11, bounds: [160, -53, 185, -28] },
      'satellite-cover': { type: 'raster', tiles: ['bushline://satellite/{z}/{x}/{y}'], tileSize: 256, maxzoom: 11, bounds: [160, -53, 185, -28] },
      'topo-cover': { type: 'raster', tiles: [urls.topo], tileSize: 256, maxzoom: 11, bounds: [165, -49, 180, -33] },
      satellite: { type: 'raster', tiles: ['bushline://satellite/{z}/{x}/{y}'], tileSize: imageSize, maxzoom: 19, bounds: [160, -53, 185, -28], attribution: ESRI_ATTRIBUTION },
      // NZ Topo: LINZ's finest real detail is zoom 17 (18 is 17 enlarged), and on dense
      // screens a smaller tile size asks for finer tiles (main.js topoTileSize), as the
      // app picks Topo tiles at one map pixel per screen pixel.
      topo: { type: 'raster', tiles: [urls.topo250], tileSize: topoSize, maxzoom: 17, bounds: [165, -49, 180, -33], attribution: '<a href="https://www.linz.govt.nz/products-services/maps/new-zealand-topographic-maps" target="_blank" rel="noopener">Topo50 © Toitū Te Whenua LINZ</a> CC BY 4.0' },
      topo50: { type: 'raster', tiles: [urls.topo50], tileSize: topoSize, maxzoom: 17, bounds: [165, -49, 180, -33], attribution: '<a href="https://www.linz.govt.nz/products-services/maps/new-zealand-topographic-maps" target="_blank" rel="noopener">Topo50 © Toitū Te Whenua LINZ</a> CC BY 4.0' },
      terrain: { type: 'raster-dem', tiles: [urls.elevation], tileSize: 256, maxzoom: 18, encoding: 'mapbox', bounds: [165, -48, 179, -34] },
      // Both read LINZ's vector tiles through one memory cache (vector.js).
      'ridgeline-topography': { type: 'vector', tiles: ['bushline-vt://{z}/{x}/{y}'], minzoom: 0, maxzoom: 14, bounds: [165, -48, 179, -34] },
      'ridgeline-tracks': { type: 'vector', tiles: ['bushline-vt://{z}/{x}/{y}'], minzoom: 12, maxzoom: 14, bounds: [165, -48, 179, -34] },
      // Cut no finer than 12: finer tiles draw nothing from it (see doc-walks).
      'doc-walks': { type: 'geojson', data: { type: 'FeatureCollection', features: [] }, tolerance: 0.6, buffer: 32, maxzoom: 12, attribution: DOC_ATTRIBUTION },
      'te-araroa': { type: 'geojson', data: { type: 'FeatureCollection', features: [] }, tolerance: 0.4, buffer: 32, maxzoom: 14, attribution: TA_ATTRIBUTION },
      'te-araroa-places': { type: 'geojson', data: { type: 'FeatureCollection', features: [] }, maxzoom: 14 },
      'te-araroa-food': { type: 'geojson', data: { type: 'FeatureCollection', features: [] }, maxzoom: 14 },
      // buffer 512: each tile holds the whole of a name's line, so it is placed once.
      'te-araroa-names': { type: 'geojson', data: { type: 'FeatureCollection', features: [] }, maxzoom: 12, buffer: 512, tolerance: 0 },
      'te-araroa-shops': { type: 'geojson', data: { type: 'FeatureCollection', features: [] }, maxzoom: 16, attribution: '<a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">© OpenStreetMap contributors</a>' },
      hunting: { type: 'geojson', data: { type: 'FeatureCollection', features: [] }, tolerance: 0.6, buffer: 16 },
      route: { type: 'geojson', data: { type: 'FeatureCollection', features: [] }, tolerance: 0, buffer: 32, maxzoom: 18 },
      waypoints: { type: 'geojson', data: { type: 'FeatureCollection', features: [] }, tolerance: 0, maxzoom: 18 },
      selection: { type: 'geojson', data: { type: 'FeatureCollection', features: [] }, tolerance: 0.3, buffer: 32, maxzoom: 18 },
      marker: { type: 'geojson', data: { type: 'FeatureCollection', features: [] } },
      you: { type: 'geojson', data: { type: 'FeatureCollection', features: [] } },
      range: { type: 'geojson', data: { type: 'FeatureCollection', features: [] } },
    },
    terrain: settings.threeD ? { source: 'terrain', exaggeration: settings.relief } : undefined,
    layers: [
      { id: 'background', type: 'background', paint: { 'background-color': dark ? '#16211d' : '#dfe7e1' } },
      { id: 'aerial-cover', type: 'raster', source: 'aerial-cover', layout: { visibility: visible(settings.base === 'aerial') }, paint: { 'raster-fade-duration': 0, 'raster-resampling': 'linear', ...grade('aerial', settings.natural) } },
      { id: 'satellite-cover', type: 'raster', source: 'satellite-cover', layout: { visibility: visible(settings.base === 'satellite') }, paint: { 'raster-fade-duration': 0, 'raster-resampling': 'linear', ...grade('satellite', settings.natural) } },
      { id: 'topo-cover', type: 'raster', source: 'topo-cover', layout: { visibility: visible(settings.base === 'topo') }, paint: { 'raster-fade-duration': 0 } },
      { id: 'aerial', type: 'raster', source: 'aerial', layout: { visibility: visible(settings.base === 'aerial') }, paint: { 'raster-fade-duration': 150, 'raster-resampling': 'linear', ...grade('aerial', settings.natural) } },
      { id: 'satellite', type: 'raster', source: 'satellite', layout: { visibility: visible(settings.base === 'satellite') }, paint: { 'raster-fade-duration': 150, 'raster-resampling': 'linear', ...grade('satellite', settings.natural) } },
      // Topo250 below the chosen zoom, Topo50 from it (main.js topoSwitch).
      { id: 'topo', type: 'raster', source: 'topo', maxzoom: topoSwitch, layout: { visibility: visible(settings.base === 'topo') }, paint: { 'raster-fade-duration': 150 } },
      { id: 'topo50', type: 'raster', source: 'topo50', minzoom: topoSwitch, layout: { visibility: visible(settings.base === 'topo') }, paint: { 'raster-fade-duration': 150 } },
      ...lines,
      // Hunting permit areas: faint blaze-orange hatching inside a dashed outline.
      { id: 'hunting-fill', type: 'fill', source: 'hunting', minzoom: 7, layout: { visibility: visible(settings.hunting) }, paint: { 'fill-pattern': 'hatch', 'fill-opacity': 0.85 } },
      { id: 'hunting-line', type: 'line', source: 'hunting', minzoom: 7, layout: { visibility: visible(settings.hunting), 'line-join': 'round' }, paint: { 'line-color': 'rgba(255,106,19,.94)', 'line-width': ['interpolate', ['linear'], ['zoom'], 7, 1.2, 13, 2.6], 'line-dasharray': [3, 1.5] } },
      // DOC's walks before the map's own tracks (zooms 9–12), handed over per tile by
      // the tile's zoom, not the map's: a tilted view's near tiles are finer (the map's
      // tracks), its distant ones coarser (DOC's lines), so every part of the view has
      // one or the other at every zoom. Zoom-12 tiles carry both, DOC's over the map's
      // own line for the same track, so nothing goes blank while the map's zoom-12
      // tiles arrive; from 13 the map shows its zoom-12 tiles until finer ones load.
      { id: 'doc-walks-halo', type: 'line', source: 'doc-walks', minzoom: 9, filter: ['<', ['zoom'], 13], layout: { visibility: visible(shown('doc-walks', settings)), 'line-join': 'round', 'line-cap': 'round' }, paint: { 'line-color': 'rgba(15,30,31,.8)', 'line-width': 2.6 } },
      { id: 'doc-walks', type: 'line', source: 'doc-walks', minzoom: 9, filter: ['<', ['zoom'], 13], layout: { visibility: visible(shown('doc-walks', settings)), 'line-join': 'round', 'line-cap': 'round' }, paint: { 'line-color': '#f6db94', 'line-width': 1.2, 'line-dasharray': [3, 2] } },
      // Te Araroa, the length of the country: red over the tracks it follows.
      { id: 'te-araroa-halo', type: 'line', source: 'te-araroa', filter: ['==', ['get', 'kind'], 'main'], layout: { visibility: visible(shown('te-araroa', settings)), 'line-join': 'round', 'line-cap': 'round' }, paint: { 'line-color': 'rgba(40,10,16,.75)', 'line-width': ['interpolate', ['linear'], ['zoom'], 5, 2.6, 12, 4.4, 16, 6] } },
      // The options beside it (bypasses, water and ferry legs): dashed.
      { id: 'te-araroa-option', type: 'line', source: 'te-araroa', filter: ['==', ['get', 'kind'], 'option'], layout: { visibility: visible(shown('te-araroa', settings)), 'line-join': 'round' }, paint: { 'line-color': TE_ARAROA_COLOUR, 'line-opacity': 0.9, 'line-width': ['interpolate', ['linear'], ['zoom'], 5, 1, 12, 1.8, 16, 2.6], 'line-dasharray': [2, 1.6] } },
      { id: 'te-araroa', type: 'line', source: 'te-araroa', filter: ['==', ['get', 'kind'], 'main'], layout: { visibility: visible(shown('te-araroa', settings)), 'line-join': 'round', 'line-cap': 'round' }, paint: { 'line-color': TE_ARAROA_COLOUR, 'line-width': ['interpolate', ['linear'], ['zoom'], 5, 1.3, 12, 2.4, 16, 3.4] } },
      // The selected walk or track, and the route being planned.
      // The app's track highlight: golden; with a section chosen, the rest dimmed.
      { id: 'selection-halo', type: 'line', source: 'selection', layout: { 'line-join': 'round', 'line-cap': 'round' }, paint: { 'line-color': '#ffffff', 'line-width': 6, 'line-opacity': ['case', ['boolean', ['get', 'dim'], false], 0.45, 0.85] } },
      { id: 'selection-line', type: 'line', source: 'selection', layout: { 'line-join': 'round', 'line-cap': 'round', 'line-sort-key': ['case', ['boolean', ['get', 'dim'], false], 0, 1] }, paint: { 'line-color': ['case', ['boolean', ['get', 'dim'], false], 'rgba(255,214,120,0.59)', 'rgb(255,201,74)'], 'line-width': 3 } },
      { id: 'route-halo', type: 'line', source: 'route', layout: { 'line-join': 'round', 'line-cap': 'round' }, paint: { 'line-color': '#ffffff', 'line-width': 6, 'line-opacity': 0.85 } },
      { id: 'route-line', type: 'line', source: 'route', layout: { 'line-join': 'round', 'line-cap': 'round' }, paint: { 'line-color': 'rgb(232,121,68)', 'line-width': 3 } },
      ...symbols,
      { id: 'doc-walk-names', type: 'symbol', source: 'doc-walks', minzoom: 11, filter: ['all', ['==', ['get', 'large'], true], ['<', ['zoom'], 12]], layout: { visibility: visible(shown('doc-walks', settings)), 'symbol-placement': 'line-center', 'text-rotation-alignment': 'viewport', 'text-field': ['get', 'name'], 'text-font': ['Open Sans Regular'], 'text-size': 12, 'text-max-angle': 180, 'text-padding': 8, 'symbol-sort-key': 5 }, paint: { 'text-color': '#f6db94', 'text-halo-color': 'rgba(15,30,31,.9)', 'text-halo-width': 1.6 } },
      // "Te Araroa" along the trail when zoomed out.
      // Closer in, each food stop splits into its shops and cafés.
      { id: 'te-araroa-shops', type: 'circle', source: 'te-araroa-shops', minzoom: 11, layout: { visibility: visible(shown('te-araroa-shops', settings)) }, paint: { 'circle-radius': ['interpolate', ['linear'], ['zoom'], 11, 3.2, 16, 5.5], 'circle-color': ['match', ['get', 'kind'], 'supermarket', '#22a35a', 'store', '#8fd6aa', '#d08a3f'], 'circle-stroke-color': '#ffffff', 'circle-stroke-width': 1.5, 'circle-pitch-alignment': 'viewport' } },
      { id: 'te-araroa-shops-label', type: 'symbol', source: 'te-araroa-shops', minzoom: 14, layout: { visibility: visible(shown('te-araroa-shops', settings)), 'text-field': ['get', 'name'], 'text-font': ['Open Sans Regular'], 'text-size': 11, 'text-offset': [0, 0.9], 'text-anchor': 'top', 'text-max-width': 8, 'text-padding': 3, 'symbol-sort-key': 14 }, paint: { 'text-color': '#ecf8ef', 'text-halo-color': 'rgba(12,40,24,.9)', 'text-halo-width': 1.3 } },
      // Te Araroa's campsites and holiday parks (huts are the map's own).
      { id: 'te-araroa-places', type: 'circle', source: 'te-araroa-places', minzoom: 10, filter: ['!', ['get', 'hut']], layout: { visibility: visible(shown('te-araroa-places', settings)) }, paint: { 'circle-radius': ['interpolate', ['linear'], ['zoom'], 10, 1.8, 14, 3.2], 'circle-color': ['match', ['get', 'type'], 'Hostel', TE_ARAROA_COLOUR, 'Lodge', TE_ARAROA_COLOUR, '#fff4f6'], 'circle-stroke-color': ['match', ['get', 'type'], 'Hostel', '#fff4f6', 'Lodge', '#fff4f6', TE_ARAROA_COLOUR], 'circle-stroke-width': ['interpolate', ['linear'], ['zoom'], 10, 1, 14, 1.5], 'circle-pitch-alignment': 'viewport' } },
      { id: 'te-araroa-places-label', type: 'symbol', source: 'te-araroa-places', minzoom: 12, filter: ['!', ['get', 'hut']], layout: { visibility: visible(shown('te-araroa-places', settings)), 'text-field': ['get', 'name'], 'text-font': ['Open Sans Regular'], 'text-size': 11, 'text-offset': [0, 1.1], 'text-anchor': 'top', 'text-max-width': 9, 'text-padding': 4, 'symbol-sort-key': 30 }, paint: { 'text-color': '#ffe3e8', 'text-halo-color': 'rgba(50,10,20,.9)', 'text-halo-width': 1.4 } },
      // Where to buy food on Te Araroa: supermarkets filled, small stores and box-only stops ringed.
      { id: 'te-araroa-food', type: 'circle', source: 'te-araroa-food', minzoom: 6, maxzoom: 11, layout: { visibility: visible(shown('te-araroa-food', settings)) }, paint: { 'circle-radius': ['interpolate', ['linear'], ['zoom'], 6, 2.6, 10, 3.6, 14, 4.6], 'circle-color': ['match', ['get', 'kind'], 'supermarket', '#22a35a', 'store', '#8fd6aa', 'cafe', '#d08a3f', '#ffffff'], 'circle-stroke-color': ['match', ['get', 'kind'], 'box', TE_ARAROA_COLOUR, '#ffffff'], 'circle-stroke-width': ['interpolate', ['linear'], ['zoom'], 6, 1.2, 12, 1.6], 'circle-pitch-alignment': 'viewport' } },
      { id: 'te-araroa-food-label', type: 'symbol', source: 'te-araroa-food', minzoom: 8, maxzoom: 11, layout: { visibility: visible(shown('te-araroa-food', settings)), 'text-field': ['get', 'name'], 'text-font': ['Open Sans Bold'], 'text-size': 11, 'text-offset': [0, 1], 'text-anchor': 'top', 'text-padding': 4, 'symbol-sort-key': 12 }, paint: { 'text-color': '#e7f7ec', 'text-halo-color': 'rgba(12,40,24,.9)', 'text-halo-width': 1.4 } },
      // Above the food and place labels: placed before them, so the trail's name wins.
      // The trail's name as an old map letters a road: wide-spaced old-style small
      // capitals (IM Fell English SC, a 17th-century face, drawn on the device from the
      // style's font-faces) in red on a parchment halo, along the trail's curve beside it. One smoothed
      // line per island and zoom band (te-araroa.js), the band chosen by zoom.
      { id: 'te-araroa-name', type: 'symbol', source: 'te-araroa-names', minzoom: 5, maxzoom: 11.5, filter: ['==', ['get', 'band'], ['step', ['zoom'], 0, 7, 1, 9, 2]], layout: { visibility: visible(shown('te-araroa-name', settings)), 'symbol-placement': 'line-center', 'text-field': 'Te Araroa', 'text-font': ['IM Fell English SC'], 'text-size': ['interpolate', ['linear'], ['zoom'], 5, 17.5, 8, 20, 11, 22.5], 'text-letter-spacing': 0.36, 'text-offset': [0, -1.2], 'text-max-angle': 40, 'text-keep-upright': true, 'text-padding': 2, 'symbol-sort-key': 3 }, paint: { 'text-color': '#b8152f', 'text-halo-color': 'rgba(250,243,228,.88)', 'text-halo-width': 1.5, 'text-halo-blur': 0.6 } },
      { id: 'hunting-names', type: 'symbol', source: 'hunting', minzoom: 11, filter: ['==', ['geometry-type'], 'Point'], layout: { visibility: visible(settings.hunting), 'text-field': ['get', 'name'], 'text-font': ['Open Sans Regular'], 'text-size': 11, 'text-max-width': 10, 'text-padding': 6 }, paint: { 'text-color': '#ffa25f', 'text-halo-color': 'rgba(20,20,20,.85)', 'text-halo-width': 1.4 } },
      // The app's route points: paper with an orange ring; selected, all orange and larger.
      { id: 'waypoints', type: 'circle', source: 'waypoints', paint: { 'circle-radius': ['case', ['boolean', ['get', 'selected'], false], 7.5, 4.5], 'circle-color': ['case', ['boolean', ['get', 'selected'], false], 'rgb(216,117,73)', 'rgb(250,250,245)'], 'circle-stroke-width': ['case', ['boolean', ['get', 'selected'], false], 1, 1.5], 'circle-stroke-color': 'rgb(216,117,73)', 'circle-pitch-alignment': 'viewport' } },
      // A and B: the ends of a section measured on the elevation profile.
      { id: 'range', type: 'circle', source: 'range', paint: { 'circle-radius': 11, 'circle-color': ['get', 'color'], 'circle-stroke-width': 2, 'circle-stroke-color': '#ffffff', 'circle-pitch-alignment': 'viewport' } },
      { id: 'range-label', type: 'symbol', source: 'range', layout: { 'text-field': ['get', 'label'], 'text-font': ['Open Sans Bold'], 'text-size': 12, 'text-allow-overlap': true, 'text-ignore-placement': true, 'text-pitch-alignment': 'viewport' }, paint: { 'text-color': '#ffffff' } },
      { id: 'you-accuracy', type: 'circle', source: 'you', paint: { 'circle-radius': ['interpolate', ['exponential', 2], ['zoom'], 8, ['/', ['get', 'accuracy'], 600], 18, ['/', ['get', 'accuracy'], 0.6]], 'circle-color': 'rgba(47,127,216,.14)', 'circle-stroke-color': 'rgba(47,127,216,.35)', 'circle-stroke-width': 1, 'circle-pitch-alignment': 'map' } },
      { id: 'you', type: 'circle', source: 'you', paint: { 'circle-radius': 7, 'circle-color': '#2f7fd8', 'circle-stroke-width': 2.5, 'circle-stroke-color': '#ffffff', 'circle-pitch-alignment': 'viewport' } },
      { id: 'marker', type: 'circle', source: 'marker', paint: { 'circle-radius': 6, 'circle-color': 'rgb(186,99,53)', 'circle-stroke-width': 2.5, 'circle-stroke-color': '#ffffff', 'circle-pitch-alignment': 'viewport' } },
    ],
  };
}

/** The diagonal hatching for hunting areas (the app's: blaze orange lines). */
export function hatch(pixelRatio = 2) {
  const size = Math.round(12 * pixelRatio);
  const c = typeof OffscreenCanvas === 'function' ? new OffscreenCanvas(size, size) : Object.assign(document.createElement('canvas'), { width: size, height: size });
  const g = c.getContext('2d');
  g.strokeStyle = 'rgba(255,106,19,.30)';
  g.lineWidth = 1.3 * pixelRatio;
  g.beginPath();
  for (const o of [-size, 0, size]) { g.moveTo(o, size); g.lineTo(o + size, 0); }
  g.stroke();
  return { image: g.getImageData(0, 0, size, size), pixelRatio };
}
