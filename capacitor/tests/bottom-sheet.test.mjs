import {test} from 'node:test';
import assert from 'node:assert/strict';
import {sheetStops,nearestStop} from '../mobile/bottom-sheet.js';
test('sheet positions leave an editable map and stay ordered through phone rotation',()=>{
 for(const height of [320,412,640,915,1366]){
  const stops=sheetStops(height);
  assert.equal(stops.length,2);assert.ok(stops[0]<stops[1]);assert.ok(stops[1]<=height-80);
  assert.ok(stops[0]<=160);assert.ok(stops[1]>=height*.5);
 }
});
test('release chooses the closest fixed position',()=>{
 const stops=sheetStops(915);assert.equal(nearestStop(stops,150),0);assert.equal(nearestStop(stops,350),0);assert.equal(nearestStop(stops,590),1);
});
