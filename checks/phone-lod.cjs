const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
(async()=>{
 const E=await import('../public/assets/maplibre-gl.mjs');
 const env=vm.createContext({performance:{now:()=>1000}});for(const file of ['surface-focus.js','performance.js'])vm.runInContext(fs.readFileSync('public/'+file,'utf8'),env);
 const P=env.RidgePerformance,holder=(levels,ratio)=>{const s={};E.Map.prototype.setSourceTileLodParams.call({getSource:()=>s,_update(){}},levels,ratio,'test');return s.calculateTileZoom;};
 let camera;E.Style.prototype._setProjectionInternal.call({map:{migrateProjection:t=>camera=t},tileManagers:{},_globalState:{}},'mercator');
 camera.setCenter(new E.LngLat(170.141,-43.595));camera.setElevation(3000);camera.setBearing(-24);
 const terrain={getMinMaxElevation:()=>({minElevation:0,maxElevation:4000})},map={_camera:{transform:camera}},provider=camera.getCoveringTilesDetailsProvider(),distance=provider.distanceToTile2d;let state;
 provider.distanceToTile2d=function(px,py,id,bounds){state.tile=id;return distance.call(this,px,py,id,bounds);};
 const at=(ids,point)=>ids.find(({canonical:q})=>Math.floor(point.x*2**q.z)===q.x&&Math.floor(point.y*2**q.z)===q.y)?.canonical.z;
 // Distance weighting is independent of camera direction and focuses the slider
 // near the viewer; distant LOD is identical at both ends of the slider.
 const plain=()=>12,low=P.nearbyLod(plain,()=>0),high=P.nearbyLod(plain,()=>100);
 const gains=[.5,1,1.5,2.5].map(distance=>high(12,distance,0,1,36)-low(12,distance,0,1,36));
 assert.ok(gains[0]>gains[1]&&gains[1]>gains[2]&&gains[2]>0);assert.equal(gains[3],0);
 assert.equal(P.normalizePhoneDetail(NaN),50);assert.equal(P.normalizePhoneDetail(999),100);assert.equal(P.normalizePhoneDetail(-4),0);
 let normalTotal=0,phoneTotal=0,lowTotal=0,highTotal=0;const rows=[];
 for(const [width,height]of [[390,844],[844,390]])for(const pitch of [45,58,75])for(const zoom of [11,13,15,17]){
  camera.resize(width,height);camera.setPitch(pitch);camera.setZoom(zoom);
  const centre=E.MercatorCoordinate.fromLngLat(camera.center,camera.elevation),d=camera.cameraToCenterDistance/camera.worldSize,p=pitch*Math.PI/180,b=camera.bearing*Math.PI/180,cam={x:centre.x-d*Math.sin(p)*Math.sin(b),y:centre.y+d*Math.sin(p)*Math.cos(b),z:centre.z+d*Math.cos(p)};
  const hit={x:cam.x+(centre.x-cam.x)*.6,y:cam.y+(centre.y-cam.y)*.6,z:cam.z+(centre.z-cam.z)*.6};state={hit,range:d*.6,pivot:centre,pivotRange:d,fov:camera.fov,tile:null};
  for(const kind of ['dem','imagery','render']){
   const normal=kind==='dem'?P.lodParams(false):kind==='imagery'?{levels:6,ratio:3.5}:{levels:10.3,ratio:2.75},phone=kind==='dem'?P.lodParams(false,true):kind==='imagery'?{levels:10,ratio:2}:{levels:12,ratio:2};
   const cover=(params,detail=50)=>E.Map.prototype.coveringTiles.call(map,{tileSize:kind==='imagery'?256:431,maxzoom:kind==='imagery'?19:18,terrain,calculateTileZoom:P.nearbyLod(P.focusLod(holder(params.levels,params.ratio),kind==='imagery'?.25:0,()=>state,kind!=='render'),()=>detail,()=>state)});
   const before=cover(normal),after=cover(phone),lower=cover(phone,0),higher=cover(phone,100);lowTotal+=lower.length;highTotal+=higher.length;
   assert.ok(at(higher,hit)>=at(lower,hit),'Slider must increase viewed-face LOD');normalTotal+=before.length;phoneTotal+=after.length;
   assert.ok(at(after,hit)>=at(before,hit)-(kind==='imagery'?1:0),'Viewed face must stay within one native LOD: '+JSON.stringify({width,height,pitch,zoom,kind,viewedBefore:at(before,hit),viewedAfter:at(after,hit)}));
   assert.ok(after.length<=before.length,'Phone policy must not grow this cover: '+JSON.stringify({width,height,pitch,zoom,kind,before:before.length,after:after.length}));
   rows.push({width,height,pitch,zoom,kind,normal:before.length,phone:after.length});
  }
 }
 assert.ok(highTotal>phoneTotal&&phoneTotal>lowTotal,'Slider changes aggregate native coverage');
 assert.ok(phoneTotal<normalTotal*.95,'Must reduce aggregate tile demand');
 console.log(JSON.stringify({actualNativeCovering:true,scenes:rows.length,normalTotal,phoneTotal,lowTotal,highTotal,distanceWeightedGain:gains,viewedFaceWithinOneLOD:true,caveat:'Controlled camera/frustum/elevation coverage; not a phone FPS or real-terrain benchmark.'}));
})().catch(e=>{console.error(e);process.exitCode=1});
