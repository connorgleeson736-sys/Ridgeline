const assert=require('node:assert/strict'),fs=require('node:fs');
const {retainDrapes,attach}=require('../public/native-residency.js'),P=require('../public/performance.js');
(async()=>{
 const {Map:NativeMap,LngLat}=await import('../public/assets/maplibre-gl.mjs');
 let allocations=0,releases=0,repaints=0;
 const painter={acquireRTT(size){allocations++;return{size,texture:{marker:allocations}};},releaseRTT(){releases++;}};
 const sourceManager={_source:{tileSize:256,type:'raster-dem'},map:{painter},reload(){},update(){}};
 const handlers={};const map={terrain:null,style:{_checkLoaded(){},off(){},on(){},tileManagers:{terrain:sourceManager},_layers:{},triggerSymbolPlacement(){}},painter,_camera:{setTerrain(){}},fire(e){for(const fn of handlers[e.type]||[])fn(e);},on(n,fn){(handlers[n]??=[]).push(fn);},off(n,fn){handlers[n]=(handlers[n]||[]).filter(f=>f!==fn);},triggerRepaint(){repaints++;}};
 NativeMap.prototype.setTerrain.call(map,{source:'terrain',exaggeration:1});
 const manager=map.terrain.tileManager,nativeUpdate=manager.update;manager.getSourceTile=()=>({dem:{}});
 // Real coveringTiles and native Tile objects, with a controlled synthetic
 // frustum exposing a single geographic tile at each camera position.
 let wanted={z:3,x:4,y:4};
 const provider={getCenter:t=>t.center,getWrap:()=>0,allowVariableZoom:()=>false,allowWorldCopies:()=>false,distanceToTile2d:()=>0,getTileBoundingVolume({z,x,y}){const shift=wanted.z-z,visible=shift>=0?x===(wanted.x>>shift)&&y===(wanted.y>>shift):(x>>(z-wanted.z))===wanted.x&&(y>>(z-wanted.z))===wanted.y;return{intersectsFrustum:()=>visible?(z>=wanted.z?2:1):0};}};
 const transform={center:new LngLat(0,0),elevation:100,pitch:58,bearing:0,cameraToCenterDistance:500,worldSize:4096,tileSize:512,maxZoom:19,zoom:3,fov:36.87,renderWorldCopies:false,getCameraFrustum:()=>({}),getClippingPlane:()=>null,getCoveringTilesDetailsProvider:()=>provider};
 const cache=retainDrapes(manager,painter,{bytes:16*1048576});assert.ok(cache);assert.equal(manager.update(transform,map.terrain),true);
 let tile=manager.getRenderableTiles()[0];assert.ok(tile);const originalTile=tile,key=tile.tileID.key;
 function prepare(t,revision=0){const source={getSource:()=>({type:'raster'}),getState:()=>({revision}),getVisibleCoordinates:()=>[t.tileID]};const style={_order:['photo'],_layers:{photo:{type:'raster',source:'photo',isHidden:()=>false}},tileManagers:{photo:source}};painter.renderToTexture.prepareForRender(style,3,false);return painter.renderToTexture._rttFingerprints.photo[t.tileID.key];}
 let fingerprint=prepare(tile);const originalRTT=tile.acquireRTT(painter,0,1024);tile.rttFingerprint.photo=fingerprint;
 wanted.x=5;manager.update(transform,map.terrain);assert.equal(cache.stats().residentDrapes,1);assert.equal(originalTile.getRTT(0),originalRTT,'Looking away must retain the actual rendered texture');assert.equal(releases,0);
 tile=manager.getRenderableTiles()[0];fingerprint=prepare(tile);tile.acquireRTT(painter,0,1024);tile.rttFingerprint.photo=fingerprint;
 wanted.x=4;manager.update(transform,map.terrain);tile=manager.getRenderableTiles()[0];assert.equal(tile,originalTile);assert.equal(tile.getRTT(0),originalRTT);prepare(tile);assert.equal(tile.getRTT(0),originalRTT,'Native fingerprints accept unchanged restored content');assert.equal(allocations,2,'Returning to a view must not allocate/rerender its retained drape');
 // Native source revision checks must still release stale content.
 prepare(tile,1);assert.equal(tile.getRTT(0),undefined,'Source revisions must invalidate restored imagery');
 tile.acquireRTT(painter,0,1024);wanted.x=5;manager.update(transform,map.terrain);const before=releases;manager.releaseRTT(originalTile.tileID);assert.equal(releases,before+1,'Source events must invalidate off-screen drapes too');
 for(let i=0;i<100;i++){wanted.x=i%8;manager.update(transform,map.terrain);const t=manager.getRenderableTiles()[0];if(!t.getRTT(0))t.acquireRTT(painter,0,1024);assert.ok(cache.stats().residentBytes<=16*1048576);}
 assert.ok(cache.stats().reuses>0);assert.ok(cache.stats().evictions>0);const stats=cache.stats();cache.setBudget(0);assert.equal(cache.stats().residentDrapes,0);assert.ok(manager.getRenderableTiles()[0].getRTT(0),'Trimming warm memory must not unload visible content');cache.destroy();assert.equal(manager.update,nativeUpdate);
 // Real bounded moving RTT refresh preserves focal priority. Confirm
 // that the viewed surface is first even when the pivot's native order differs.
 wanted={z:2,x:2,y:2};transform.zoom=3;manager.update(transform,map.terrain);
 const active=manager.getRenderableTiles();assert.equal(active.length,4);
 const viewed=active[active.length-1],q=viewed.tileID.canonical;
 let vectorContent=false;const priorityCache=retainDrapes(manager,painter,{contentUpdate:()=>vectorContent,focus:()=>({hit:{x:(q.x+.5)/2**q.z,y:(q.y+.5)/2**q.z}})});
 const photo={getSource:()=>({type:'raster'}),getState:()=>({revision:0}),getVisibleCoordinates:()=>active.map(t=>t.tileID)};
 const photoStyle={_order:['photo'],_layers:{photo:{type:'raster',source:'photo',isHidden:()=>false}},tileManagers:{photo}};
 painter.renderToTexture.prepareForRender(photoStyle,3,false);
 for(const t of active){t.acquireRTT(painter,0,512);t.rttFingerprint.photo=painter.renderToTexture._rttFingerprints.photo[t.tileID.key];}
 manager.update(transform,map.terrain);assert.equal(manager.getRenderableTiles()[0],viewed);
 painter.renderToTexture.prepareForRender(photoStyle,4,true);
 assert.equal(viewed.getRTT(0),undefined,'Actual first stale drape refreshed must be the viewed mountain');
 assert.equal(active.filter(t=>!t.getRTT(0)).length,2,'Moving refresh services two patches while preserving the others');for(const t of active){t.acquireRTT(painter,0,512);t.rttFingerprint.photo=painter.renderToTexture._rttFingerprints.photo[t.tileID.key];}
 vectorContent=true;for(const t of active)manager.releaseRTT(t.tileID);vectorContent=false;
 assert.ok(active.every(t=>t.getRTT(0)),'A vector arrival eagerly discarded valid coverage');
 painter.renderToTexture.prepareForRender(photoStyle,4,true);assert.equal(active.filter(t=>!t.getRTT(0)).length,2,'Vector refinements must use the bounded refresh lane');
 const multi={...photoStyle,_order:['photo','label','road'],_layers:{...photoStyle._layers,label:{type:'symbol',isHidden:()=>false},road:{type:'line',source:'photo',isHidden:()=>false}}};
 painter.renderToTexture.prepareForRender(multi,4,false);for(const t of active){t.acquireRTT(painter,0,512);t.acquireRTT(painter,1,512);t.rttFingerprint.photo=painter.renderToTexture._rttFingerprints.photo[t.tileID.key];}
 painter.renderToTexture.prepareForRender(multi,5,true);assert.equal(active.filter(t=>!t.getRTT(0)).length,1,'Two stacks must share one refresh budget');priorityCache.destroy();
 let dem=.5,image=.5,budget=48;const adapter=attach(map,{engineVersion:'6.14.0',demDetail:()=>dem,imageryDetail:()=>image,residentMB:()=>budget});assert.equal(manager.tileSize,362);assert.equal(sourceManager.tileSize,362);assert.equal(painter.renderToTexture.rttSize,1024);dem=1;adapter.refresh();assert.equal(manager.tileSize,256);assert.equal(sourceManager.tileSize,256);assert.equal(painter.renderToTexture.rttSize,724);image=1;adapter.refresh();assert.equal(painter.renderToTexture.rttSize,1024);budget=32;adapter.refresh();assert.equal(adapter.stats().budgetBytes,32*1048576);
 wanted={z:3,x:4,y:4};transform.zoom=3.75;dem=0;adapter.refresh();manager.update(transform,map.terrain);const baseline=manager.getRenderableTiles().length;dem=.5;adapter.refresh();manager.update(transform,map.terrain);assert.equal(manager.getRenderableTiles().length,baseline*4,'Higher terrain detail must actually subdivide the same geographic view');
 const lodSource={},lodMap={getSource:()=>lodSource,_update(){}};NativeMap.prototype.setSourceTileLodParams.call(lodMap,7,4,'image');const native=lodSource.calculateTileZoom;
 for(const boost of [0,.5,1,1.5]){assert.ok(P.focusLod(native,boost)(12,0,1,1,36)>=12+boost);const a=P.focusLod(native,boost)(12,8,.53,1,36),b=P.focusLod(native,0)(12,8,.53,1,36);assert.ok(Math.abs(a-b)<1e-8,'Imagery slider must protect nearby detail without inflating distant requests');}

 const preparePrototype=Object.getPrototypeOf(painter.renderToTexture).prepareForRender;
 for(let i=0;i<2;i++){for(const fn of handlers.webglcontextrestored||[])fn();assert.notEqual(painter.renderToTexture.prepareForRender,preparePrototype,'Restored context receives a fresh continuity adapter');}
 adapter.destroy();assert.equal(painter.renderToTexture.prepareForRender,preparePrototype,'Context restoration must not stack or leak renderer hooks');
 global.RidgeTiles=require('../public/tile-cache.js');let cacheMiB=700;const aggregate=attach(map,{engineVersion:'6.14.0',cacheMiB:()=>cacheMiB});
 const live=manager.getRenderableTiles()[0],liveRTT=live.acquireRTT(painter,0,512);
 for(const amount of [96,700,352]){cacheMiB=amount;aggregate.refresh();assert.equal(aggregate.stats().budgetBytes,global.RidgeTiles.budgetPlan(amount).views*1048576);assert.equal(live.getRTT(0),liveRTT,'Aggregate slider must not release visible terrain RTTs');}
 aggregate.destroy();delete global.RidgeTiles;manager.destruct();assert.equal(sourceManager.usedForTerrain,false);assert.ok(Object.values(handlers).every(a=>a.length===0));
 // Deterministic backoff: only failed, still-resident imagery is retried.
 const vm=require('node:vm'),mod={exports:{}},timers=new Map();let timerId=0,refreshes=0;
 vm.runInNewContext(fs.readFileSync('public/native-residency.js','utf8'),{module:mod,RidgeTileRetry:require('../public/tile-retry.js'),setTimeout:fn=>{timers.set(++timerId,fn);return timerId;},clearTimeout:id=>timers.delete(id)});
 const eh={},failed={state:'errored',tileID:{key:'failed-key',canonical:{z:14,x:12,y:13}}};
 const retryMap={terrain:null,on(n,f){(eh[n]??=[]).push(f);},off(n,f){eh[n]=(eh[n]||[]).filter(a=>a!==f);},style:{tileManagers:{linz:{getTile:()=>failed,_removeTile:key=>assert.equal(key,'failed-key')}}},_update(){refreshes++;},refreshTiles(){throw Error('Unsafe errored-tile refresh must not be called');}};
 const retryControl=mod.exports.attach(retryMap,{engineVersion:'6.14.0'}),error={tile:failed,sourceId:'linz',error:{status:503}},fire=(n,e)=>{for(const f of eh[n]||[])f(e);},flush=()=>{const batch=[...timers.values()];timers.clear();batch.forEach(f=>f());};
 fire('error',error);fire('error',error);assert.equal(timers.size,1);flush();assert.equal(refreshes,1);fire('error',error);flush();fire('error',error);flush();assert.equal(refreshes,2,'Never retry indefinitely');
 failed.state='loaded';fire('sourcedata',{tile:failed,sourceId:'linz'});failed.state='errored';fire('error',error);retryControl.destroy();flush();assert.equal(refreshes,2,'No retry after teardown');
 assert.equal(retainDrapes({},{}),null,'Unknown native shapes must safely skip the adapter');
 const result={engine:'MapLibre 6.14.0',realNativeTileAndRTTManager:true,lookAwayTextureRetained:true,returnReusesSameTileAndTexture:true,nativeFingerprintInvalidation:true,offscreenSourceInvalidation:true,turns:100,cacheStats:stats,detailControls:'passed',aggregateBudgetResize:'passed',targetedRetries:'passed',nativeRTTFocusPriority:'passed',contextRestoreHooks:'fresh and unstacked',cleanup:'passed',caveat:'Real native CPU tile/drape/fingerprint lifecycle with mocked GPU allocations and controlled frustum; no browser pixels or FPS measured'};fs.writeFileSync('NATIVE_RESIDENCY_VALIDATION.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
})().catch(e=>{console.error(e);process.exitCode=1});
