// Run from the root of Ridgeline at d880143. Requires Node 22+.
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
const source = name => pathToFileURL(resolve('public', name));
const engine = source('assets/maplibre-gl.mjs');
const hash = createHash('sha256').update(readFileSync(engine)).digest('hex');
assert.equal(hash, '37611c5ee952f5fcb76215a1be4b9fdcfde8bf415a8493db435fd05d3a7727fb');
const E = await import(engine);
const adapter = async name => (await import(source(name))).default;
globalThis.RidgeDEMRevision = await adapter('dem-revision.js');
globalThis.RidgeSurfaceFocus = await adapter('surface-focus.js');
const visibility = await adapter('terrain-visibility.js');
const quality = await adapter('performance.js');
const adaptive = await adapter('adaptive-terrain.js');

// Real terrain manager, Mercator transform, source lookup and visibility index.
// Only the Android UI, tile storage and elevation extrema are fixtures.
const loaded = new Map();
const demSource = {
  _source: {tileSize: 256, minzoom: 0, maxzoom: 18, type: 'raster-dem'},
  map: {painter: {}}, reload() {}, update() {}, getSource() {return this._source;},
  _inViewTiles: {getTileById: key => loaded.get(key), getAllTiles: () => [...loaded.values()]},
  getTileByID: key => loaded.get(key), _outOfViewCache: {getByKey: () => null, data: {}}
};
const map = {
  on() {}, off() {}, terrain: null, painter: {}, _camera: {setTerrain() {}}, fire() {},
  style: {_checkLoaded() {}, off() {}, on() {}, tileManagers: {terrain: demSource},
    _layers: {}, triggerSymbolPlacement() {}}
};
E.Map.prototype.setTerrain.call(map, {source: 'terrain', exaggeration: 1});
let transform;
E.Style.prototype._setProjectionInternal.call({
  map: {migrateProjection: value => {transform = value;}}, tileManagers: {}, _globalState: {}
}, 'mercator');
const position = transform;
position.resize(390, 844);
position.setRenderWorldCopies(false);
position.setPitch(58);
position.setBearing(-24);
position.setZoom(13);
position.setCenter(new E.LngLat(170.141, -43.595));
position.setElevation(2300);

const terrain = map.terrain;
const manager = terrain.tileManager;
manager.update(position);
const TileID = manager.getRenderableTiles()[0].tileID.constructor;
const summit = E.MercatorCoordinate.fromLngLat([170.141, -43.595]);
for (const z of [2, 5, 8, 10, 11, 12, 13, 14]) {
  const columns = 2 ** z;
  const radius = z < 8 ? 0 : 2;
  const centerX = Math.floor(summit.x * columns);
  const centerY = Math.floor(summit.y * columns);
  for (let x = centerX - radius; x <= centerX + radius; x++) {
    for (let y = centerY - radius; y <= centerY + radius; y++) {
      const tileID = new TileID(z, 0, z, x, y);
      const dem = {min: 500 + (x % 13) * 30, max: 2600 + (y % 11) * 69, dim: 256};
      loaded.set(tileID.key, {tileID, dem});
    }
  }
}
visibility.attach(map, {engineVersion: '6.14.0'});

let focus;
const provider = position.getCoveringTilesDetailsProvider();
const originalBounds = provider.getTileBoundingVolume;
const originalDistance = provider.distanceToTile2d;
provider.distanceToTile2d = function(x, y, id, box) {
  focus.tile = id;
  return originalDistance.call(this, x, y, id, box);
};
function policy(levels, ratio, bias, legacy) {
  const holder = {};
  E.Map.prototype.setSourceTileLodParams.call({getSource: () => holder, _update() {}}, levels, ratio, 's');
  return quality.focusLod(holder.calculateTileZoom, bias, () => focus, legacy);
}
const passes = [
  {tileSize: 256, maxzoom: 19, roundZoom: true, calculateTileZoom: policy(6, 3.5, .25, true)},
  {tileSize: 431, maxzoom: 18, calculateTileZoom: policy(8, 3.5, 0, true)},
  {tileSize: 431, maxzoom: 22, calculateTileZoom: policy(10.3, 2.75, 0, false)}
].map(pass => ({...pass, terrain, minzoom: 0}));
const frames = 96;
let sourceLookups = 0;
const nativeLookup = manager.getSourceTile;
manager.getSourceTile = function(...args) {
  sourceLookups++;
  return nativeLookup.apply(this, args);
};

// This is a bounded experiment for one stationary set of DEMs. A mutable
// production source also needs a revision contract and observable cache-touch handling.
function run(mode, collect = false) {
  const memo = new Map();
  const ordered = [];
  let calls = 0, builds = 0, selected = 0;
  sourceLookups = 0;
  provider.getTileBoundingVolume = function(id, wrap, elevation, options) {
    calls++;
    if (mode !== 'original') {
      assert.equal(wrap, 0);
      // Disjoint zoom intervals for z<=22. This fixture has one terrain instance,
      // guaranteed finite conservative bounds, and no world wrap.
      const key = 4 ** id.z + id.y * 2 ** id.z + id.x;
      const hit = memo.get(key);
      if (hit) return hit;
      const box = originalBounds.call(this, id, wrap, elevation, options);
      builds++;
      memo.set(key, box);
      if (memo.size > 2048) memo.delete(memo.keys().next().value);
      return box;
    }
    builds++;
    return originalBounds.call(this, id, wrap, elevation, options);
  };
  for (let i = 0; i < frames; i++) {
    if (mode === 'per frame') memo.clear();
    position.setCenter(new E.LngLat(170.141 + Math.sin(i/16)*.006, -43.595 + Math.cos(i/17)*.002));
    position.setZoom(12.7 + .7*Math.sin(i/19));
    position.setPitch(53 + 11*Math.sin(i/12));
    position.setBearing(-24 + i*.19);
    const pivot = E.MercatorCoordinate.fromLngLat(position.center, position.elevation);
    const range = position.cameraToCenterDistance / position.worldSize;
    focus = {tile: null, hit: pivot, pivot, range, pivotRange: range, fov: position.fov};
    for (const pass of passes) {
      const ids = E.Map.prototype.coveringTiles.call({_camera: {transform: position}}, pass);
      selected += ids.length;
      if (collect) ordered.push(ids.map(id => id.key));
    }
  }
  return {ordered, calls, builds, selected, sourceLookups};
}
const modes = ['original', 'per frame', 'stable DEM'];
const reference = run('original', true).ordered;
for (const mode of modes) {
  const {ordered, ...counts} = run(mode, true);
  assert.deepEqual(ordered, reference);
  console.log(mode, counts);
}
for (let i = 0; i < 4; i++) for (const mode of modes) run(mode);
const samples = new Map(modes.map(mode => [mode, []]));
for (let round = 0; round < 12; round++) {
  for (let k = 0; k < modes.length; k++) {
    const mode = modes[(k + round) % modes.length];
    const start = performance.now();
    run(mode);
    samples.get(mode).push((performance.now() - start) / frames);
  }
}
for (const [mode, times] of samples) {
  times.sort((a,b) => a-b);
  console.log(mode, 'median ms per three passes incl. camera setup', ((times[5]+times[6])/2).toFixed(3));
}
console.log('indices including skirts', [128,64,32].map(size => [size, adaptive.geometry(size, true).triangles]));
