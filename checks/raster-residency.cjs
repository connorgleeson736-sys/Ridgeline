const assert=require('node:assert/strict'),fs=require('node:fs');
const {createPool}=require('../public/raster-residency.js');
(async()=>{
 global.window={requestAnimationFrame:fn=>setTimeout(fn,0),cancelAnimationFrame:clearTimeout};
 const {Style,Evented,LngLat}=await import('../public/assets/maplibre-gl.mjs');
 const map={_maxTileCacheSize:192,_maxTileCacheZoomLevels:8,_requestManager:{transformRequest:async url=>({url})},_refreshExpiredTiles:false};
 const style=Object.assign(new Evented(),{map,tileManagers:{},dispatcher:{},_layers:{},_checkLoaded(){},_validate:()=>false});
 Style.prototype.addSource.call(style,'linz',{type:'raster',tiles:['https://example.test/{z}/{x}/{y}.webp'],tileSize:256,maxzoom:19});
 style.on('error',()=>{});await new Promise(r=>setTimeout(r,10));
 const manager=style.tileManagers.linz;assert.ok(manager._sourceLoaded,'Use actual native inline source metadata path');
 let wanted={z:8,x:100,y:100},loads=0,unloads=0,aborts=0,held=null;
 const source=manager.getSource();source.loadTile=async tile=>{loads++;if(held)await held;tile.texture={size:[256,256],identity:loads};tile.state='loaded';};source.unloadTile=tile=>{unloads++;tile.texture=null;};source.abortTile=()=>{aborts++;};
 const provider={getCenter:t=>t.center,getWrap:()=>0,allowVariableZoom:()=>false,allowWorldCopies:()=>false,distanceToTile2d:()=>0,getTileBoundingVolume({x,y,z}){const shift=wanted.z-z,visible=shift>=0?x===Math.floor(wanted.x/2**shift)&&y===Math.floor(wanted.y/2**shift):Math.floor(x/2**-shift)===wanted.x&&Math.floor(y/2**-shift)===wanted.y;return{intersectsFrustum:()=>visible?1:0};}};
 const transform={center:new LngLat(0,0),elevation:0,pitch:58,bearing:0,bearingInRadians:0,cameraToCenterDistance:500,worldSize:512*2**7,tileSize:512,maxZoom:19,zoom:7,fov:36.87,renderWorldCopies:false,width:256,height:256,populateCache(){},getCameraFrustum:()=>({}),getClippingPlane:()=>null,getCoveringTilesDetailsProvider:()=>provider};
 manager.used=true;const tick=async()=>{manager.update(transform,null);await new Promise(r=>setImmediate(r));manager.update(transform,null);};
 await tick();const first=manager._inViewTiles.getAllTiles()[0],id=first.tileID,texture=first.texture;
 // Native viewport-based cache silently caps this scene at 32, not the
 // configured 192. Reproduce eviction before applying the adapter.
 for(let x=101;x<=145;x++){wanted.x=x;await tick();}
 assert.ok(!manager._outOfViewCache.has(id));const baselineEvictions=unloads;
 manager.clearTiles();loads=0;unloads=0;
 const nativeRemove=manager._removeTile,nativeRetain=manager._updateRetainedTiles,pool=createPool({bytes:32*1048576,pendingLimit:4});pool.attach(manager);
 wanted.x=100;await tick();const retained=manager._inViewTiles.getAllTiles()[0],retainedTexture=retained.texture,retainedId=retained.tileID;
 for(let x=101;x<=160;x++){wanted.x=x;await tick();}
 assert.ok(manager._outOfViewCache.has(retainedId));const before=loads;wanted.x=100;await tick();assert.equal(loads,before,'Return must not fetch/decode/upload');assert.equal(manager.getTile(retainedId),retained);assert.equal(retained.texture,retainedTexture,'Reuse the actual GPU source texture');
 // A change to a coarser desired LOD during orbit must keep already-loaded,
 // currently visible finer images in the native composition.
 wanted={z:7,x:50,y:50};transform.zoom=6;await tick();assert.equal(manager._updateRetainedTiles,nativeRetain,'Keep native parent/child selection instead of injecting mixed LODs');
 // Hidden pending requests finish into residency instead of being cancelled.
 transform.zoom=7;wanted={z:8,x:180,y:100};let finish;held=new Promise(r=>finish=r);manager.update(transform,null);const pending=manager._inViewTiles.getAllTiles().find(t=>t.state==='loading');assert.ok(pending);wanted.x=181;manager.update(transform,null);assert.equal(aborts,0);assert.ok(manager._outOfViewCache.has(pending.tileID));held=null;finish();await new Promise(r=>setImmediate(r));assert.equal(pending.state,'loaded');assert.ok(pool.stats().completedOffscreen>0);
 const pendingLoads=loads;wanted.x=180;await tick();assert.equal(loads,pendingLoads);assert.equal(manager.getTile(pending.tileID),pending);
 // A parked request that fails must be retried on return, not become a
 // permanently cached errored child that keeps its coarse parent on screen.
 let rejectHidden;held=new Promise((r,reject)=>rejectHidden=reject);wanted.x=190;manager.update(transform,null);
 const failed=manager._inViewTiles.getAllTiles().find(t=>t.state==='loading');wanted.x=191;manager.update(transform,null);held=null;rejectHidden(Object.assign(Error('temporary'),{status:503}));await new Promise(r=>setImmediate(r));assert.equal(failed.state,'errored');
 const failedLoads=loads;wanted.x=190;await tick();assert.ok(loads>failedLoads);assert.equal(manager.getTile(failed.tileID).state,'loaded');
 // Changing the pending allowance affects future off-screen retention only.
 pool.setPendingLimit(0);assert.equal(pool.stats().pendingLimit,0);
 let finishLimited;held=new Promise(r=>finishLimited=r);wanted.x=205;manager.update(transform,null);const limited=manager._inViewTiles.getAllTiles().find(t=>t.state==='loading');assert.ok(limited);const abortsBefore=aborts;wanted.x=206;manager.update(transform,null);assert.ok(aborts>abortsBefore);assert.ok(!manager._outOfViewCache.has(limited.tileID),'Zero allowance cannot park a new unfinished request');held=null;finishLimited();await new Promise(r=>setImmediate(r));pool.setPendingLimit(4);assert.equal(pool.stats().pendingLimit,4);
 // Prove the shared byte budget still evicts old entries and teardown is clean.
 for(let x=0;x<180;x++){wanted.x=x;await tick();assert.ok(pool.stats().sourceResidentBytes<=32*1048576);}
 assert.ok(pool.stats().sourceEvictions>0);
 // Share the same byte allowance across providers, rather than allocating the
 // full allowance separately to each basemap.
 Style.prototype.addSource.call(style,'satellite',{type:'raster',tiles:['https://example.test/{z}/{x}/{y}.jpg'],tileSize:256,maxzoom:19});await new Promise(r=>setTimeout(r,10));const second=style.tileManagers.satellite;second.used=true;second.getSource().loadTile=source.loadTile;second.getSource().unloadTile=source.unloadTile;second.getSource().abortTile=source.abortTile;pool.attach(second);
 for(let x=0;x<40;x++){wanted.x=x;second.update(transform,null);await new Promise(r=>setImmediate(r));second.update(transform,null);assert.ok(pool.stats().sourceResidentBytes<=32*1048576);}
 const stats=pool.stats();pool.destroy();assert.equal(manager._removeTile,nativeRemove);assert.equal(pool.stats().sourceResidentTiles,0);manager.clearTiles();second.clearTiles();
 // Exercise the actual production controller wiring as well as the adapter.
 global.RidgeRasterResidency=require('../public/raster-residency.js');const events=new Evented();map.style=style;map.on=events.on.bind(events);map.off=events.off.bind(events);
 const controller=require('../public/native-residency.js').attach(map,{engineVersion:'6.14.0',loadingLimits:()=>({imageryPending:1,demPending:0})});assert.equal(controller.stats().pendingLimit,1);assert.equal(controller.stats().demPendingLimit,0);controller.setLoadingLimits({imageryPending:3,demPending:2});assert.equal(controller.stats().pendingLimit,3);assert.equal(controller.stats().demPendingLimit,2);assert.equal(controller.stats().sourceBudgetBytes,128*1048576);assert.equal(manager._outOfViewCache.max,Number.MAX_SAFE_INTEGER);controller.destroy();assert.equal(manager._removeTile,nativeRemove);delete global.RidgeRasterResidency;
 const result={engine:'MapLibre 6.14.0',nativeStyleAndTileManager:true,nativeViewportCacheLimit:32,baselineEvictions,panAwayTiles:60,revisitFetchDecodeUploads:0,sameNativeTileAndGPUTexture:true,nativeLODSelectionUnmodified:true,inflightCompletesWhileHidden:true,failedHiddenLoadRecovers:true,budgetBounded:true,sharedAcrossProviders:true,productionControllerWired:true,stats,caveat:'Real native TileManager, TileCache and visible-coordinate lifecycle. Texture upload/allocation and frustum are controlled; browser pixels/FPS are not measured.'};fs.writeFileSync('RASTER_RESIDENCY_VALIDATION.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
})().catch(e=>{console.error(e);process.exitCode=1});
