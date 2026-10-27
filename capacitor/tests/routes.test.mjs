import {test} from 'node:test';
import assert from 'node:assert/strict';
import {combineRoutes} from '../mobile/routes.js';
test('combines selected order/direction, removing only identical join points',()=>{
 const a={points:[[170,-43],[171,-44]]},b={points:[[172,-45],[171,-44]],reverse:true};assert.deepEqual(combineRoutes([a,b]),[[170,-43],[171,-44],[172,-45]]);assert.deepEqual(a.points,[[170,-43],[171,-44]]);
 assert.deepEqual(combineRoutes([a,{points:[[173,-45],[174,-46]]}]),[[170,-43],[171,-44],[173,-45],[174,-46]]);
});
test('combination keeps originals and enforces the existing route point limit',()=>{assert.throws(()=>combineRoutes([{points:[[170,-43]]}]));assert.throws(()=>combineRoutes([{points:Array.from({length:2000},(_,i)=>[170+i/10000,-43])},{points:[[171,-44],[172,-45]]}]),/2,000/);});
import {routeLength,routePreview} from '../mobile/routes.js';
test('route summaries measure distance and previews fit horizontal, vertical and single-point routes',()=>{
 assert.ok(Math.abs(routeLength([[170,-43],[170,-44]])-111195)<10);
 for(const points of [[[170,-43]],[[170,-43],[171,-43]],[[170,-43],[170,-44]],[[170,-43],[170.5,-43.5],[171,-43]]]){
  const preview=routePreview(points);assert.equal(preview.length,points.length);for(const p of preview){assert.ok(p.every(Number.isFinite));assert.ok(p[0]>=7.99&&p[0]<=64.01&&p[1]>=7.99&&p[1]<=48.01);}
 }
});
