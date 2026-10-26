const assert=require('node:assert/strict'),fs=require('node:fs');
const {attach}=require('../public/terrain-visibility.js');
(async()=>{
 const E=await import('../public/assets/maplibre-gl.mjs'),{Map:NativeMap,Style,LngLat,MercatorCoordinate:MC}=E;
 let transform;Style.prototype._setProjectionInternal.call({map:{migrateProjection:t=>transform=t},tileManagers:{},_globalState:{}},'mercator');
 transform.resize(1359,620);transform.setCenter(new LngLat(170.141,-43.595));transform.setElevation(3717.3);transform.setPitch(58);transform.setBearing(-24);transform.setZoom(16);
 const view={_camera:{transform}},coord=MC.fromLngLat(transform.center);
 const ids=NativeMap.prototype.coveringTiles.call(view,{tileSize:512,minzoom:16,maxzoom:16});
 const fineId=ids.find(t=>t.canonical.x===Math.floor(coord.x*65536)&&t.canonical.y===Math.floor(coord.y*65536));assert.ok(fineId);
 const rows=new Map(),events={},sourceEvents={};
 // Min/max are from actual LINZ PNG downloads captured in DEM_CULLING_SOURCE.json.
 const coarse={tileID:fineId.scaledTo(5),dem:{min:-16,max:3056,dim:256}},fine={tileID:fineId,dem:{min:3282.3,max:3717.3,dim:256}};
 rows.set(coarse.tileID.key,coarse);rows.set(fine.tileID.key,fine);
 const painter={},sourceManager={_source:{type:'raster-dem',tileSize:256,minzoom:0,maxzoom:18},map:{painter},reload(){},update(){},getSource(){return this._source;},getTileByID:key=>rows.get(key),_inViewTiles:{getAllTiles:()=>[...rows.values()]},_outOfViewCache:{data:{},getByKey:()=>null},on(n,f){(sourceEvents[n]??=[]).push(f);},off(n,f){sourceEvents[n]=(sourceEvents[n]||[]).filter(a=>a!==f);}};
 const map={...view,terrain:null,style:{_checkLoaded(){},off(){},on(){},tileManagers:{terrain:sourceManager},_layers:{},triggerSymbolPlacement(){}},painter,_camera:{transform,setTerrain(){}},fire(){},on(n,f){(events[n]??=[]).push(f);},off(n,f){events[n]=(events[n]||[]).filter(a=>a!==f);}};
 NativeMap.prototype.setTerrain.call(map,{source:'terrain',exaggeration:1});
 const terrain=map.terrain,original=terrain.getMinMaxElevation,manager=terrain.tileManager,originalUpdate=manager.update;
 const parent=fineId.scaledTo(14);assert.equal(terrain.getMinMaxElevation(parent).maxElevation,3056,'Native coarse fallback excludes the measured summit height');
 const cover=()=>NativeMap.prototype.coveringTiles.call(map,{tileSize:431,minzoom:0,maxzoom:19,terrain});
 const atPoint=tiles=>tiles.filter(({canonical:q})=>q.x===Math.floor(coord.x*2**q.z)&&q.y===Math.floor(coord.y*2**q.z));
 const baseline=cover(),baselineAt=atPoint(baseline);
 const visibility=attach(map,{engineVersion:'6.14.0'}),installed=terrain.getMinMaxElevation,bounds=terrain.getMinMaxElevation(parent);
 assert.ok(bounds.maxElevation>=3717.3);assert.ok(bounds.minElevation<=-16);
 const fixed=cover(),fixedAt=atPoint(fixed);assert.ok(fixedAt.length,'Native frustum traversal must include the measured summit');
 // Search a range of close cameras to catch the actual false-culling failure.
 let corrected=0,baselineMissing=0,totalNativeTiles=0,totalGuardedTiles=0,maxNativeTiles=0,maxGuardedTiles=0;const cases=[];
 for(const zoom of [14,15,16,17,18])for(const pitch of [45,58,60])for(const bearing of [-120,-24,60,150]){
  transform.setZoom(zoom);transform.setPitch(pitch);transform.setBearing(bearing);
  terrain.getMinMaxElevation=original;const nativeTiles=cover(),before=atPoint(nativeTiles);terrain.getMinMaxElevation=installed;const guardedTiles=cover(),after=atPoint(guardedTiles);totalNativeTiles+=nativeTiles.length;totalGuardedTiles+=guardedTiles.length;maxNativeTiles=Math.max(maxNativeTiles,nativeTiles.length);maxGuardedTiles=Math.max(maxGuardedTiles,guardedTiles.length);
  if(!before.length){baselineMissing++;if(after.length){corrected++;cases.push({zoom,pitch,bearing,before:0,after:after.length});}}
 }
 // Restore the installed method so its cleanup can restore the true original.
 visibility.destroy();assert.equal(terrain.getMinMaxElevation,original);assert.equal(manager.update,originalUpdate);
 assert.ok(corrected>0,'Reproduce and correct a real native frustum false rejection with measured coarse/fine extrema');
 const control=attach(map,{engineVersion:'6.14.0'});terrain.getMinMaxElevation(parent);const stable=control.stats().visibilityBoundRebuilds;terrain.getMinMaxElevation(parent);assert.equal(control.stats().visibilityBoundRebuilds,stable,'Do not rebuild the min/max hierarchy per candidate');
 rows.delete(fine.tileID.key);for(const f of sourceEvents.data)f();assert.equal(terrain.getMinMaxElevation(parent).maxElevation,3856,'Removing a fine DEM must refresh metadata rather than accumulate a journey forever');
 rows.clear();for(const f of sourceEvents.data)f();const missing=terrain.getMinMaxElevation(parent);assert.deepEqual(missing,{minElevation:-50,maxElevation:4000});
 const stats=control.stats();control.destroy();assert.equal(terrain.getMinMaxElevation,original);assert.equal(manager.update,originalUpdate);assert.ok(Object.values(sourceEvents).every(a=>!a.length));assert.ok(Object.values(events).every(a=>!a.length));
 const result={engine:'MapLibre 6.14.0',actualNativeMercatorFrustumAndTerrain:true,measuredCoarseMaximum:3056,measuredFineMaximum:3717.3,baselineCentreTiles:baselineAt.length,fixedCentreTiles:fixedAt.length,baselineMissingCameras:baselineMissing,correctedCameras:corrected,totalNativeTiles,totalGuardedTiles,maxNativeTiles,maxGuardedTiles,cases,hierarchyReuse:true,evictionAndUnknownBounds:true,cleanup:true,stats,caveat:'Actual native CPU frustum/coverage. Measured LINZ extrema, controlled residency and actual installed bounds adapter; not a browser screenshot or GPU/FPS benchmark.'};assert.equal(coarse.dem.max,3056);assert.equal(fine.dem.max,3717.3);fs.writeFileSync('TERRAIN_VISIBILITY_VALIDATION.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
})().catch(e=>{console.error(e);process.exitCode=1});
