// Two component reproductions against shipped code. Network waits are injected.
// This is evidence for pressure-aware cancellation, not a production fix.
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';
const require=createRequire(import.meta.url),base=path.resolve(process.argv[2]||'capacitor/www');
globalThis.window={requestAnimationFrame:fn=>setTimeout(fn,0),cancelAnimationFrame:clearTimeout};globalThis.self=globalThis;
const E=await import(pathToFileURL(path.join(base,'assets/maplibre-gl.mjs')));
const map={_maxTileCacheSize:192,_maxTileCacheZoomLevels:8,_requestManager:{transformRequest:async url=>({url})},_refreshExpiredTiles:false};
const style=Object.assign(new E.Evented(),{map,tileManagers:{},dispatcher:{},_layers:{},_checkLoaded(){},_validate:()=>false});style.on('error',()=>{});
E.Style.prototype.addSource.call(style,'photo',{type:'raster',tiles:['https://example.test/{z}/{x}/{y}.webp'],tileSize:256,maxzoom:19});
await new Promise(r=>setTimeout(r,10));const manager=style.tileManagers.photo,source=manager.getSource();
let gate=null,aborts=0;source.loadTile=async tile=>{if(gate)await gate;tile.texture??={size:[256,256],owner:tile.tileID.key};tile.state='loaded';};source.abortTile=()=>{aborts++;};source.unloadTile=tile=>{tile.texture=null;};
// Get an actual native ID constructor without adding an engine export.
let transform;E.Style.prototype._setProjectionInternal.call({map:{migrateProjection:t=>transform=t},tileManagers:{},_globalState:{}},'mercator');transform.resize(412,820);transform.setCenter(new E.LngLat(170,-43));transform.setZoom(13);
const id=E.Map.prototype.coveringTiles.call({_camera:{transform}},{tileSize:256,minzoom:0,maxzoom:19})[0],ID=id.constructor;
const pool=require(path.join(base,'raster-residency.js')).createPool({bytes:32*1048576,pendingLimit:0});pool.attach(manager);
const ids=Array.from({length:12},(_,i)=>new ID(13,0,13,3000+i,5000));
for(const q of ids)manager._addTile(q);await new Promise(r=>setImmediate(r));
const owners=ids.map(q=>manager.getTile(q).texture);
let release;gate=new Promise(r=>release=r);
for(const q of ids)manager._reloadTile(q.key,'reloading');
assert.ok(ids.every(q=>manager.getTile(q).state==='reloading'));
for(const q of ids)manager._removeTile(q.key);
const retention={configuredPendingLimit:0,pendingOffscreen:pool.stats().pendingOffscreen,aborted:aborts,
 originalTexturesRetained:ids.every((q,i)=>manager._outOfViewCache.getByKey(q.wrapped().key).texture===owners[i])};
assert.equal(retention.pendingOffscreen,12);assert.equal(aborts,0);assert.ok(retention.originalTexturesRetained);
release();await new Promise(r=>setImmediate(r));pool.destroy();manager.clearTiles();

// Extract exactly the generated/published queue, including app-only patches.
const text=fs.readFileSync(path.join(base,'assets/maplibre-gl.mjs'),'utf8'),start=text.indexOf('let Un;'),end=text.indexOf('Un.resetRequestQueue();',start);
assert.ok(start>=0&&end>start);const queueSource=text.slice(start,end+'Un.resetRequestQueue();'.length)+'\nglobalThis.queue=Un;';
async function replay(preempt){
 const requests=[];let moving=false;class Abort extends Error{constructor(){super('Cancelled');this.name='AbortError';}}
 const env={console,performance,setTimeout,clearTimeout,AbortController,HTMLImageElement:class{},On:{MAX_PARALLEL_IMAGE_REQUESTS:12,MAX_PARALLEL_IMAGE_REQUESTS_PER_FRAME:8},
  un:Abort,fn:signal=>{if(signal.aborted)throw new Abort();},I(){},nt:e=>e,Bt:()=>true,kn:()=>false,Wt:()=>false,createImageBitmap(){},Gt:async()=>({width:256,height:256,close(){}})};
 env.self=env;
 env.Rn=(request,controller)=>new Promise((resolve,reject)=>{
  requests.push({url:request.url,at:performance.now()});const timer=setTimeout(()=>{controller.signal.removeEventListener('abort',cancel);resolve({data:new Uint8Array(32).buffer});},request.url.includes('/old/')?1000:200);
  const cancel=()=>{clearTimeout(timer);reject(new Abort());};controller.signal.addEventListener('abort',cancel,{once:true});if(controller.signal.aborted)cancel();
 });
 vm.runInNewContext(queueSource,env);const queue=env.queue,controllers=Array.from({length:12},()=>new AbortController());queue.addThrottleControl(()=>moving);
 const old=controllers.map((c,i)=>queue.getImage({url:'https://fixture/old/'+i},c).catch(e=>e));
 assert.equal(queue.stats().active,12);moving=true;const at=performance.now();
 const visible=Array.from({length:4},(_,i)=>queue.getImage({url:'https://fixture/visible/'+i},new AbortController()));
 assert.equal(queue.stats().queued,4);const blockedBeforePreemption=queue.stats();
 if(preempt)for(const c of controllers)c.abort();
 await Promise.all(visible);const elapsedMs=performance.now()-at;
 const firstVisibleStartMs=Math.min(...requests.filter(r=>r.url.includes('/visible/')).map(r=>r.at))-at;
 await Promise.all(old);return{elapsedMs,firstVisibleStartMs,blockedBeforePreemption};
}
const baseline=await replay(false),pressurePreemption=await replay(true);
assert.ok(baseline.firstVisibleStartMs>900);assert.ok(pressurePreemption.firstVisibleStartMs<200);
console.log(JSON.stringify({retention,queueReplay:{fixture:'Actual shipped admission queue, default limits 12 stationary / 8 moving; 12 obsolete transfers with injected 1000 ms delivery, then movement and 4 visible transfers with injected 200 ms delivery; decoding/GPU/network mocked',baseline,pressurePreemption,
 visibleReadySavingMs:baseline.elapsedMs-pressurePreemption.elapsedMs},productionFixImplemented:false,
 requiredIntegration:'Cancel obsolete transfer subscribers under foreground pressure while preserving valid old textures/DEMs and retry-on-return; real raster-source cancellation state still needs integration checks'},null,2));
