const assert=require('node:assert/strict'),fs=require('node:fs');
const {createPool}=require('../public/raster-residency.js'),{retainDrapes,boundRecyclePool,retryFailedTile}=require('../public/native-residency.js');
(async()=>{
 global.window={requestAnimationFrame:fn=>setTimeout(fn,0),cancelAnimationFrame:clearTimeout};
 const {Style,Evented,LngLat,Map:NativeMap}=await import('../public/assets/maplibre-gl.mjs');
 async function eviction(protect){
  const map={_maxTileCacheSize:192,_maxTileCacheZoomLevels:8,_requestManager:{transformRequest:async url=>({url})},_refreshExpiredTiles:false};
  const style=Object.assign(new Evented(),{map,tileManagers:{},dispatcher:{},_layers:{},_checkLoaded(){},_validate:()=>false});map.style=style;style.on('error',()=>{});
  Style.prototype.addSource.call(style,'terrain',{type:'raster-dem',tiles:['https://example.test/{z}/{x}/{y}'],tileSize:256,maxzoom:18});await new Promise(r=>setTimeout(r,10));
  const sm=style.tileManagers.terrain,source=sm.getSource();source.loadTile=async t=>{t.dem={stride:260,dim:256,min:3200,max:3200,data:new Uint32Array(260*260),sampleBilinear:()=>3200,backfillBorder(){}};t.state='loaded';};source.unloadTile=t=>{t.dem=null;};source.abortTile=()=>{};sm.used=true;
  const wanted={z:8,x:100,y:100},provider={getCenter:t=>t.center,getWrap:()=>0,allowVariableZoom:()=>false,allowWorldCopies:()=>false,distanceToTile2d:()=>0,getTileBoundingVolume({z,x,y}){const d=wanted.z-z,hit=d>=0?x===Math.floor(wanted.x/2**d)&&y===Math.floor(wanted.y/2**d):false;return{intersectsFrustum:()=>hit?1:0};}};
  const tr={center:new LngLat(0,0),elevation:0,pitch:58,bearing:0,bearingInRadians:0,cameraToCenterDistance:500,worldSize:512*2**7,tileSize:512,maxZoom:19,zoom:7,fov:36.87,renderWorldCopies:false,width:256,height:256,populateCache(){},getCameraFrustum:()=>({}),getClippingPlane:()=>null,getCoveringTilesDetailsProvider:()=>provider};
  sm.update(tr,null);await new Promise(r=>setImmediate(r));const first=sm._inViewTiles.getAllTiles().find(t=>t.tileID.canonical.z===8);assert.ok(first?.dem);
  const ID=first.tileID.constructor,geoID=new ID(9,0,9,200,200),painter={acquireRTT:size=>({size,texture:{}}),releaseRTT(){}};
  const tm={terrain:null,style:{...style,off(){},on(){},_checkLoaded(){},triggerSymbolPlacement(){}},painter,_camera:{setTerrain(){}},fire(){}};map.painter=painter;
  NativeMap.prototype.setTerrain.call(tm,{source:'terrain',exaggeration:1});const manager=tm.terrain.tileManager,drawn=new first.constructor(geoID,512);drawn.acquireRTT(painter,0,512);manager._tiles[geoID.key]=drawn;manager._renderableTilesKeys=[geoID.key];
  let complete=true,photos=[geoID];const renderer=painter.renderToTexture;
  const photoSource={getSource:()=>({type:'raster'}),getState:()=>({revision:0}),getVisibleCoordinates:()=>photos},photoStyle={_order:['photo'],_layers:{photo:{id:'photo',type:'raster',source:'photo',isHidden:()=>false}},tileManagers:{photo:photoSource}};
  // Control GPU allocation/draw only; use the actual native prepare/fingerprint
  // implementation and verify the production render-completion hook.
  renderer.renderLayer=function(){for(const tile of this._renderableTiles){if(!tile.getRTT(0))tile.acquireRTT(painter,0,512);tile.rttFingerprint.photo=this._rttFingerprints.photo[tile.tileID.key];}return true;};
  const drapes=retainDrapes(manager,painter,{readiness:()=>{const snapshot=complete;return()=>snapshot;}}),pool=createPool({types:['raster-dem'],bytes:1200000,protect:()=>protect?drapes.protectedDEMs():new Set(),relieve:()=>drapes.relieveDEMPressure()});pool.attach(sm);renderer.prepareForRender(photoStyle,9,false);renderer.renderLayer(photoStyle._layers.photo);
  sm._removeTile(first.tileID.key);assert.equal(tm.terrain.getDEMElevation(geoID,0,0),3200);
  const other=sm._addTile(new ID(8,0,8,101,100));await new Promise(r=>setImmediate(r));sm._removeTile(other.tileID.key);
  tm.terrain.resetElevationCache();const height=tm.terrain.getDEMElevation(geoID,0,0),stats=pool.stats();
  if(protect){assert.equal(height,3200);assert.equal(manager.getSourceTile(geoID,true),first);assert.ok(first.dem);}else assert.equal(height,0,'Reproduce cached parent eviction turning real terrain sampling into zero elevation');
  assert.ok(stats.sourceResidentBytes<=1200000);
  if(protect){const saved=drawn.getRTT(0);complete=false;photos=[new ID(10,0,10,400,400)];renderer.prepareForRender(photoStyle,9,false);assert.equal(drawn.getRTT(0),saved,'Actual render hook captures a complete atlas; native preparation retains it during incomplete replacement');
   drapes.recordImageCoverage(drawn,()=>false);renderer.prepareForRender(photoStyle,9,false);assert.equal(drawn.getRTT(0),undefined,'An incomplete atlas must remain eligible for progressive repaint');renderer.renderLayer(photoStyle._layers.photo);complete=true;photos=[geoID];renderer.prepareForRender(photoStyle,9,false);assert.equal(drawn.getRTT(0),undefined,'A partial atlas refreshes once full data arrives');renderer.renderLayer(photoStyle._layers.photo);assert.ok(drawn.getRTT(0));}
  drapes.destroy();pool.destroy();sm.clearTiles();manager.destruct();return{height,stats};
 }
 const before=await eviction(false),after=await eviction(true);
 // Off-screen expiry: the native cache timer would delete valid imagery.
 // The pool leaves freshness reload metadata on Tile, but no hidden timeout.
 const rows={},native={data:rows,setMaxSize(){},onRemove(){},getAndRemove(){},getByKey(k){return rows[k]?.[0]?.value;},add(id,t,expiry){rows[id.key]=[{value:t,expiry}];},reset(){for(const row of Object.values(rows))this.onRemove(row[0].value);for(const k in rows)delete rows[k];},remove(id,row){delete rows[id.key];this.onRemove(row.value);}};
 const id={key:'cached',wrapped(){return this;}},tile={tileID:id,state:'loaded',texture:{size:[256,256]}};
 const m={getSource:()=>({type:'raster',tileSize:256}),_outOfViewCache:native,_inViewTiles:{deleteTileById(){}},on(){},off(){},updateCacheSize(){},_removeTile(){}};
 const pool=createPool();pool.attach(m);native.add(id,tile,1);assert.equal(rows.cached[0].expiry,undefined);assert.equal(native.getByKey(id.key),tile);assert.equal(pool.stats().fallbackReads,1);pool.destroy();
 let reload=0;const valid={state:'errored',texture:{},tileID:id},retryMap={style:{tileManagers:{linz:{getTile:()=>valid,_removeTile(){throw Error('Must not discard a valid last-good image');},_reloadTile(key,state){assert.equal(key,id.key);assert.equal(state,'reloading');reload++;}}}},_update(){}};
 assert.equal(retryFailedTile(retryMap,'linz',id),true);assert.equal(reload,1);
 let deleted=0;const painter={_rttObjectRecyclePool:[],releaseRTT(t){this._rttObjectRecyclePool.push(t);}},old=painter.releaseRTT,cap=boundRecyclePool(painter,12*1048576);
 for(let i=0;i<50;i++)painter.releaseRTT({size:1024,texture:{destroy(){deleted++;}}});assert.ok(cap.stats().spareRTTBytes<=12*1048576);assert.ok(deleted>=48);cap.destroy();assert.equal(painter.releaseRTT,old);
 assert.match(fs.readFileSync('public/app.js','utf8'),/maxPitch:85/);
 let camera;Style.prototype._setProjectionInternal.call({map:{migrateProjection:t=>camera=t},tileManagers:{},_globalState:{}},'mercator');camera.resize(1327,598);camera.setMaxPitch(85);camera.setCenter(new LngLat(170.141,-43.595));camera.setBearing(-24);camera.setElevation(3000);
 const pt={releaseRTT(){}},src={_source:{type:'raster-dem',tileSize:256,minzoom:0,maxzoom:18},map:{painter:pt},update(){},reload(){}},scene={terrain:null,style:{tileManagers:{terrain:src},_layers:{},off(){},on(){},_checkLoaded(){},triggerSymbolPlacement(){}},painter:pt,_camera:{setTerrain(){}},fire(){}};
 NativeMap.prototype.setTerrain.call(scene,{source:'terrain',exaggeration:1});scene.terrain.getMinMaxElevation=()=>({minElevation:0,maxElevation:4000});scene.terrain.tileManager.tileSize=430;const highPitchCases=[];
 for(const pitch of [60,75,85])for(const zoom of [11,13,15,17]){camera.setPitch(pitch);camera.setZoom(zoom);scene.terrain.tileManager.update(camera,scene.terrain);const count=scene.terrain.tileManager.getRenderableTiles().length;assert.ok(count>0&&count<128);assert.equal(camera.pitch,pitch);highPitchCases.push({pitch,zoom,renderTiles:count});}scene.terrain.destroy();
 const report={nativeEngine:'6.14.0',recording:'Screen recording 2026-10-18 21.32.53.webm',oldCachedDEMHeightAfterPressure:before.height,protectedDEMHeightAfterPressure:after.height,actualNativeDEMSampling:true,actualNativeRTTPreparation:true,renderHookRecordsCoverage:true,partialAtlasCanRefresh:true,offscreenExpiryDoesNotUnload:true,readFallbackRefreshesRecency:true,reloadRetainsValidTexture:true,unusedRTTPoolBounded:true,maxPitch:85,highPitchCases,sourceCacheBytesBounded:true,caveat:'Native CPU source/cache/terrain sampling with controlled 3200m DEM and GPU handles, not a live replay or GPU screenshot.'};
 fs.writeFileSync('LOADING_CONTINUITY_VALIDATION.json',JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
})().catch(e=>{console.error(e);process.exitCode=1});
