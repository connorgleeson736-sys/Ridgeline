const assert=require('node:assert/strict'),fs=require('node:fs');
const {createPool}=require('../public/raster-residency.js');
const {retainDrapes,terrainEventContext,attach}=require('../public/native-residency.js');
(async()=>{
 global.window={requestAnimationFrame:fn=>setTimeout(fn,0),cancelAnimationFrame:clearTimeout};
 const {Style,Evented,LngLat,Map:NativeMap}=await import('../public/assets/maplibre-gl.mjs');
 const map={_maxTileCacheSize:192,_maxTileCacheZoomLevels:8,_requestManager:{transformRequest:async url=>({url})},_refreshExpiredTiles:false};
 const style=Object.assign(new Evented(),{map,tileManagers:{},dispatcher:{},_layers:{},_checkLoaded(){},_validate:()=>false});map.style=style;style.on('error',()=>{});
 Style.prototype.addSource.call(style,'terrain',{type:'raster-dem',tiles:['https://example.test/{z}/{x}/{y}'],tileSize:256,maxzoom:18});await new Promise(r=>setTimeout(r,10));
 const sourceManager=style.tileManagers.terrain,source=sourceManager.getSource();
 let wanted={z:8,x:100,y:100},loads=0,unloads=0,aborts=0,held=null;
 source.loadTile=async tile=>{loads++;if(held)await held;tile.dem={stride:260,dim:256,data:new Uint32Array(260*260),backfillBorder(){}};tile.demTexture={size:[260,260],identity:loads};tile.state='loaded';};
 source.unloadTile=tile=>{unloads++;tile.dem=null;tile.demTexture=null;};source.abortTile=()=>{aborts++;};
 const provider={getCenter:t=>t.center,getWrap:()=>0,allowVariableZoom:()=>false,allowWorldCopies:()=>false,distanceToTile2d:()=>0,getTileBoundingVolume({x,y,z}){const d=wanted.z-z,visible=d>=0?x===Math.floor(wanted.x/2**d)&&y===Math.floor(wanted.y/2**d):Math.floor(x/2**-d)===wanted.x&&Math.floor(y/2**-d)===wanted.y;return{intersectsFrustum:()=>visible?1:0};}};
 const transform={center:new LngLat(170,-43.5),elevation:3000,pitch:58,bearing:0,bearingInRadians:0,cameraToCenterDistance:500,worldSize:512*2**8,tileSize:512,maxZoom:19,zoom:8,fov:36.87,renderWorldCopies:false,width:256,height:256,populateCache(){},getCameraFrustum:()=>({}),getClippingPlane:()=>null,getCoveringTilesDetailsProvider:()=>provider};
 sourceManager.usedForTerrain=true;sourceManager.tileSize=512;
 const tick=async()=>{sourceManager.update(transform,null);await new Promise(r=>setImmediate(r));sourceManager.update(transform,null);};
 await tick();const baselineTile=sourceManager._inViewTiles.getAllTiles().find(t=>t.tileID.canonical.z===8),baselineId=baselineTile.tileID;
 for(let x=101;x<=128;x++){wanted.x=x;await tick();}
 const baselineMiss=!sourceManager._outOfViewCache.has(baselineId),baselineUnloads=unloads;assert.ok(baselineMiss,'Reproduce native decoded DEM eviction');
 sourceManager.clearTiles();loads=unloads=0;
 const nativeRemove=sourceManager._removeTile,pool=createPool({types:['raster-dem'],bytes:48*1048576,pendingLimit:2});pool.attach(sourceManager);
 wanted.x=100;await tick();const first=sourceManager.getTile(baselineId),dem=first.dem,texture=first.demTexture;
 for(let x=101;x<=128;x++){wanted.x=x;await tick();}
 assert.ok(sourceManager._outOfViewCache.has(baselineId),'Retain decoded terrain through this pan');
 const before=loads;wanted.x=100;await tick();assert.equal(loads,before);assert.equal(sourceManager.getTile(baselineId),first);assert.equal(first.dem,dem);assert.equal(first.demTexture,texture);
 // In-flight DEM work also survives leaving view, up to the pending limit.
 let finish;held=new Promise(r=>finish=r);wanted.x=180;sourceManager.update(transform,null);const pending=sourceManager._inViewTiles.getAllTiles().find(t=>t.tileID.canonical.z===8&&t.state==='loading');assert.ok(pending);
 wanted.x=181;sourceManager.update(transform,null);assert.ok(sourceManager._outOfViewCache.has(pending.tileID));assert.ok(!pending.aborted);held=null;finish();await new Promise(r=>setImmediate(r));assert.equal(pending.state,'loaded');
 const pendingBefore=loads;wanted.x=180;await tick();assert.equal(sourceManager.getTile(pending.tileID),pending);assert.equal(loads,pendingBefore);
 // Combined decoded + worker + prepared GPU storage is accounted for, and
 // only hidden native-cache entries may be evicted under pressure.
 for(let x=0;x<90;x++){wanted.x=x;await tick();assert.ok(pool.stats().sourceResidentBytes<=48*1048576);}
 assert.ok(pool.stats().sourceEvictions>0);const demStats=pool.stats();pool.flush();assert.equal(pool.stats().sourceResidentTiles,0);assert.ok(sourceManager._inViewTiles.getAllTiles().length>0);pool.destroy();assert.equal(sourceManager._removeTile,nativeRemove);sourceManager.clearTiles();

 // Actual Mercator camera/frustum: orbit away and back at several zooms.
 // The sampled heights are controlled; these are lifecycle checks, not LINZ
 // coverage or rendered-pixel quality measurements.
 let camera;Style.prototype._setProjectionInternal.call({map:{migrateProjection:t=>camera=t},tileManagers:{},_globalState:{}},'mercator');
 camera.resize(400,300);camera.setCenter(new LngLat(170.141,-43.595));camera.setPitch(58);camera.setElevation(3724);
 const sceneTerrain={getMinMaxElevation:()=>({minElevation:0,maxElevation:4000})},cameraPool=createPool({types:['raster-dem'],bytes:48*1048576,pendingLimit:2});cameraPool.attach(sourceManager);
 const cameraCases=[];
 const cameraTick=async()=>{sourceManager.update(camera,sceneTerrain);await new Promise(r=>setImmediate(r));sourceManager.update(camera,sceneTerrain);};
 for(const zoom of [12,14,16]){
  sourceManager.clearTiles();camera.setZoom(zoom);camera.setBearing(-24);await cameraTick();
  const seen=sourceManager._inViewTiles.getAllTiles().filter(t=>t.dem),identity=new Map(seen.map(t=>[t.tileID.key,t.dem]));
  camera.setBearing(16);await cameraTick();const calls=loads,evicted=cameraPool.stats().sourceEvictions;camera.setBearing(-24);await cameraTick();
  const returning=sourceManager._inViewTiles.getAllTiles().filter(t=>identity.has(t.tileID.key));
  assert.equal(loads,calls,'Retained DEMs must not decode/upload again after a real-camera orbit');assert.ok(returning.every(t=>t.dem===identity.get(t.tileID.key)));
  cameraCases.push({zoom,bearingAway:16,bearingReturn:-24,initialDEMTiles:seen.length,returningDEMTiles:returning.length,returnLoads:loads-calls,evictionsOnReturn:cameraPool.stats().sourceEvictions-evicted});
 }
 cameraPool.destroy();sourceManager.clearTiles();

 // Exercise the actual native terrain-event path, not a replacement handler.
 let releases=0,elevationChanges=0,depthChanges=0,symbolChanges=0;
 const painter={acquireRTT:size=>({size,texture:{}}),releaseRTT:()=>releases++,markTerrainDepthDirty(){depthChanges++;}};
 map.painter=painter;
 const eventMap={terrain:null,style:{_checkLoaded(){},off(){},on(){},tileManagers:{terrain:sourceManager},_layers:{photo:{type:'raster',source:'photo'}},triggerSymbolPlacement(){symbolChanges++;}},painter,_camera:{setTerrain(){},applyTerrainChange(){elevationChanges++;}},fire(){},_handleTerrainDataEvent:NativeMap.prototype._handleTerrainDataEvent};
 NativeMap.prototype.setTerrain.call(eventMap,{source:'terrain',exaggeration:1});
 const manager=eventMap.terrain.tileManager;
 // Avoid network for this independent RTT lifecycle; native coverage + Tile
 // creation are still used, with the controlled frustum above.
 const sourceUpdate=sourceManager.update;sourceManager.update=()=>{};
 transform.zoom=8;wanted={z:8,x:100,y:100};const cache=retainDrapes(manager,painter);manager.update(transform,eventMap.terrain);
 const original=manager.getRenderableTiles()[0],id=original.tileID;original.acquireRTT(painter,0,512);wanted.x=110;manager.update(transform,eventMap.terrain);assert.equal(cache.stats().residentDrapes,1);
 const geometryEvent={dataType:'source',sourceId:'terrain',tile:{tileID:id}};
 eventMap._handleTerrainDataEvent(geometryEvent,'terrain');assert.equal(cache.stats().residentDrapes,0,'Native DEM arrival discards a finished off-screen colour drape');cache.destroy();
 // Same event now preserves colour while all terrain bookkeeping runs.
 wanted.x=100;manager.update(transform,eventMap.terrain);manager.getRenderableTiles()[0].acquireRTT(painter,0,512);
 const context=terrainEventContext(eventMap),retained=retainDrapes(manager,painter,{shouldInvalidate:context.shouldInvalidate,imageryUpdate:context.isImageryUpdate});
 const warm=manager.getRenderableTiles()[0],rtt=warm.getRTT(0);wanted.x=110;manager.update(transform,eventMap.terrain);
 const beforeCamera=elevationChanges,beforeDepth=depthChanges,beforeSymbols=symbolChanges,beforeRelease=releases;
 eventMap._handleTerrainDataEvent(geometryEvent,'terrain');assert.equal(retained.stats().residentDrapes,1);assert.equal(releases,beforeRelease);assert.equal(elevationChanges,beforeCamera+1);assert.equal(depthChanges,beforeDepth+1);assert.equal(symbolChanges,beforeSymbols+1);
 wanted.x=100;manager.update(transform,eventMap.terrain);assert.equal(manager.getRenderableTiles()[0],warm);assert.equal(warm.getRTT(0),rtt);
 eventMap._handleTerrainDataEvent(geometryEvent,'terrain');assert.equal(warm.getRTT(0),rtt,'Visible drapes survive geometry-only arrivals too');
 eventMap._handleTerrainDataEvent({dataType:'source',sourceId:'photo',tile:{tileID:id}},'terrain');assert.equal(warm.getRTT(0),undefined,'Real imagery arrivals must still refresh colour');
 warm.acquireRTT(painter,0,512);eventMap.style.tileManagers.photo={getSource:()=>({type:'raster'})};eventMap._handleTerrainDataEvent({dataType:'source',sourceId:'photo',tile:{tileID:id}},'terrain');assert.ok(warm.getRTT(0),'Real raster arrivals defer refreshing a valid atlas instead of discarding it');
 warm.acquireRTT(painter,0,512);eventMap.style._layers.shade={source:'terrain',type:'hillshade'};eventMap._handleTerrainDataEvent(geometryEvent,'terrain');assert.equal(warm.getRTT(0),undefined,'A DEM used by any style layer must still invalidate');delete eventMap.style._layers.shade;
 warm.acquireRTT(painter,0,512);eventMap._handleTerrainDataEvent({dataType:'style'},'terrain');assert.equal(warm.getRTT(0),undefined,'Style changes must still invalidate');
 const drapeStats=retained.stats();retained.destroy();context.destroy();assert.equal(eventMap._handleTerrainDataEvent,NativeMap.prototype._handleTerrainDataEvent);manager.destruct();sourceManager.update=sourceUpdate;
 // Controller wiring: both native pools are cleared on context loss and all
 // method/listener changes are undone on teardown.
 global.RidgeRasterResidency={createPool};const handlers={};map.on=(n,f)=>(handlers[n]??=[]).push(f);map.off=(n,f)=>handlers[n]=(handlers[n]||[]).filter(x=>x!==f);
 sourceManager.usedForTerrain=true;sourceManager.tileSize=512;
 const controller=attach(map,{engineVersion:'6.14.0'});assert.equal(controller.stats().demSourceBudgetBytes,48*1048576);wanted.x=100;await tick();wanted.x=110;await tick();assert.ok(controller.stats().demSourceResidentTiles>0);for(const f of handlers.webglcontextlost)f();assert.equal(controller.stats().demSourceResidentTiles,0);controller.destroy();assert.ok(Object.values(handlers).every(a=>!a.length));delete global.RidgeRasterResidency;sourceManager.clearTiles();
 const result={engine:'MapLibre 6.14.0',nativeDEMManagerAndTerrainEventHandler:true,baselineDEMCacheMiss:baselineMiss,baselineDEMUnloads:baselineUnloads,demPanSteps:28,revisitDEMLoads:0,sameDecodedArrayAndGPUTexture:true,hiddenDEMLoadCompletes:true,actualMercatorOrbitCases:cameraCases,geometryArrivalPreservesWarmAndVisibleDrapes:true,elevationDepthSymbolUpdatesPreserved:true,imageryAndStyleInvalidationPreserved:true,rasterArrivalsKeepLastGoodAtlas:true,contextLossClearsPools:true,demStats,drapeStats,caveat:'Actual bundled source/terrain/event lifecycle; controlled frustum, elevation decode and GPU allocation. No browser pixel or FPS measurement.'};fs.writeFileSync('TILE_UNLOADING_VALIDATION.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
})().catch(e=>{console.error(e);process.exitCode=1});
