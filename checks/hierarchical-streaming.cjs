const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const S=require('../public/terrain-streaming.js'),{retainDrapes}=require('../public/native-residency.js');global.RidgeTerrainStreaming=S;
(async()=>{
 const E=await import('../public/assets/maplibre-gl.mjs'),{Map:NativeMap,LngLat,MercatorCoordinate:MC,Style}=E;
 const env=vm.createContext({performance:{now:()=>1000}});for(const name of ['surface-focus.js','performance.js'])vm.runInContext(fs.readFileSync('public/'+name,'utf8'),env);
 const P=env.RidgePerformance;
 let camera;Style.prototype._setProjectionInternal.call({map:{migrateProjection:t=>camera=t},tileManagers:{},_globalState:{}},'mercator');camera.resize(1359,620);camera.setPitch(58);camera.setBearing(-24);camera.setCenter(new LngLat(170.141,-43.595));camera.setElevation(3000);
 const terrain={getMinMaxElevation:()=>({minElevation:0,maxElevation:4000})},map={_camera:{transform:camera}},provider=camera.getCoveringTilesDetailsProvider(),distance=provider.distanceToTile2d;
 let state;provider.distanceToTile2d=function(px,py,id,bounds){state.tile=id;return distance.call(this,px,py,id,bounds);};
 const holder=(levels,ratio)=>{const source={};NativeMap.prototype.setSourceTileLodParams.call({getSource:()=>source,_update(){}},levels,ratio,'test');return source.calculateTileZoom;};
 const rows=[];let oldCount=0,newCount=0;
 for(const zoom of [11,13,15,17]){
  camera.setZoom(zoom);const centre=MC.fromLngLat(camera.center,camera.elevation),d=camera.cameraToCenterDistance/camera.worldSize,p=camera.pitch*Math.PI/180,b=camera.bearing*Math.PI/180,cam={x:centre.x-d*Math.sin(p)*Math.sin(b),y:centre.y+d*Math.sin(p)*Math.cos(b),z:centre.z+d*Math.cos(p)};
  const hit={x:cam.x+(centre.x-cam.x)*.6,y:cam.y+(centre.y-cam.y)*.6,z:cam.z+(centre.z-cam.z)*.6};state={hit,range:d*.6,pivot:centre,pivotRange:d,fov:camera.fov,tile:null};
  const oldNative=holder(7,4),newNative=holder(8,3.5);
  // Previous surface-only policy; keep this baseline independent of live code.
  const old=(z,xy,dz,range,fov)=>{const value=oldNative(z,xy,dz,range,fov),weight=env.RidgeSurfaceFocus.weight(state),target=z+Math.min(1,Math.max(0,Math.log2(range/state.range)));return value+Math.max(0,target-value)*weight;};
  const next=P.focusLod(newNative,0,()=>state),before=NativeMap.prototype.coveringTiles.call(map,{tileSize:431,maxzoom:18,terrain,calculateTileZoom:old}),after=NativeMap.prototype.coveringTiles.call(map,{tileSize:431,maxzoom:18,terrain,calculateTileZoom:next});
  const at=(ids,point)=>ids.find(({canonical:q})=>Math.floor(point.x*2**q.z)===q.x&&Math.floor(point.y*2**q.z)===q.y)?.canonical.z;
  assert.ok(at(after,hit)>=at(before,hit),'Viewed surface detail must be preserved');assert.ok(at(after,centre)>=at(before,centre),'Protect pivot detail as well as viewed face');
  const distant={x:centre.x+(centre.x-cam.x)*1.1,y:centre.y+(centre.y-cam.y)*1.1};
  oldCount+=before.length;newCount+=after.length;rows.push({zoom,oldTiles:before.length,newTiles:after.length,viewedBefore:at(before,hit),viewedAfter:at(after,hit),pivotBefore:at(before,centre),pivotAfter:at(after,centre),distantBefore:at(before,distant),distantAfter:at(after,distant)});
 }
 assert.ok(newCount<=oldCount*1.05,'A smaller protected pivot should not inflate the distant budget');
 // Complete source coverage, including complete child sets, never a single
 // available child posing as full coverage of its parent.
 const quad=(z,x,y)=>({canonical:{z,x,y}}),children=[quad(3,4,4),quad(3,5,4),quad(3,4,5),quad(3,5,5)],parent=quad(2,2,2);
 assert.equal(S.completeCoverage(children)(parent),true);assert.equal(S.completeCoverage(children.slice(0,3))(parent),false);assert.equal(S.completeCoverage([parent])(children[0]),true);
 const f={hit:{x:.51,y:.51},range:.01,pivot:{x:.7,y:.7},pivotRange:.03};assert.ok(S.priority(quad(8,130,130),f)<S.priority(quad(8,20,20),f));assert.ok(S.priority(quad(8,179,179),f)<S.priority(quad(8,20,20),f));
 // The adapter must change request order, never the selected tile set.
 const nativeRetain=function(ids){this.received=ids;return ids;},emptyCache={data:{},setMaxSize(){},getAndRemove(){},add(){},onRemove(){},reset(){}};
 const orderManager={getSource:()=>({type:'raster',tileSize:256}),_outOfViewCache:emptyCache,_inViewTiles:{deleteTileById(){}},updateCacheSize(){},_removeTile(){},_updateRetainedTiles:nativeRetain,on(){},off(){}};
 const orderPool=require('../public/raster-residency.js').createPool({priority:id=>S.priority(id,f)});orderPool.attach(orderManager);
 const far={...quad(8,20,20),key:'far'},near={...quad(8,130,130),key:'near'},pivot={...quad(8,179,179),key:'pivot'};
 orderManager._updateRetainedTiles([far,pivot,near],8);assert.deepEqual(orderManager.received,[near,pivot,far]);assert.equal(orderManager.received.length,3);orderPool.destroy();assert.equal(orderManager._updateRetainedTiles,nativeRetain);
 // Real native terrain tile lifecycle, controlled frustum/readiness/GPU.
 let allocations=0,releases=0,now=1000,ready=false,imageEvent=false;
 const painter={acquireRTT(size){allocations++;return{size,texture:{}};},releaseRTT(){releases++;}};
 const sourceManager={_source:{tileSize:256,type:'raster-dem'},map:{painter},reload(){},update(){}};
 const nativeMap={terrain:null,style:{_checkLoaded(){},off(){},on(){},tileManagers:{terrain:sourceManager},_layers:{},triggerSymbolPlacement(){}},painter,_camera:{setTerrain(){}},fire(){}};
 NativeMap.prototype.setTerrain.call(nativeMap,{source:'terrain',exaggeration:1});const manager=nativeMap.terrain.tileManager;manager.getSourceTile=()=>({dem:{}});
 const wanted={z:2,x:2,y:2},pvd={getCenter:t=>t.center,getWrap:()=>0,allowVariableZoom:()=>false,allowWorldCopies:()=>false,distanceToTile2d:()=>0,getTileBoundingVolume({z,x,y}){const shift=wanted.z-z,visible=shift>=0?x===(wanted.x>>shift)&&y===(wanted.y>>shift):(x>>(z-wanted.z))===wanted.x&&(y>>(z-wanted.z))===wanted.y;return{intersectsFrustum:()=>visible?1:0};}};
 const t={center:new LngLat(0,0),elevation:100,pitch:58,bearing:0,cameraToCenterDistance:500,worldSize:4096,tileSize:512,maxZoom:19,zoom:2,fov:36.87,renderWorldCopies:false,getCameraFrustum:()=>({}),getClippingPlane:()=>null,getCoveringTilesDetailsProvider:()=>pvd};
 const cache=retainDrapes(manager,painter,{clock:()=>now,imageryUpdate:()=>imageEvent,readiness:()=>()=>ready});manager.update(t,nativeMap.terrain);const original=manager.getRenderableTiles()[0];
 let photos=[original.tileID],revision=0;
 const photo={getSource:()=>({type:'raster'}),getState:()=>({revision}),getVisibleCoordinates:()=>photos},photoStyle={_order:['photo'],_layers:{photo:{id:'photo',type:'raster',source:'photo',isHidden:()=>false}},tileManagers:{photo}};
 painter.renderToTexture.prepareForRender(photoStyle,2,false);const rtt=original.acquireRTT(painter,0,1024);cache.recordImageCoverage(original,()=>true);original.rttFingerprint.photo=painter.renderToTexture._rttFingerprints.photo[original.tileID.key];
 t.zoom=3;manager.update(t,nativeMap.terrain);assert.deepEqual(manager.getRenderableTiles(),[original]);assert.equal(original.getRTT(0),rtt);assert.equal(allocations,1);assert.equal(releases,0,'No release/reallocation while waiting for child imagery');
 manager.update(t,nativeMap.terrain);assert.deepEqual(manager.getRenderableTiles(),[original]);
 photos=[original.tileID.scaledTo(1)];
 // Actual native prepareForRender would release on the source-tile change.
 painter.renderToTexture.prepareForRender(photoStyle,3,false);assert.equal(original.getRTT(0),rtt,'Incomplete replacement must retain the real rendered drape through native fingerprint checks');assert.equal(painter.renderToTexture.needsFollowUpFrame,false,'Pending data must not spin extra render frames');
 imageEvent=true;manager.releaseRTT(original.tileID);imageEvent=false;assert.equal(original.getRTT(0),rtt,'Source arrival marks dirty rather than discarding last-good imagery');
 painter.renderToTexture.prepareForRender(photoStyle,3,false);assert.equal(original.getRTT(0),rtt);ready=true;manager.update(t,nativeMap.terrain);assert.equal(manager.getRenderableTiles().length,4);assert.ok(manager.getRenderableTiles().every(t=>!t.tileID.equals(original.tileID)),'Parent and children never overlap');
 t.zoom=2;manager.update(t,nativeMap.terrain);manager.getRenderableTiles()[0].acquireRTT(painter,0,512);cache.recordImageCoverage(manager.getRenderableTiles()[0],()=>true);ready=false;t.zoom=3;manager.update(t,nativeMap.terrain);assert.equal(manager.getRenderableTiles().length,1);now+=1900;manager.update(t,nativeMap.terrain);assert.equal(manager.getRenderableTiles().length,1,'A timeout must not discard valid terrain while replacement is missing');ready=true;manager.update(t,nativeMap.terrain);assert.equal(manager.getRenderableTiles().length,4,'Ready replacement must refine immediately');
 // Complete fine cover remains usable when a coarser replacement is pending.
 for(const tile of manager.getRenderableTiles()){tile.acquireRTT(painter,0,512);cache.recordImageCoverage(tile,()=>true);}original.releaseRTT(painter);
 ready=false;t.zoom=2;manager.update(t,nativeMap.terrain);assert.equal(manager.getRenderableTiles().length,4,'Keep all four old children during coarsening');
 now+=5000;manager.update(t,nativeMap.terrain);assert.equal(manager.getRenderableTiles().length,4);ready=true;manager.update(t,nativeMap.terrain);assert.equal(manager.getRenderableTiles().length,1);
 manager.getRenderableTiles()[0].acquireRTT(painter,0,512);cache.recordImageCoverage(manager.getRenderableTiles()[0],()=>true);cache.flush();ready=false;t.zoom=5;manager.update(t,nativeMap.terrain);assert.equal(manager.getRenderableTiles().length,1,'Retain parent across a three-level zoom jump');
 ready=true;manager.getSourceTile=id=>id.canonical.z<=2?{dem:{}}:undefined;manager.update(t,nativeMap.terrain);assert.equal(manager.getRenderableTiles().length,1,'Image readiness alone must not replace measured terrain with a missing DEM');
 manager.getSourceTile=()=>({dem:{}});manager.update(t,nativeMap.terrain);assert.equal(manager.getRenderableTiles().length,64,'Refine immediately once geometry and imagery are ready');
 // A partial RTT must refresh progressively, never be mistaken for a complete
 // last-good image and frozen indefinitely while off-screen areas are pending.
 const fragment=manager.getRenderableTiles()[0];photos=[fragment.tileID];ready=true;painter.renderToTexture.prepareForRender(photoStyle,5,false);fragment.acquireRTT(painter,0,512);fragment.rttFingerprint.photo=painter.renderToTexture._rttFingerprints.photo[fragment.tileID.key];cache.recordImageCoverage(fragment,()=>false);
 photos=[fragment.tileID.scaledTo(1)];ready=false;painter.renderToTexture.prepareForRender(photoStyle,5,false);assert.equal(fragment.getRTT(0),undefined,'Partial images remain eligible for progressive repaint');
 const stats=cache.stats();cache.destroy();manager.destruct();
 const result={engine:'MapLibre 6.14.0',actualNativeCovering:true,oldDEMTiles:oldCount,newDEMTiles:newCount,scenes:rows,viewedSurfaceAndPivotDetailPreserved:true,completeChildCoverageRequired:true,nearRequestsPrioritised:true,parentTextureSurvivesChildLoading:true,noParentChildOverlap:true,noExtraGPUTextureAllocationsDuringHold:true,replacementNeedsDEMAndImagery:true,timeoutCannotCreateHole:true,nativeFingerprintKeepsLastCompleteImage:true,pendingSourceArrivalKeepsDrape:true,noPendingRenderSpin:true,coarseningKeepsCompleteChildren:true,largeZoomJumpRetainsParent:true,imageryAloneCannotReplaceMissingDEM:true,partialDrapeCannotFreeze:true,stats,caveat:'Native CPU tile traversal/lifecycle with controlled first hit, readiness and GPU allocation; no browser pixels or FPS measured.'};fs.writeFileSync('HIERARCHICAL_STREAMING_VALIDATION.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
})().catch(e=>{console.error(e);process.exitCode=1});
