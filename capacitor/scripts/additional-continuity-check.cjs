const assert=require('node:assert/strict'),fs=require('node:fs');
const vm=require('node:vm');const env={module:{exports:{}},performance};vm.runInNewContext(fs.readFileSync('capacitor/www/native-residency.js','utf8'),env);const {retainDrapes}=env.module.exports;
(async()=>{
 const {Map:NativeMap,LngLat}=await import('../www/assets/maplibre-gl.mjs');
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

 let ready=true,live=true;
 const cache=retainDrapes(manager,painter,{bytes:0,readiness:()=>()=>ready});
 function mark(t){const o=t.tileID.canonical;t.state='loaded';t._ridgeDirectCoverage={valid:()=>live,coverage:id=>{const q=id.canonical,d=2**(q.z-o.z);return q.z>=o.z&&Math.floor(q.x/d)===o.x&&Math.floor(q.y/d)===o.y;}};}
 manager.update(transform,map.terrain);const original=manager.getRenderableTiles()[0];mark(original);
 wanted.x=5;manager.update(transform,map.terrain);mark(manager.getRenderableTiles()[0]);
 assert.equal(cache.stats().residentDrapes,1);assert.equal(cache.stats().residentBytes,0);
 wanted.x=4;manager.update(transform,map.terrain);assert.equal(manager.getRenderableTiles()[0],original,'Zero-RTT cover restores its original owner');
 wanted={z:4,x:8,y:8};transform.zoom=4;ready=false;manager.update(transform,map.terrain);
 assert.ok(manager.getRenderableTiles().includes(original),'Measured parent cover remains while finer imagery is unavailable');assert.equal(cache.hasWaitingCovers(),true);
 ready=true;manager.update(transform,map.terrain);assert.equal(cache.hasWaitingCovers(),false);assert.equal(manager.getRenderableTiles()[0].tileID.canonical.z,4);mark(manager.getRenderableTiles()[0]);
 for(let x=0;x<160;x++){wanted={z:8,x,y:128};transform.zoom=8;manager.update(transform,map.terrain);for(const t of manager.getRenderableTiles())mark(t);assert.ok(cache.stats().residentDrapes<=128);assert.equal(cache.stats().residentBytes,0);}
 assert.equal(allocations,0);console.log(JSON.stringify({zeroRttWarmRestoresOwner:true,measuredParentHeldForUnreadyChild:true,fineHandoff:true,metadataCountBound:128,zeroRttAllocations:allocations},null,2));cache.destroy();
})().catch(e=>{console.error(e);process.exitCode=1;});
