// Run from the repository root. Keep the camera dirty on every simulated frame.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';
const code = readFileSync('public/selection-interval.js', 'utf8');
function replay(emitContent) {
  let now = 0, nextId = 0, drawings = 0, updates = 0;
  const handles = new Map(), listeners = new Map();
  const env = {
    performance: {now: () => now},
    setTimeout(fn, delay) {handles.set(++nextId, {fn, at: now + delay}); return nextId;},
    clearTimeout(id) {handles.delete(id);}
  };
  runInNewContext(code, env);
  const source = {update() {updates++;}};
  const map = {
    style: {tileManagers: {linz: source}}, _update() {}, triggerRepaint() {},
    on(name, fn) {listeners.set(name, fn);}, off(name) {listeners.delete(name);},
    _render() {source.update(); drawings++;}
  };
  const controller = env.RidgeSelectionInterval.attach(map, {
    interval: () => 100, engine: {getVersion: () => '6.14.0'}
  });
  listeners.get('movestart')();
  for (let frame = 0; frame < 60; frame++) {
    now = frame * 1000 / 60;
    for (const [id, timer] of handles) {
      if (timer.at <= now) {handles.delete(id); timer.fn();}
    }
    if (emitContent) listeners.get('sourcedata')({sourceId: 'linz', sourceDataType: 'content'});
    map._render();
  }
  controller.destroy();
  return {drawings, updates};
}
const quiet = replay(false), streaming = replay(true);
assert.equal(quiet.drawings, 60);
assert.ok(quiet.updates >= 9 && quiet.updates <= 10);
assert.deepEqual(streaming, {drawings: 60, updates: 60});
console.log({quiet, streaming});
