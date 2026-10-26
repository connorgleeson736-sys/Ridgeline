/* Native 6.14 lifecycle, controlled GPU/readiness. No browser FPS claims. */
const assert=require('node:assert/strict'),fs=require('node:fs');
const S=require('../public/terrain-streaming.js'),{retainDrapes}=require('../public/native-residency.js');
global.RidgeTerrainStreaming=S;
(async()=>{
 const E=await import('../public/assets/maplibre-gl.mjs'),results=[];
 function scene(finished,budget=96*1048576,partialImages=false){
  let ready=false,allocations=0,releases=0;
  const painter={acquireRTT:size=>{allocations++;return{size,texture:{}};},releaseRTT(){releases++;}};
  const sourceManager={_source:{tileSize:256,type:'raster-dem'},map:{painter},reload(){},update(){}};
  const map={terrain:null,style:{_checkLoaded(){},off(){},on(){},tileManagers:{terrain:sourceManager},_layers:{},triggerSymbolPlacement(){}},painter,_camera:{setTerrain(){}},fire(){}};
  E.Map.prototype.setTerrain.call(map,{source:'terrain',exaggeration:1});const manager=map.terrain.tileManager;manager.getSourceTile=()=>({dem:{}});
  const wanted={z:2,x:2,y:2},provider={getCenter:t=>t.center,getWrap:()=>0,allowVariableZoom:()=>false,allowWorldCopies:()=>false,distanceToTile2d:()=>0,getTileBoundingVolume({z,x,y}){const shift=wanted.z-z,visible=shift>=0?x===(wanted.x>>shift)&&y===(wanted.y>>shift):(x>>(z-wanted.z))===wanted.x&&(y>>(z-wanted.z))===wanted.y;return{intersectsFrustum:()=>visible?1:0};}};
  const t={center:new E.LngLat(0,0),elevation:100,pitch:58,bearing:0,cameraToCenterDistance:500,worldSize:4096,tileSize:512,maxZoom:19,zoom:3,fov:36.87,renderWorldCopies:false,getCameraFrustum:()=>({}),getClippingPlane:()=>null,getCoveringTilesDetailsProvider:()=>provider};
  const cache=retainDrapes(manager,painter,{bytes:budget,readiness:()=>()=>ready});manager.update(t,map.terrain);
  const fine=manager.getRenderableTiles(),saved=new Map();assert.equal(fine.length,4);
  const photo={getState:()=>({revision:0}),getSource:()=>({type:'raster'}),getVisibleCoordinates:()=>fine.map(t=>t.tileID)};
  const style={_order:['photo'],_layers:{photo:{id:'photo',type:'raster',source:'photo',isHidden:()=>false}},tileManagers:{photo}};
  painter.renderToTexture.prepareForRender(style,3,false);
  for(const tile of fine)tile.rttFingerprint.photo=painter.renderToTexture._rttFingerprints.photo[tile.tileID.key];
  for(const tile of fine.slice(0,finished)){const rtt=tile.acquireRTT(painter,0,512);cache.recordImageCoverage(tile,()=>true);saved.set(tile,rtt);}
  if(partialImages)for(const tile of fine.slice(finished)){tile.acquireRTT(painter,0,512);cache.recordImageCoverage(tile,()=>false);}
  const parent=fine[0].tileID.scaledTo(2),beforeAllocations=allocations;
  function check(){const draw=manager.getRenderableTiles();assert.equal(draw.length,4);
   assert.equal(S.completeCoverage(draw.map(t=>t.tileID))(parent),true);
   for(const a of draw)for(const b of draw)if(a!==b)assert.ok(!a.tileID.equals(b.tileID)&&!a.tileID.isChildOf(b.tileID),'Non-overlapping partition');
   for(const [tile,rtt]of saved){assert.ok(draw.includes(tile));assert.equal(tile.getRTT(0),rtt,'Keep exact finished GPU target');}
   for(const tile of draw)assert.equal(tile.tileID.terrainRttPosMatrix32f.length,16);
  }
  t.zoom=2;manager.update(t,map.terrain);check();
  painter.renderToTexture.prepareForRender(style,2,false);check();
  for(let i=0;i<50;i++){manager.update(t,map.terrain);check();}
  assert.equal(allocations,beforeAllocations,'Continuity bookkeeping allocates no GPU targets');assert.equal(releases,partialImages?4-finished:0,'Only incomplete stale images can refresh; all finished covers stay retained');
  // Reverse the gesture before replacement is ready, then coarsen again.
  t.zoom=3;manager.update(t,map.terrain);for(const [tile,rtt]of saved)assert.equal(manager.getTileByID(tile.tileID.key).getRTT(0),rtt);
  t.zoom=2;manager.update(t,map.terrain);check();
  const held=cache.stats();assert.ok(held.partialCoverHolds>0);assert.ok(held.residentBytes>=0);
  ready=true;manager.update(t,map.terrain);assert.equal(manager.getRenderableTiles().length,1);assert.ok(manager.getRenderableTiles()[0].tileID.equals(parent));
  const resourceBytes=cache.resources().warm.reduce((n,t)=>n+t.rttObjects.reduce((sum,o)=>sum+(o?o.size*o.size*4*4/3:0),0),0);assert.ok(Math.abs(cache.stats().residentBytes-resourceBytes)<1,'Cached partial targets keep correct ownership and byte accounting');
  const result={finishedChildren:finished,partialImages,cacheBudgetBytes:budget,preservedFinishedTextures:saved.size,transientDrawTiles:4,stableUpdates:50,extraGPUAllocations:allocations-beforeAllocations,partialCoverHolds:held.partialCoverHolds};cache.destroy();manager.destruct();return result;
 }
 for(const count of [1,2,3])for(const budget of [0,96*1048576])results.push(scene(count,budget));
 results.push(scene(3,96*1048576,true));
 // Deep mixed-resolution partition, world wrap, and hard work limits.
 // Tile ID is internal; obtain its constructor from a real fixture's native tiles.
 let ID;const p={acquireRTT(){},releaseRTT(){}},source={_source:{tileSize:256,type:'raster-dem'},map:{painter:p},reload(){},update(){}};
 const m={terrain:null,style:{_checkLoaded(){},off(){},on(){},tileManagers:{t:source},_layers:{},triggerSymbolPlacement(){}},painter:p,_camera:{setTerrain(){}},fire(){}};E.Map.prototype.setTerrain.call(m,{source:'t'});
 // Constructor available via source code fixture above; capture through a native transform.
 let transform;E.Style.prototype._setProjectionInternal.call({map:{migrateProjection:t=>transform=t},tileManagers:{},_globalState:{}},'mercator');transform.resize(800,600);transform.setCenter(new E.LngLat(170.14,-43.6));transform.setZoom(10);
 ID=E.Map.prototype.coveringTiles.call({_camera:{transform}}, {tileSize:512,maxzoom:19})[0].constructor;
 const parent=new ID(5,1,5,20,20),deep=new ID(8,1,8,160,160),near=new ID(6,1,6,41,41),entries=[deep,near].map(tileID=>({tile:{tileID}}));
 const cover=S.partitionCover(parent,entries);assert.ok(cover);assert.ok(cover.length<=64);assert.ok(cover.every(row=>row.id.wrap===1));assert.ok(S.completeCoverage(cover.map(row=>row.id))(parent));assert.equal(cover.filter(row=>row.entry).length,2);
 assert.equal(S.partitionCover(parent,entries,{maxTiles:4}),null);assert.equal(S.partitionCover(parent,entries,{maxDepth:1}),null);m.terrain.destroy();
 const preload=require('../public/imagery-preload.js');const parents=preload.plan({hit:{x:.9726,y:.635},zoom:15,previousZoom:16,baseZoom:17});assert.deepEqual(parents.map(q=>q.z),[16,15]);assert.equal(preload.plan({hit:null,zoom:15,previousZoom:16,baseZoom:17}).length,0);
 const result={engine:'MapLibre 6.14.0',scenes:results,mixedResolutionPartition:true,wrapPreserved:true,boundedTransitionWork:true,zoomOutPrefetchAncestors:parents.map(q=>q.z),caveat:'Native CPU lifecycle and exact texture identities with controlled readiness/GPU. Not browser pixels, latency or FPS.'};
 fs.writeFileSync('ZOOM_CONTINUITY_VALIDATION.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
})().catch(e=>{console.error(e);process.exitCode=1});
