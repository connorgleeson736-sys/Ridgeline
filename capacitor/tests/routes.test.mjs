import {test} from 'node:test';
import assert from 'node:assert/strict';
import {combineRoutes} from '../mobile/routes.js';
test('combines selected order/direction, removing only identical join points',()=>{
 const a={points:[[170,-43],[171,-44]]},b={points:[[172,-45],[171,-44]],reverse:true};assert.deepEqual(combineRoutes([a,b]),[[170,-43],[171,-44],[172,-45]]);assert.deepEqual(a.points,[[170,-43],[171,-44]]);
 assert.deepEqual(combineRoutes([a,{points:[[173,-45],[174,-46]]}]),[[170,-43],[171,-44],[173,-45],[174,-46]]);
});
test('combination keeps originals and enforces the existing route point limit',()=>{assert.throws(()=>combineRoutes([{points:[[170,-43]]}]));assert.throws(()=>combineRoutes([{points:Array.from({length:2000},(_,i)=>[170+i/10000,-43])},{points:[[171,-44],[172,-45]]}]),/2,000/);});
