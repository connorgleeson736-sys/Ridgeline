// The map's style: LINZ aerial photography (with the app's overview imagery
// for zooms 5–12), LINZ Topo50, LINZ elevation in 3D, and the app's topographic
// layers over them (LINZ topographic-v2 vector tiles).
import config from './config.js';

const LINZ = 'https://basemaps.linz.govt.nz/v1';
const key = encodeURIComponent(config.linzKey);
export const NZ_BOUNDS = [[150, -58], [195, -24]];
export const LINZ_ATTRIBUTION = '<a href="https://www.linz.govt.nz/" target="_blank" rel="noopener">© Toitū Te Whenua LINZ</a> CC BY 4.0';
const DOC_ATTRIBUTION = '<a href="https://www.doc.govt.nz/" target="_blank" rel="noopener">Department of Conservation</a> CC BY 4.0';

export const urls = {
  aerial: `${LINZ}/tiles/aerial/WebMercatorQuad/{z}/{x}/{y}.webp?api=${key}`,
  topo: `${LINZ}/tiles/topo-raster/WebMercatorQuad/{z}/{x}/{y}.webp?api=${key}`,
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
};

/** Whether a layer shows: its switch is on, and on Topo50 (which has its own tracks,
 * huts and names) only hunting areas are drawn over it, as in the app. */
export function shown(id, settings) {
  for (const [group, ids] of Object.entries(GROUPS)) if (ids.includes(id)) return !!settings[group] && (group === 'hunting' || settings.base !== 'topo');
  return true;
}

/** The whole style. `topography`: the app's layers (data/topography-style.json). */
export function build({ topography, settings, dark }) {
  const visible = (on) => (on ? 'visible' : 'none');
  const topoLayers = topography.map((l) => ({ ...l, layout: { ...(l.layout || {}), visibility: visible(shown(l.id, settings)) } }));
  // Names and lines go above routes' lines but labels stay on top: split the app's
  // layers into lines and symbols so DOC walks and routes sit between them.
  const lines = topoLayers.filter((l) => l.type !== 'symbol' && l.type !== 'circle');
  const symbols = topoLayers.filter((l) => l.type === 'symbol' || l.type === 'circle');
  return {
    version: 8,
    name: 'Bushline Maps',
    glyphs: urls.glyphs,
    sky: sky(dark),
    sources: {
      // Overview imagery for zooms 5–12 (the app's, averaged from LINZ's zoom-13 photography), LINZ beyond.
      aerial: { type: 'raster', tiles: ['bushline://aerial/{z}/{x}/{y}'], tileSize: 256, maxzoom: 19, bounds: [160, -53, 185, -28], attribution: LINZ_ATTRIBUTION },
      topo: { type: 'raster', tiles: [urls.topo], tileSize: 256, maxzoom: 16, bounds: [165, -49, 180, -33], attribution: '<a href="https://www.linz.govt.nz/products-services/maps/new-zealand-topographic-maps" target="_blank" rel="noopener">Topo50 © Toitū Te Whenua LINZ</a> CC BY 4.0' },
      terrain: { type: 'raster-dem', tiles: [urls.elevation], tileSize: 256, maxzoom: 18, encoding: 'mapbox', bounds: [165, -48, 179, -34] },
      'ridgeline-topography': { type: 'vector', tiles: [urls.vector], minzoom: 0, maxzoom: 14, bounds: [165, -48, 179, -34] },
      'doc-walks': { type: 'geojson', data: { type: 'FeatureCollection', features: [] }, tolerance: 0.6, buffer: 32, attribution: DOC_ATTRIBUTION },
      hunting: { type: 'geojson', data: { type: 'FeatureCollection', features: [] }, tolerance: 0.6, buffer: 16 },
      route: { type: 'geojson', data: { type: 'FeatureCollection', features: [] }, tolerance: 0, buffer: 32, maxzoom: 18 },
      waypoints: { type: 'geojson', data: { type: 'FeatureCollection', features: [] }, tolerance: 0, maxzoom: 18 },
      selection: { type: 'geojson', data: { type: 'FeatureCollection', features: [] }, tolerance: 0.3, buffer: 32, maxzoom: 18 },
      marker: { type: 'geojson', data: { type: 'FeatureCollection', features: [] } },
      you: { type: 'geojson', data: { type: 'FeatureCollection', features: [] } },
    },
    terrain: settings.threeD ? { source: 'terrain', exaggeration: settings.relief } : undefined,
    layers: [
      { id: 'background', type: 'background', paint: { 'background-color': dark ? '#16211d' : '#dfe7e1' } },
      { id: 'aerial', type: 'raster', source: 'aerial', layout: { visibility: visible(settings.base === 'aerial') }, paint: { 'raster-fade-duration': 150, 'raster-resampling': 'linear' } },
      { id: 'topo', type: 'raster', source: 'topo', layout: { visibility: visible(settings.base === 'topo') }, paint: { 'raster-fade-duration': 150 } },
      ...lines,
      // Hunting permit areas: faint blaze-orange hatching inside a dashed outline.
      { id: 'hunting-fill', type: 'fill', source: 'hunting', minzoom: 7, layout: { visibility: visible(settings.hunting) }, paint: { 'fill-pattern': 'hatch', 'fill-opacity': 0.85 } },
      { id: 'hunting-line', type: 'line', source: 'hunting', minzoom: 7, layout: { visibility: visible(settings.hunting), 'line-join': 'round' }, paint: { 'line-color': 'rgba(255,106,19,.94)', 'line-width': ['interpolate', ['linear'], ['zoom'], 7, 1.2, 13, 2.6], 'line-dasharray': [3, 1.5] } },
      // DOC's walks where the map's own tracks are not drawn yet (zooms 9–12).
      { id: 'doc-walks-halo', type: 'line', source: 'doc-walks', minzoom: 9, maxzoom: 12, layout: { visibility: visible(shown('doc-walks', settings)), 'line-join': 'round', 'line-cap': 'round' }, paint: { 'line-color': 'rgba(15,30,31,.8)', 'line-width': 2.6 } },
      { id: 'doc-walks', type: 'line', source: 'doc-walks', minzoom: 9, maxzoom: 12, layout: { visibility: visible(shown('doc-walks', settings)), 'line-join': 'round', 'line-cap': 'round' }, paint: { 'line-color': '#f6db94', 'line-width': 1.2, 'line-dasharray': [3, 2] } },
      // The selected walk or track, and the route being planned.
      { id: 'selection-halo', type: 'line', source: 'selection', layout: { 'line-join': 'round', 'line-cap': 'round' }, paint: { 'line-color': '#ffffff', 'line-width': 8, 'line-opacity': 0.85 } },
      { id: 'selection-line', type: 'line', source: 'selection', layout: { 'line-join': 'round', 'line-cap': 'round' }, paint: { 'line-color': '#2f7fd8', 'line-width': 4 } },
      { id: 'route-halo', type: 'line', source: 'route', layout: { 'line-join': 'round', 'line-cap': 'round' }, paint: { 'line-color': '#ffffff', 'line-width': 7, 'line-opacity': 0.9 } },
      { id: 'route-line', type: 'line', source: 'route', layout: { 'line-join': 'round', 'line-cap': 'round' }, paint: { 'line-color': '#e87944', 'line-width': 3.6 } },
      ...symbols,
      { id: 'doc-walk-names', type: 'symbol', source: 'doc-walks', minzoom: 11, maxzoom: 15, filter: ['==', ['get', 'large'], true], layout: { visibility: visible(shown('doc-walks', settings)), 'symbol-placement': 'line', 'symbol-spacing': 650, 'text-field': ['get', 'name'], 'text-font': ['Open Sans Regular'], 'text-size': 12, 'text-max-angle': 30, 'text-padding': 8, 'symbol-sort-key': 5 }, paint: { 'text-color': '#f6db94', 'text-halo-color': 'rgba(15,30,31,.9)', 'text-halo-width': 1.6 } },
      { id: 'hunting-names', type: 'symbol', source: 'hunting', minzoom: 11, filter: ['==', ['geometry-type'], 'Point'], layout: { visibility: visible(settings.hunting), 'text-field': ['get', 'name'], 'text-font': ['Open Sans Regular'], 'text-size': 11, 'text-max-width': 10, 'text-padding': 6 }, paint: { 'text-color': '#ffa25f', 'text-halo-color': 'rgba(20,20,20,.85)', 'text-halo-width': 1.4 } },
      { id: 'waypoints', type: 'circle', source: 'waypoints', paint: { 'circle-radius': ['case', ['boolean', ['get', 'selected'], false], 8, ['boolean', ['get', 'end'], false], 6.5, 5], 'circle-color': ['case', ['boolean', ['get', 'selected'], false], '#e87944', ['boolean', ['get', 'end'], false], '#e87944', '#fafaf5'], 'circle-stroke-width': 2, 'circle-stroke-color': ['case', ['boolean', ['get', 'end'], false], '#ffffff', '#e87944'], 'circle-pitch-alignment': 'viewport' } },
      { id: 'you-accuracy', type: 'circle', source: 'you', paint: { 'circle-radius': ['interpolate', ['exponential', 2], ['zoom'], 8, ['/', ['get', 'accuracy'], 600], 18, ['/', ['get', 'accuracy'], 0.6]], 'circle-color': 'rgba(47,127,216,.14)', 'circle-stroke-color': 'rgba(47,127,216,.35)', 'circle-stroke-width': 1, 'circle-pitch-alignment': 'map' } },
      { id: 'you', type: 'circle', source: 'you', paint: { 'circle-radius': 7, 'circle-color': '#2f7fd8', 'circle-stroke-width': 2.5, 'circle-stroke-color': '#ffffff', 'circle-pitch-alignment': 'viewport' } },
      { id: 'marker', type: 'circle', source: 'marker', paint: { 'circle-radius': 7, 'circle-color': '#2f7fd8', 'circle-stroke-width': 2.5, 'circle-stroke-color': '#ffffff', 'circle-pitch-alignment': 'viewport' } },
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
