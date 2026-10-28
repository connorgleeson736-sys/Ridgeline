import {test} from 'node:test';import assert from 'node:assert/strict';import {createCoordinateIndexCache} from '../scripts/coordinate-index-cache.mjs';import {readFile} from 'node:fs/promises';import vm from 'node:vm';import {execFileSync} from 'node:child_process';
async function load(app){const c={module:{exports:{}}};c.globalThis=c;vm.runInNewContext(await readFile(new URL(app?'../www/native-residency.js':'../../public/native-residency.js',import.meta.url),'utf8'),c);return c.module.exports;}
test('stable coordinate membership builds once, reorders cheaply and detects mutation/replacement',()=>{
 const a={canonical:{z:12,x:1,y:2}},b={canonical:{z:12,x:2,y:2}},m={_renderableTilesKeys:['a','b'],_tiles:{a:{tileID:a},b:{tileID:b}}};const cache=createCoordinateIndexCache();let built=0;
 const build=()=>({descendants:new Map(),exact:new Map(),order:new Map(m._renderableTilesKeys.map((k,i)=>[k,i])),version:++built});
 const first=cache.get(m,build);for(let i=0;i<100;i++)assert.equal(cache.get(m,build),first);assert.equal(built,1);
 m._renderableTilesKeys.reverse();const reordered=cache.get(m,build);assert.equal(built,1);assert.notEqual(reordered,first);assert.equal(first.order.get('a'),0);assert.equal(reordered.order.get('a'),1);
 a.canonical.x++;cache.get(m,build);assert.equal(built,2);m._tiles.a.tileID={canonical:{...a.canonical}};cache.get(m,build);assert.equal(built,3);
 delete m._tiles.b;m._renderableTilesKeys=['a'];cache.get(m,build);assert.equal(built,4);
});
test('index reuse preserves native matrix calls and exact candidate ordering',async()=>{
 const before=await load(false),after=await load(true),m={_renderableTilesKeys:['a','b','c'],_tiles:{a:{tileID:{canonical:{z:4,x:4,y:6}}},b:{tileID:{canonical:{z:5,x:8,y:12}}},c:{tileID:{canonical:{z:4,x:14,y:14}}}}};
 let camera=0,calls=0;function regular(){calls++;return{keys:this._renderableTilesKeys.slice(),matrixVersion:camera};}
 const id={canonical:{z:4,x:4,y:6}},indices=new Set();
 for(let i=0;i<100;i++){camera=i;if(i===50)m._renderableTilesKeys.reverse();const scope={};const expected=before.relatedCoordinates(m,regular,id,{}),actual=after.relatedCoordinates(m,regular,id,scope);assert.equal(JSON.stringify(actual),JSON.stringify(expected));indices.add(scope.coordinateIndex);}
 assert.equal(calls,200);assert.equal(indices.size,2,'Only ordering changed; native matrices are still fresh each call');
});
test('real pinned engine retains textures, invalidates changed content and reduces wrapper allocation',()=>{
 const run=baseline=>execFileSync(process.execPath,[new URL('../scripts/native-residency-check.cjs',import.meta.url).pathname,...(baseline?['--baseline']:[])],{encoding:'utf8'});
 const old=run(true).split('\n').filter(Boolean).map(s=>JSON.parse(s)),fresh=run(false).split('\n').filter(Boolean).map(s=>JSON.parse(s));
 assert.equal(old[0].releaseHookFunctions,101);assert.equal(fresh[0].releaseHookFunctions,2);assert.equal(fresh[1].returnReusesSameTileAndTexture,true);assert.equal(fresh[1].nativeFingerprintInvalidation,true);assert.equal(fresh[1].cleanup,'passed');
});
test('release interception restores original methods after a native update throws',async()=>{
 const {retainDrapes}=await load(true);let called=0;const release=function(){called++;throw Error('native failure');},tile={tileID:{key:'a'},rttObjects:[],releaseRTT:release};
 const manager={_tiles:{a:tile},_renderableTilesKeys:['a'],update(){tile.releaseRTT();},releaseAllRTT(){},releaseRTT(){},destruct(){}};
 const cache=retainDrapes(manager,{});for(let i=0;i<2;i++){assert.throws(()=>manager.update(),/native failure/);assert.equal(tile.releaseRTT,release);}assert.equal(called,2);cache.destroy();
});
test('unchanged tile sets skip transition maps while preserving native updates',async()=>{
 async function measure(app){let allocations=0,updates=0;class CountingMap extends Map{constructor(...args){super(...args);allocations++;}}class CountingSet extends Set{constructor(...args){super(...args);allocations++;}}
  const c={module:{exports:{}},Map:CountingMap,Set:CountingSet};c.globalThis=c;vm.runInNewContext(await readFile(new URL(app?'../www/native-residency.js':'../../public/native-residency.js',import.meta.url),'utf8'),c);
  const tile={tileID:{key:'a'},rttObjects:[],releaseRTT(){}};const m={_tiles:{a:tile},_renderableTilesKeys:['a'],update(){updates++;return false;},releaseAllRTT(){},releaseRTT(){},destruct(){}};
  const control=c.module.exports.retainDrapes(m,{});allocations=0;for(let i=0;i<100;i++)m.update();const result={allocations,updates,skips:control.stats().stableTransitionSkips};control.destroy();return result;
 }
 const before=await measure(false),after=await measure(true);assert.equal(before.updates,100);assert.equal(after.updates,100);assert.equal(after.skips,100);assert.equal(before.allocations,1000);assert.equal(after.allocations,200);
});
test('release hook reuse accepts a changed underlying method and preserves arguments and receiver',async()=>{
 const {retainDrapes}=await load(true);let first=0,second=0;const tile={tileID:{key:'a'},rttObjects:[]};
 const original=function(value){assert.equal(this,tile);assert.equal(value,7);first++;};tile.releaseRTT=original;
 const m={_tiles:{a:tile},_renderableTilesKeys:['a'],update(){tile.releaseRTT(7);return false;},releaseAllRTT(){},releaseRTT(){},destruct(){}};
 const control=retainDrapes(m,{});m.update();assert.equal(tile.releaseRTT,original);
 const replacement=function(value){assert.equal(this,tile);assert.equal(value,7);second++;};tile.releaseRTT=replacement;m.update();m.update();assert.equal(tile.releaseRTT,replacement);assert.equal(first,1);assert.equal(second,2);control.destroy();assert.equal(tile.releaseRTT,replacement);
});
