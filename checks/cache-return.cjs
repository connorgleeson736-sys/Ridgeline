const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),{execFileSync}=require('node:child_process');
const baseline='f294e4da00898c050d096fee6eae939e6a187cb1',S=require('../public/terrain-streaming.js');
(async()=>{
 global.window={requestAnimationFrame:fn=>setTimeout(fn,0),cancelAnimationFrame:clearTimeout};
 const E=await import('../public/assets/maplibre-gl.mjs');
 function load(code){const mod={exports:{}},env={module:mod,RidgeTerrainStreaming:S,performance,Map,Set,WeakMap,WeakSet,Number,Object,Math,Array};vm.runInNewContext(code,env);return{api:mod.exports,env};}
 function fixture(code){
  const {api,env}=load(code);let allocations=0,releases=0,time=0,event=null;
  const painter={context:{},acquireRTT(size){allocations++;return{size,texture:{identity:allocations}};},releaseRTT(){releases++;}};
  const source={_source:{tileSize:256,type:'raster-dem'},map:{painter},reload(){},update(){}};
  const map={terrain:null,style:{_checkLoaded(){},off(){},on(){},tileManagers:{terrain:source},_layers:{},triggerSymbolPlacement(){}},painter,_camera:{setTerrain(){}},fire(){}};
  E.Map.prototype.setTerrain.call(map,{source:'terrain',exaggeration:1});const manager=map.terrain.tileManager;manager.getSourceTile=()=>({dem:{}});
  let wanted={z:3,x:4,y:4};const provider={getCenter:t=>t.center,getWrap:()=>0,allowVariableZoom:()=>false,allowWorldCopies:()=>false,distanceToTile2d:()=>0,getTileBoundingVolume({z,x,y}){const d=wanted.z-z,yes=d>=0?x===(wanted.x>>d)&&y===(wanted.y>>d):(x>>(z-wanted.z))===wanted.x&&(y>>(z-wanted.z))===wanted.y;return{intersectsFrustum:()=>yes?(z>=wanted.z?2:1):0};}};
  const tr={center:new E.LngLat(0,0),elevation:100,pitch:58,bearing:0,cameraToCenterDistance:500,worldSize:4096,tileSize:512,maxZoom:19,zoom:3,fov:36.87,renderWorldCopies:false,getCameraFrustum:()=>({}),getClippingPlane:()=>null,getCoveringTilesDetailsProvider:()=>provider};
  const rtt=painter.renderToTexture;rtt.rttSize=512;
  // Control only GPU painting, keeping actual native Tile, covering/update,
  // source mapping, fingerprint validation and refresh throttling.
  rtt.renderLayer=function(){for(const t of this._renderableTiles){if(!t.getRTT(0)){t.acquireRTT(painter,0,512);for(const [source,rows]of Object.entries(this._rttFingerprints))if(rows[t.tileID.key])t.rttFingerprint[source]=rows[t.tileID.key];}}return true;};
  const cache=api.retainDrapes(manager,painter,{bytes:16*1048576,clock:()=>time,readiness:()=>()=>true,imageryUpdate:()=>!!event,imageryEvent:()=>event});
  let revision=0,photoCoords=null;const factory=Object.assign(new E.Evented(),{_layers:{},_order:[],_removedLayers:{},_globalState:{},_checkLoaded(){},_validate:()=>false,_validateLayer(){},_updateLayer(){},getLayer:E.Style.prototype.getLayer});
  E.Style.prototype.addLayer.call(factory,{id:'photo',type:'raster',source:'linz',paint:{'raster-opacity':1}});const photo=factory._layers.photo;
  const sourcePhoto={getSource:()=>({type:'raster'}),getState:()=>({revision}),getVisibleCoordinates:()=>photoCoords||manager.getRenderableTiles().map(t=>t.tileID)};
  const style={_order:['photo'],_layers:{photo},tileManagers:{linz:sourcePhoto}},state={context:painter.context};
  const update=x=>{wanted.x=x;manager.update(tr,map.terrain);return manager.getRenderableTiles()[0];};
  const frame=(zoom=3)=>{time+=16;rtt.prepareForRender(style,zoom,false);rtt.renderLayer(photo,style,state);};
  const invalidate=(id,sourceId='linz')=>{event={sourceId,tile:{tileID:id}};try{manager.releaseRTT(id);}finally{event=null;}};
  return{api,env,map,manager,cache,painter,rtt,photo,style,update,frame,invalidate,area:(z,x,y)=>{wanted={z,x,y};manager.update(tr,map.terrain);},setPaint:p=>{for(const [key,value]of Object.entries(p))photo.setPaintProperty(key,value,{validate:false});},setRevision:n=>revision=n,coordinates:ids=>{photoCoords=ids;},advance:ms=>time+=ms,stats:()=>({allocations,releases,...cache.stats()}),destroy:()=>cache.destroy()};
 }
 const beforeCode=execFileSync('git',['show',baseline+':public/native-residency.js'],{encoding:'utf8'}),afterCode=fs.readFileSync('public/native-residency.js','utf8');
 function scenarios(code){
  const f=fixture(code),a=f.update(4);f.frame();const saved=a.getRTT(0);f.update(5);f.frame();let n=f.stats().allocations;f.update(4);f.frame();const plainReturn=f.stats().allocations-n;assert.equal(a.getRTT(0),saved);assert.equal(plainReturn,0);
  f.update(5);f.frame();f.invalidate(a.tileID.scaledTo(2));n=f.stats().allocations;f.update(4);f.frame();const coarseArrivalRepaints=f.stats().allocations-n;
  f.update(5);f.frame();n=f.stats().allocations;f.update(4);f.frame(3.0001);f.frame(3.0001);const constantRasterZoomRepaints=f.stats().allocations-n;
  f.invalidate(a.tileID);n=f.stats().allocations;f.frame(3.0001);f.frame(3.0001);assert.equal(f.stats().allocations-n,1,'Actual participating imagery content must refresh');
  f.setRevision(1);n=f.stats().allocations;f.frame(3.0001);assert.equal(f.stats().allocations-n,1,'Revision changes must still refresh');
  f.setPaint({'raster-opacity':['interpolate',['linear'],['zoom'],3,0,4,1]});f.manager.releaseAllRTT();f.frame(3.0001);n=f.stats().allocations;f.frame(3.1);f.frame(3.1);assert.equal(f.stats().allocations-n,1,'Zoom-dependent imagery must still refresh');
  const stats=f.stats();f.destroy();return{plainReturn,coarseArrivalRepaints,constantRasterZoomRepaints,stats};
 }
 function repeated(code){const f=fixture(code),a=f.update(4);f.frame();f.update(5);f.frame();const n=f.stats().allocations;
  for(let i=0;i<40;i++){f.update(i%2?5:4);if(i%4===0)f.invalidate(a.tileID.scaledTo(2));const z=3+(i+1)*.00001;f.frame(z);f.frame(z);}
  const result={turns:40,recompositions:f.stats().allocations-n,stats:f.stats()};f.destroy();return result;}
 function coarsened(code){const f=fixture(code),a=f.update(4);f.frame();const saved=a.getRTT(0);f.update(5);f.frame();f.coordinates([a.tileID.scaledTo(2)]);const n=f.stats().allocations;f.update(4);f.frame();const result={recompositions:f.stats().allocations-n,sameFinishedTexture:a.getRTT(0)===saved,noFollowupSpin:!f.rtt.needsFollowUpFrame};
  const q=a.tileID.canonical,ID=a.tileID.constructor,children=[];for(let dy=0;dy<2;dy++)for(let dx=0;dx<2;dx++)children.push(new ID(q.z+1,0,q.z+1,q.x*2+dx,q.y*2+dy));
  f.coordinates(children);let start=f.stats().allocations;f.frame();assert.equal(f.stats().allocations-start,1,'Complete finer coverage still refines the retained picture');const refined=a.getRTT(0);
  f.coordinates([a.tileID.scaledTo(2),...children.slice(0,3)]);start=f.stats().allocations;f.frame();result.partialFineCoverRetained=a.getRTT(0)===refined;result.partialRecompositions=f.stats().allocations-start;
  f.setRevision(2);start=f.stats().allocations;f.frame();assert.equal(f.stats().allocations-start,1,'A genuine source revision overrides quality retention');f.destroy();return result;}
 function pressure(code){const f=fixture(code),a=f.update(4);f.frame();const b=f.update(5);f.frame();
  // Controlled composition GPU work; cache admission/adoption/trim are the
  // actual production paths. Native shader dispatch is covered separately.
  f.env.RidgeTerrainComposition={estimate:()=>512*512*4*4/3,compose({tile,painter}){tile.acquireRTT(painter,0,512);return{coverage:()=>true,zoomIndependent:true};}};
  const q=b.tileID.canonical,ID=b.tileID.constructor,prefetch=new ID(q.z,0,q.z,q.x+1,q.y);
  f.cache.setBudget(3*512*512*4*4/3);assert.equal(f.cache.prepareComposition(prefetch),'prepared');
  f.update(7);f.frame();f.update(8);f.frame();const n=f.stats().allocations;f.update(4);f.frame();const result={recentViewRecompositions:f.stats().allocations-n,stats:f.stats()};f.destroy();return result;
 }
 const before=scenarios(beforeCode),after=scenarios(afterCode),roundtripBefore=repeated(beforeCode),roundtripAfter=repeated(afterCode),pressureBefore=pressure(beforeCode),pressureAfter=pressure(afterCode),coarsenedBefore=coarsened(beforeCode),coarsenedAfter=coarsened(afterCode);
 if(!process.env.CACHE_RETURN_BASELINE){assert.equal(roundtripBefore.recompositions,40);assert.equal(roundtripAfter.recompositions,0);assert.equal(pressureBefore.recentViewRecompositions,1);assert.equal(pressureAfter.recentViewRecompositions,0);assert.ok(pressureAfter.stats.cacheReturns.speculativeEvictions>0);assert.equal(coarsenedBefore.recompositions,1);assert.equal(coarsenedAfter.recompositions,0);assert.equal(coarsenedAfter.sameFinishedTexture,true);assert.equal(coarsenedAfter.noFollowupSpin,true);assert.equal(coarsenedAfter.partialFineCoverRetained,true);assert.equal(coarsenedAfter.partialRecompositions,0);}
 const batch=fixture(afterCode);batch.area(2,2,2);batch.frame();const four=batch.manager.getRenderableTiles();assert.equal(four.length,4);for(const tile of four)batch.invalidate(tile.tileID);let repaintStart=batch.stats().allocations;
 for(let i=0;i<4;i++)batch.frame(3.001);assert.equal(batch.stats().allocations-repaintStart,4,'Every changed participating tile must repaint despite concurrent zoom and native one-per-frame throttling');batch.destroy();
 const f=fixture(afterCode);f.update(4);f.frame();const sourceTile=f.manager.getRenderableTiles()[0];let hiddenStart=f.stats().allocations;f.invalidate(sourceTile.tileID,'topo');f.frame();assert.equal(f.stats().allocations,hiddenStart,'Hidden provider arrivals cannot invalidate LINZ pixels');const tile=f.manager.getRenderableTiles()[0],q=tile.tileID,coords=f.rtt._coordsAscending;
 assert.equal(f.api.zoomIndependent(tile,f.style,coords,3),true);
 const factory=Object.assign(new E.Evented(),{_layers:{},_order:[],_removedLayers:{},_globalState:{},_checkLoaded(){},_validate:()=>false,_validateLayer(){},_updateLayer(){},getLayer:E.Style.prototype.getLayer});
 E.Style.prototype.addLayer.call(factory,{id:'route',type:'line',source:'route',minzoom:2,paint:{'line-width':2}});const line=factory._layers.route;
 const realTile=new tile.constructor(q,256);realTile.buckets={};const lineStyle={_order:['photo','route'],_layers:{photo:f.photo,route:line},tileManagers:{...f.style.tileManagers,route:{getTile:()=>realTile}}},lineCoords={...coords,route:{[q.key]:[q]}};
 assert.equal(f.api.zoomIndependent(tile,lineStyle,lineCoords,3),true,'Empty route tiles do not block imagery reuse');realTile.buckets.route={};assert.equal(f.api.zoomIndependent(tile,lineStyle,lineCoords,3),false,'Actual constant-width vector lines still require zoom updates');
 f.photo.hasTransition=()=>true;assert.equal(f.api.zoomIndependent(tile,f.style,coords,3),false);f.photo.hasTransition=()=>false;
 f.setPaint({'raster-opacity':['interpolate',['linear'],['zoom'],3,0,4,1]});assert.equal(f.api.zoomIndependent(tile,f.style,coords,3),false);f.destroy();
 const result={baseline,actualNativeTileFingerprintAndStyleLayers:true,before,after,roundtripBefore,roundtripAfter,pressureBefore,pressureAfter,coarsenedBefore,coarsenedAfter,actualContentAndRevisionRefresh:true,zoomDependentStyleAndVectorSafety:true,emptyRouteAndTransitionSafety:true,hiddenProviderSafe:true,simultaneousContentAndZoomRefreshesAllTiles:true,cacheBudgetUnchanged:true,caveat:'Actual native CPU tile/cache/fingerprint/style paths with controlled GPU painting and synthetic frame clock. Recomposition counts are measured in fixtures; latency is simulated, not Chromebook/GPU timing.'};
 if(!process.env.CACHE_RETURN_BASELINE)fs.writeFileSync('CACHE_RETURN_VALIDATION.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
 // Run with CACHE_RETURN_BASELINE=1 before implementation to establish failure.
 if(!process.env.CACHE_RETURN_BASELINE){assert.equal(before.coarseArrivalRepaints,1);assert.equal(after.coarseArrivalRepaints,0);assert.equal(before.constantRasterZoomRepaints,1);assert.equal(after.constantRasterZoomRepaints,0);}
})().catch(e=>{console.error(e);process.exitCode=1});
