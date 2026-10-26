const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),A=require('../public/prepare-ahead.js');
(async()=>{
 const E=await import('../public/assets/maplibre-gl.mjs'),S=require('../public/terrain-streaming.js');global.RidgeTerrainStreaming=S;
 const overlaps=(a,b)=>{a=a.canonical;b=b.canonical;const n=2**a.z,m=2**b.z;return a.x/n<(b.x+1)/m&&(a.x+1)/n>b.x/m&&a.y/n<(b.y+1)/m&&(a.y+1)/n>b.y/m;};
 const rows=[];let outside=0;
 for(const [width,height]of [[390,844],[844,390],[1200,600]])for(const pitch of [0,45,58,75])for(const angle of [30,50,70]){
  let transform;E.Style.prototype._setProjectionInternal.call({map:{migrateProjection:t=>transform=t},tileManagers:{},_globalState:{}},'mercator');transform.resize(width,height);transform.setZoom(14);transform.setCenter(new E.LngLat(170.141,-43.595));transform.setPitch(pitch);transform.setBearing(-24);transform.setElevation(3000);
  const identity=t=>({width:t.width,height:t.height,fov:t.fov,zoom:t.zoom,lng:t.center.lng,lat:t.center.lat,pitch:t.pitch,bearing:t.bearing,elevation:t.elevation,worldSize:t.worldSize,distance:t.cameraToCenterDistance});
  const before=identity(transform),copy=A.expandedTransform(transform,angle);assert.ok(copy);assert.notEqual(copy,transform);assert.notEqual(copy.getCoveringTilesDetailsProvider(),transform.getCoveringTilesDetailsProvider());
  assert.deepEqual(identity(transform),before);assert.ok(Math.abs(copy.cameraToCenterDistance/transform.cameraToCenterDistance-1)<1e-10);assert.equal(copy.zoom,transform.zoom);assert.equal(copy.center.lng,transform.center.lng);assert.equal(copy.center.lat,transform.center.lat);assert.equal(copy.elevation,transform.elevation);
  const horizontal=t=>2*Math.atan(Math.tan(t.fov*Math.PI/360)*t.width/t.height)*180/Math.PI,extra=horizontal(copy)-horizontal(transform);assert.ok(Math.abs(extra-angle)<.1);
  const source={};E.Map.prototype.setSourceTileLodParams.call({getSource:()=>source,_update(){}},8,3.5,'terrain');const options={tileSize:256,minzoom:0,maxzoom:18,maxContentElevation:0,calculateTileZoom:source.calculateTileZoom},map={_camera:{transform}};
  const visible=E.Map.prototype.coveringTiles.call(map,{...options}),focus={hit:E.MercatorCoordinate.fromLngLat([170.141,-43.595]),tile:{sentinel:true}},sentinel=focus.tile;
  const expanded=A.planExpandedView(map,{engine:E,options,visible,focus,transform:copy});assert.ok(expanded.length);assert.equal(focus.tile,sentinel);assert.deepEqual(identity(transform),before);assert.equal(new Set(expanded.map(t=>t.key)).size,expanded.length);assert.ok(expanded.every(id=>!S.completeCoverage(visible)(id)));assert.ok(expanded.length<=1024);const priorities=expanded.map(id=>A.angularPriority(id,transform));assert.ok(priorities.every((p,i)=>!i||p+1e-9>=priorities[i-1]),'Nearest angular footprint comes first');
  const external=expanded.filter(id=>visible.every(v=>!overlaps(id,v))).length;outside+=external;assert.ok(external>0,'Includes tiles strictly outside the visible tile footprint');rows.push({width,height,pitch,requestedAngle:angle,extraDegrees:extra,visible:visible.length,outside:expanded.length});
 }
 assert.equal(A.normalizeAngle(999),70);assert.equal(A.normalizeAngle(-5),30);assert.equal(A.normalizeAngle(NaN),30);
 assert.ok(outside>0);assert.equal(A.expandedTransform({}),null);
 // The replacement UI has one opt-in control and no legacy radius/ring settings.
 for(const saved of [false,true]){
  process.env.TEST_FIXTURE='1';delete require.cache[require.resolve('./base-viewer.cjs')];const f=require('./base-viewer.cjs');delete process.env.TEST_FIXTURE;
  const ctx=f.context,store=new Map(saved?[['ridgeline-prepare-ahead-v1','1'],['ridgeline-expanded-angle-v1','55']]:[]);ctx.localStorage={getItem:k=>store.get(k)||null,setItem:(k,v)=>store.set(k,v)};
  for(const m of fs.readFileSync('public/viewer.mjs','utf8').matchAll(/import '\.\/([^'?]+)(?:\?[^']*)?';/g))vm.runInContext(fs.readFileSync('public/'+m[1],'utf8'),ctx);
  let opts,refreshes=0;ctx.RidgePrepareAhead={normalizeAngle:A.normalizeAngle,attach(map,o){opts=o;return{refresh(){refreshes++;},destroy(){},stats:()=>({})};}};
  vm.runInContext(fs.readFileSync('public/app.js','utf8'),ctx);f.getMap().trigger('style.load');assert.equal(opts.enabled(),saved);assert.equal(opts.angle(),saved?55:30);assert.equal(f.nodes['expanded-angle-controls'].hidden,!saved);assert.equal(f.nodes['expanded-angle-value'].textContent,(saved?55:30)+'°');assert.equal(opts.radius,undefined);assert.equal(opts.viewBuffer,undefined);assert.equal(f.nodes['prepare-radius'],undefined);assert.equal(f.nodes['view-buffer'],undefined);
  f.nodes['prepare-ahead'].onchange({target:{checked:!saved}});assert.equal(opts.enabled(),!saved);assert.ok(refreshes>0);assert.equal(store.get('ridgeline-prepare-ahead-v1'),saved?'0':'1');assert.equal(f.nodes['expanded-angle-controls'].hidden,saved);const count=refreshes;f.nodes['expanded-angle'].oninput({target:{value:'70'}});assert.equal(opts.angle(),70);assert.equal(store.get('ridgeline-expanded-angle-v1'),'70');assert.equal(f.nodes['expanded-angle-value'].textContent,'70°');assert.ok(refreshes>count);assert.equal(f.getMap().removed,undefined);f.listeners.pagehide({persisted:false});
 }
 const report={cameraUnchanged:true,outsideVisibleFrustum:true,horizontalExtraDegrees:[30,50,70],closestAngularFirst:true,sliderSavedAndLive:true,sameCameraPositionAndPixelDensity:true,nativeCovering:true,sourceFocusRestored:true,legacyRadiusAndRingControlsRemoved:true,savedOptIn:true,testCases:rows.length,caveat:'Native CPU frustum/selection and app fixtures; not phone GPU/frame-rate measurements.'};fs.writeFileSync('EXPANDED_VIEW_VALIDATION.json',JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
 delete global.RidgeTerrainStreaming;
})().catch(e=>{console.error(e);process.exitCode=1});
