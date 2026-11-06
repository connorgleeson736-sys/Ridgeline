// Real pinned engine TileManager + generated residency pool. The source mock
// mirrors the engine raster loadTile/abortTile semantics: abortTile only aborts
// the controller; an abort rejects, leaving state 'errored' with the texture.
import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import {createRequire} from 'node:module';
import {fileURLToPath,pathToFileURL} from 'node:url';
const require=createRequire(import.meta.url),base=fileURLToPath(new URL('../www/',import.meta.url));
globalThis.window??={requestAnimationFrame:fn=>setTimeout(fn,0),cancelAnimationFrame:clearTimeout};globalThis.self??=globalThis;
const E=await import(pathToFileURL(path.join(base,'assets/maplibre-gl.mjs')));
const tick=()=>new Promise(r=>setTimeout(r,40));

async function fixture(pendingLimit){
 const map={_maxTileCacheSize:192,_maxTileCacheZoomLevels:8,_requestManager:{transformRequest:async url=>({url})},_refreshExpiredTiles:false};
 const style=Object.assign(new E.Evented(),{map,tileManagers:{},dispatcher:{},_layers:{},_checkLoaded(){},_validate:()=>false});style.on('error',()=>{});
 E.Style.prototype.addSource.call(style,'photo',{type:'raster',tiles:['https://example.test/{z}/{x}/{y}.webp'],tileSize:256,maxzoom:19});
 await tick();const manager=style.tileManagers.photo,source=manager.getSource();
 const net={gate:null,loads:0,aborts:0,version:0};
 source.loadTile=async tile=>{net.loads++;const controller=tile.abortController=new AbortController();
  try{if(net.gate)await Promise.race([net.gate,new Promise((_,reject)=>controller.signal.addEventListener('abort',()=>reject(Object.assign(Error('Cancelled'),{name:'AbortError'})),{once:true}))]);
   delete tile.abortController;if(tile.aborted){tile.state='unloaded';return;}tile.texture={size:[256,256],version:net.version};tile.state='loaded';}
  catch(error){delete tile.abortController;if(tile.aborted)tile.state='unloaded';else{tile.state='errored';throw error;}}};
 source.abortTile=async tile=>{if(tile.abortController){net.aborts++;tile.abortController.abort();delete tile.abortController;}};
 source.unloadTile=tile=>{tile.texture=null;};
 let transform;E.Style.prototype._setProjectionInternal.call({map:{migrateProjection:t=>transform=t},tileManagers:{},_globalState:{}},'mercator');transform.resize(412,820);transform.setCenter(new E.LngLat(170,-43));transform.setZoom(13);
 const ID=E.Map.prototype.coveringTiles.call({_camera:{transform}},{tileSize:256,minzoom:0,maxzoom:19})[0].constructor;
 const pool=require(path.join(base,'raster-residency.js')).createPool({bytes:64*1048576,pendingLimit});pool.attach(manager);
 return {manager,pool,net,ID};
}

test('obsolete off-screen reloads release their transfer but keep their textures, then reload on return',async()=>{
 const {manager,pool,net,ID}=await fixture(4);
 const ids=Array.from({length:12},(_,i)=>new ID(13,0,13,3000+i,5000));
 for(const q of ids)manager._addTile(q);await tick();
 const owners=ids.map(q=>manager.getTile(q).texture);assert.ok(owners.every(t=>t&&t.version===0));
 let release;net.gate=new Promise(r=>release=r);net.version=1;
 for(const q of ids)manager._reloadTile(q.key,'reloading');
 for(const q of ids)manager._removeTile(q.key);await tick();
 // Reloads with resident data never exceed the off-screen allowance (4).
 const live=ids.map(q=>manager._outOfViewCache.getByKey(q.wrapped().key)).filter(t=>t.state==='reloading');
 assert.equal(live.length,4);assert.equal(net.aborts,8);assert.equal(pool.stats().obsoleteReleased,8);
 assert.equal(pool.stats().pendingOffscreen,4);
 ids.forEach((q,i)=>assert.equal(manager._outOfViewCache.getByKey(q.wrapped().key).texture,owners[i]));
 // Returning tiles are adopted with their old texture and refresh themselves.
 for(const q of ids)manager._addTile(q);
 for(const q of ids)assert.ok(manager.getTile(q).texture,'texture visible immediately');
 release();net.gate=null;await tick();await tick();
 for(const q of ids){const t=manager.getTile(q);assert.equal(t.state,'loaded');assert.equal(t.texture.version,1);}
 pool.destroy();manager.clearTiles();
});

test('zero allowance releases every obsolete reload; first loads without data are unchanged',async()=>{
 const {manager,pool,net,ID}=await fixture(0);
 const ids=Array.from({length:6},(_,i)=>new ID(13,0,13,4000+i,5000));
 for(const q of ids)manager._addTile(q);await tick();
 net.gate=new Promise(()=>{});
 for(const q of ids)manager._reloadTile(q.key,'reloading');
 for(const q of ids)manager._removeTile(q.key);await tick();
 assert.equal(net.aborts,6);
 for(const q of ids){const t=manager._outOfViewCache.getByKey(q.wrapped().key);assert.equal(t.state,'errored');assert.ok(t.texture);}
 // A fresh tile with no data and no allowance follows the original native path (aborted and dropped).
 const fresh=new ID(13,0,13,4100,5000);manager._addTile(fresh);manager._removeTile(fresh.key);
 assert.equal(manager._outOfViewCache.getByKey(fresh.wrapped().key)??null,null);
 pool.destroy();manager.clearTiles();
});
