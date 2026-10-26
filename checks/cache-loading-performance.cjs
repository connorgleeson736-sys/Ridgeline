const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),{execFileSync}=require('node:child_process'),{performance}=require('node:perf_hooks');
const baselineCommit='906759f6d16e4b1edc364666f331beddaf77fdc7';
const read=(name,old=false)=>old?execFileSync('git',['show',baselineCommit+':public/'+name],{encoding:'utf8'}):fs.readFileSync('public/'+name,'utf8');
function load(name,old=false,globals={}){const module={exports:{}};globals={RidgeTileRetry:require('../public/tile-retry.js'),...globals};vm.runInNewContext(read(name,old),{module,performance,...globals});return module.exports;}
(async()=>{
 const E=await import('../public/assets/maplibre-gl.mjs'),S=require('../public/terrain-streaming.js');
 // Actual native RTT preparation; no browser/GPU/FPS claims. Repeated complete
 // frames should not rebuild the source coverage hierarchy.
 function renderer(old){let builds=0,demReads=0;const C=load('native-residency.js',old,{RidgeTerrainStreaming:S}),painter={acquireRTT:size=>({size,texture:{}}),releaseRTT(){}},sourceManager={_source:{tileSize:256,type:'raster-dem'},map:{painter},reload(){},update(){}};
  const map={terrain:null,style:{_checkLoaded(){},off(){},on(){},tileManagers:{terrain:sourceManager},_layers:{},triggerSymbolPlacement(){}},painter,_camera:{setTerrain(){}},fire(){}};
  E.Map.prototype.setTerrain.call(map,{source:'terrain',exaggeration:1});const m=map.terrain.tileManager;m.getSourceTile=()=>{demReads++;return{dem:{}};};
  const wanted={z:3,x:4,y:4},provider={getCenter:t=>t.center,getWrap:()=>0,allowVariableZoom:()=>false,allowWorldCopies:()=>false,distanceToTile2d:()=>0,getTileBoundingVolume({z,x,y}){const shift=wanted.z-z,visible=shift>=0?x===Math.floor(wanted.x/2**shift)&&y===Math.floor(wanted.y/2**shift):Math.floor(x/2**-shift)===wanted.x&&Math.floor(y/2**-shift)===wanted.y;return{intersectsFrustum:()=>visible?(z>=wanted.z?2:1):0};}};
  const t={center:new E.LngLat(0,0),elevation:100,pitch:58,bearing:0,cameraToCenterDistance:500,worldSize:4096,tileSize:512,maxZoom:19,zoom:6,fov:36.87,renderWorldCopies:false,getCameraFrustum:()=>({}),getClippingPlane:()=>null,getCoveringTilesDetailsProvider:()=>provider};
  const cache=C.retainDrapes(m,painter,{readiness:()=>{builds++;return S.imageReadiness(map);}});m.update(t,map.terrain);const tiles=m.getRenderableTiles(),ids=tiles.map(t=>t.tileID);
  map.getZoom=()=>6;const photo={getSource:()=>({type:'raster'}),getState:()=>({revision:0}),getVisibleCoordinates:()=>ids,getTile:()=>({texture:{}})};
  map.style={_order:['photo'],_layers:{photo:{id:'photo',type:'raster',source:'linz',isHidden:()=>false}},tileManagers:{linz:photo}};
  const prepare=()=>painter.renderToTexture.prepareForRender(map.style,6,false);prepare();
  for(const tile of tiles){tile.acquireRTT(painter,0,512);cache.recordImageCoverage(tile,()=>true);tile.rttFingerprint.linz=painter.renderToTexture._rttFingerprints.linz[tile.tileID.key];}
  for(let i=0;i<20;i++)prepare();builds=0;const samples=[];
  for(let sample=0;sample<7;sample++){const start=performance.now();for(let i=0;i<100;i++)prepare();samples.push((performance.now()-start)/100);}
  const sorted=samples.slice().sort((a,b)=>a-b),coverageBuilds=builds,updates=[];demReads=0;for(let sample=0;sample<7;sample++){const start=performance.now();for(let i=0;i<100;i++)m.update(t,map.terrain);updates.push((performance.now()-start)/100);}const result={terrainTiles:tiles.length,completeFrames:700,coverageBuilds,medianPreparationMs:sorted[3],samplesMs:samples,stableUpdateDEMReads:demReads,medianUpdateMs:updates.slice().sort((a,b)=>a-b)[3],updateSamplesMs:updates};cache.destroy();m.destruct();return result;
 }
 const before=renderer(true),after=renderer(false);assert.equal(before.terrainTiles,after.terrainTiles);assert.equal(before.coverageBuilds,700);assert.equal(after.coverageBuilds,0,'Complete cached frames must skip rebuilding readiness');assert.ok(before.stableUpdateDEMReads>=64*700);assert.equal(after.stableUpdateDEMReads,0,'Unchanged render sets must not repeat transition DEM lookups');
 // Byte-pool protection traversal is only needed when exceeding its budget.
 function dependencyScans(old){let scans=0;const P=load('raster-residency.js',old),cache={data:{},setMaxSize(){},getAndRemove(){},getByKey(){},add(id,tile){(this.data[id.key]??=[]).push({value:tile});},onRemove(){},reset(){this.data={};}};
  const manager={getSource:()=>({type:'raster-dem',tileSize:256}),_outOfViewCache:cache,_inViewTiles:{deleteTileById(){}},updateCacheSize(){},_removeTile(){},on(){},off(){}};
  const pool=P.createPool({bytes:48*1048576,types:['raster-dem'],protect:()=>{scans++;return new Set();}});pool.attach(manager);
  for(let i=0;i<32;i++){const id={key:i,wrapped(){return this;}},tile={tileID:id,dem:{stride:260,data:{byteLength:260*260*4}},state:'loaded'};cache.add(id,tile);}
  const stats=pool.stats();pool.destroy();return{scans,bytes:stats.sourceResidentBytes};
 }
 const oldProtection=dependencyScans(true),newProtection=dependencyScans(false);assert.equal(oldProtection.scans,32);assert.equal(newProtection.scans,0);assert.equal(oldProtection.bytes,newProtection.bytes);
 // Exercise production budget wiring at real reported device-memory classes.
 function budgets(memory,saveData=false){const pools=[],h={};const C=load('native-residency.js',false,{navigator:{deviceMemory:memory,connection:{saveData}},RidgeRasterResidency:{createPool(opts){pools.push(opts);return{attach(){},destroy(){},stats:()=>({sourceBudgetBytes:opts.bytes})};}}});
  const map={style:{tileManagers:{}},on(n,f){(h[n]??=[]).push(f);},off(){}};const controller=C.attach(map,{engineVersion:'6.14.0'});controller.destroy();const compressed=require('../public/tile-cache.js').createTileCaches({memory,saveData}),stats=compressed.stats();compressed.destroy();
  const app=fs.readFileSync('public/app.js','utf8').split('let renderQuality')[0],store=new Map(),ctx=vm.createContext({document:{getElementById(){}},RidgeGeo:{},RidgeAlpine:{peaks:[]},matchMedia:()=>({matches:false}),navigator:{deviceMemory:memory},RidgeTiles:require('../public/tile-cache.js'),localStorage:{getItem:k=>store.get(k)||null,setItem:(k,v)=>store.set(k,v)}});
  vm.runInContext(app,ctx);const resident=vm.runInContext('detailSettings.residentMB',ctx);
  const result={memory,saveData,imageryMiB:pools[0].bytes/1048576,demMiB:pools[1].bytes/1048576,viewsMiB:resident,spareMiB:saveData?8:16,imageryBytesMiB:stats.imagery.budgetBytes/1048576,demBytesMiB:stats.terrain.budgetBytes/1048576,unfinishedImagery:pools[0].pendingLimit,unfinishedDEM:pools[1].pendingLimit};result.totalMiB=result.imageryMiB+result.demMiB+result.viewsMiB+result.spareMiB+result.imageryBytesMiB+result.demBytesMiB;return result;
 }
 const profiles=[budgets(2),budgets(4),budgets(8),budgets(4,true)];assert.equal(profiles[1].totalMiB,352);assert.equal(profiles[2].totalMiB,352);assert.equal(profiles[0].totalMiB,188);assert.equal(profiles[3].totalMiB,168);assert.equal(profiles[1].unfinishedImagery,4);assert.equal(profiles[1].unfinishedDEM,2);
 // Migrate legacy automatic 48 MiB once; preserve subsequent user choices.
 const startup=fs.readFileSync('public/app.js','utf8').split('let renderQuality')[0];
 const selected=(saved,memory=4)=>{let written;const c=vm.createContext({document:{getElementById(){}},RidgeGeo:{},RidgeAlpine:{peaks:[]},matchMedia:()=>({matches:false}),navigator:{deviceMemory:memory},RidgeTiles:require('../public/tile-cache.js'),localStorage:{getItem:()=>JSON.stringify(saved),setItem:(k,v)=>written=JSON.parse(v)}});vm.runInContext(startup,c);return{settings:vm.runInContext('JSON.stringify(detailSettings)',c),written};};
 assert.equal(JSON.parse(selected({residentMB:48,imagery:1}).settings).residentMB,96);assert.equal(JSON.parse(selected({residentMB:48,cacheProfile:2}).settings).residentMB,48);assert.equal(JSON.parse(selected({residentMB:192}).settings).residentMB,192);assert.equal(JSON.parse(selected({residentMB:48},2).settings).residentMB,48);
 // Warmups don't allocate unused output copies; visible consumers still get
 // independently transferable buffers and share a pending network request.
 const T=require('../public/tile-cache.js');let finish,fetches=0;const bytes=new Uint8Array([1,2,3]).buffer,c=T.createCache({fetcher:async()=>{fetches++;await new Promise(r=>finish=r);return new Response(bytes,{headers:{'cache-control':'max-age=3600'}});}});
 const warm=c.load('image',undefined,'low',false),visible=c.load('image');finish();const [w,v]=await Promise.all([warm,visible]);assert.equal(w.data,undefined);assert.equal(v.data.byteLength,3);assert.equal(fetches,1);new Uint8Array(v.data)[0]=99;assert.equal(new Uint8Array((await c.load('image')).data)[0],1);c.clear();
 const report={baselineCommit,profiles,completeCachedFrames:{before,after},underBudgetDependencyScans:{before:oldProtection.scans,after:newProtection.scans},legacyDefaultUpgrade:'passed',userCacheChoicePreserved:'passed',prefetchCopyAvoidedAndVisibleBufferIsolation:'passed',caveat:'CPU microbenchmark with actual native RTT preparation and controlled GPU/source fixtures; not device FPS, network latency, or total GPU memory.'};fs.writeFileSync('CACHE_LOADING_VALIDATION.json',JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
})().catch(e=>{console.error(e);process.exitCode=1});
