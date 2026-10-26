/* Actual pinned native scheduler plus focused production lifecycle tests.
   No real GPU/frame-rate assertion. */
const assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),vm=require('node:vm');
(async()=>{
 const folder=fs.mkdtempSync(path.join(os.tmpdir(),'ridge-scheduler-'));
 try{
 const enginePath=path.join(folder,'engine.mjs');fs.writeFileSync(enginePath,fs.readFileSync('public/assets/maplibre-gl.mjs','utf8')+'\nexport {Un as AuditQueue,UP as AuditRTT};');
 const E=await import('file://'+enginePath);global.HTMLImageElement=class HTMLImageElement{};
 const oldMoving=E.config.MAX_PARALLEL_IMAGE_REQUESTS_PER_FRAME,oldStationary=E.getMaxParallelImageRequests();let moving=true,peak=0;const started=[],resolveSlow=[];
 E.AuditQueue.resetRequestQueue();E.config.MAX_PARALLEL_IMAGE_REQUESTS_PER_FRAME=8;E.setMaxParallelImageRequests(12);E.AuditQueue.addThrottleControl(()=>moving);
 E.setImageRequestPolicy(r=>({cached:r.url.includes('/ready/'),priority:0}));
 E.addProtocol('fixture',async r=>{started.push(r.url);peak=Math.max(peak,E.getImageRequestQueueStats().active);if(r.url.includes('/slow/'))await new Promise(resolve=>resolveSlow.push(resolve));return{data:new HTMLImageElement()};});
 const jobs=Array.from({length:8},(_,i)=>E.AuditQueue.getImage({url:'fixture://tiles/slow/'+i},new AbortController()));
 const returns=Array.from({length:6},(_,i)=>E.AuditQueue.getImage({url:'fixture://tiles/ready/'+i},new AbortController()));
 await Promise.all(returns);assert.equal(started.filter(v=>v.includes('/ready/')).length,6);assert.equal(resolveSlow.length,7);assert.equal(peak,8);assert.equal(E.getImageRequestQueueStats().active,7);
 const canceled=new AbortController();const blocked=E.AuditQueue.getImage({url:'fixture://tiles/other/1'},canceled);canceled.abort();E.pumpImageRequestQueue();await assert.rejects(blocked);assert.ok(!started.some(v=>v.includes('/other/')));
 moving=false;E.pumpImageRequestQueue();for(const resolve of resolveSlow)resolve();await Promise.all(jobs);assert.equal(E.getImageRequestQueueStats().active,0);
 // Exercise the real sliders against the native queue, including values above ten.
 const Controls=require('../public/loading-controls.js'),limitsTested=[];
 let held=[];E.addProtocol('held',async()=>{await new Promise(resolve=>held.push(resolve));return{data:new HTMLImageElement()};});
 async function drain(all){while(E.getImageRequestQueueStats().active||E.getImageRequestQueueStats().queued){const batch=held;held=[];batch.forEach(resolve=>resolve());await new Promise(resolve=>setImmediate(resolve));}await Promise.all(all);}
 for(const mode of [true,false])for(const limit of [1,12,24,96]){
  moving=mode;Controls.applyEngine(E,{moving:mode?limit:3,stationary:mode?3:limit});
  const reserve=Math.min(1,limit-1),network=Array.from({length:limit+3},(_,i)=>E.AuditQueue.getImage({url:'held://tiles/slow/'+i},new AbortController()));
  assert.equal(E.getImageRequestQueueStats().active,limit-reserve);
  const ready=Array.from({length:4},(_,i)=>E.AuditQueue.getImage({url:'held://tiles/ready/'+i},new AbortController()));
  assert.equal(E.getImageRequestQueueStats().active,limit,'Selected slider is the total cap');
  assert.equal(E.getImageRequestQueueStats().limit,limit);limitsTested.push({moving:mode,limit,active:E.getImageRequestQueueStats().active});await drain([...network,...ready]);
 }
 moving=true;Controls.applyEngine(E,{moving:8,stationary:24});
 const dynamic=Array.from({length:40},(_,i)=>E.AuditQueue.getImage({url:'held://tiles/slow/'+i},new AbortController()));
 assert.equal(E.getImageRequestQueueStats().active,7);Controls.applyEngine(E,{moving:24,stationary:24});assert.equal(E.getImageRequestQueueStats().active,23,'Raising a slider pumps immediately');
 Controls.applyEngine(E,{moving:4,stationary:24});assert.equal(E.getImageRequestQueueStats().active,23,'Lowering preserves running jobs');
 const batch=held;held=[];batch.slice(0,20).forEach(resolve=>resolve());await new Promise(resolve=>setImmediate(resolve));assert.equal(E.getImageRequestQueueStats().active,3,'Queue drains to new network budget');held.push(...batch.slice(20));await drain(dynamic);
 E.setImageRequestPolicy(null);E.config.MAX_PARALLEL_IMAGE_REQUESTS_PER_FRAME=oldMoving;E.setMaxParallelImageRequests(oldStationary);delete global.HTMLImageElement;
 // Simulated fingerprints exercise the unmodified native preparation path.
 const tiles=Array.from({length:12},(_,i)=>({tileID:{key:i},stale:true,releaseRTT(){this.stale=false;}}));
 const rtt=new E.AuditRTT({}, {qualityFactor:2,tileManager:{tileSize:256,getRenderableTiles:()=>tiles}});rtt._lastPrepareZoom=14;rtt._textureDifference=t=>t.stale?'zoom':'none';
 const style={_order:[],_layers:{},tileManagers:{}};for(let f=0;f<6;f++){const before=tiles.filter(t=>!t.stale).length;rtt.prepareForRender(style,15+f/100,true);assert.equal(tiles.filter(t=>!t.stale).length-before,2);}
 assert.ok(tiles.every(t=>!t.stale));
 rtt._ridgeCostPerPatch=10;tiles.forEach(t=>t.stale=true);rtt.prepareForRender(style,16,true);assert.equal(tiles.filter(t=>!t.stale).length,1,'Slow submissions reduce next-frame refresh count');
 // Native queue/caches share stable attempt IDs; summary survives ring rollover.
 const Trace=require('../public/tile-trace.js');global.RidgeTileTrace=Trace;const D=require('../public/imagery-diagnostics.js'),events={};const m={style:{tileManagers:{}},on(n,f){events[n]=f;},off(){},getCenter:()=>({lng:170,lat:-43}),getZoom:()=>15,getPitch:()=>58,getBearing:()=>0};
 const d=D.attach(m),q={z:15,x:2,y:3};const attempt=Trace.emit('source-load-start','linz',q);Trace.emit('native-image-queued','linz',q);Trace.emit('native-image-admitted','linz',q);Trace.emit('request-queued','linz',q);Trace.emit('source-ready-upload-submitted','linz',q,{attempt,elapsedMs:12});
 const snap=d.snapshot();assert.ok(snap.stages.every(r=>r.id===attempt));assert.equal(snap.stageTimings['source-ready-upload-submitted'].p50Ms,12);
 for(let i=0;i<1000;i++)Trace.emit('source-adopted','linz',q);assert.equal(d.snapshot().stages.length,384);assert.equal(d.snapshot().stageTimings['source-ready-upload-submitted'].totalSamples,1);d.destroy();delete global.RidgeTileTrace;
 // One canceled input subscriber cannot cancel another; copies and cache policy survive.
 const Responses=require('../public/response-cache.js');let finish,network=0,aborted=false;const inputs=Responses.create({fetcher:async(url,o)=>{network++;o.signal.addEventListener('abort',()=>aborted=true);await new Promise(r=>finish=r);return new Response(new Uint8Array([1,2,3]),{headers:{'Cache-Control':'max-age=60','Content-Type':'image/webp'}});}});
 const a=new AbortController(),b=new AbortController(),first=inputs.fetch('https://input.test/1',{signal:a.signal}),second=inputs.fetch('https://input.test/1',{signal:b.signal});a.abort();await assert.rejects(first);assert.equal(aborted,false);finish();const bytes=new Uint8Array(await(await second).arrayBuffer());bytes[0]=9;assert.deepEqual([...new Uint8Array(await(await inputs.fetch('https://input.test/1')).arrayBuffer())],[1,2,3]);assert.equal(network,1);inputs.destroy();
 // Distinct errors, successful resets and bounded provider recovery.
 const Health=require('../public/provider-health.js');let fallback=0,restores=0,time=0,timer,allow=false;const h=Health.create({fallback:()=>fallback++,restore:()=>restores++,warm:async()=>allow,clock:()=>time,setTimer:fn=>(timer=fn,1),clearTimer:()=>timer=null});
 const error=x=>h.error({sourceId:'terrain',tile:{tileID:{canonical:{z:12,x,y:1}}},error:{status:503}});
 for(let i=0;i<10;i++)error(1);assert.equal(fallback,0);error(2);h.data({sourceId:'terrain',tile:{state:'loaded'}});error(3);error(4);assert.equal(fallback,0);error(5);error(6);assert.equal(fallback,1);let callback=timer;timer=null;await callback();assert.equal(restores,0);allow=true;callback=timer;timer=null;await callback();assert.equal(restores,1);h.destroy();assert.equal(timer,null);
 // Recovery admits complete measured covers atomically, with cleanup on failure.
 class Tile{constructor(id){this.tileID=id;this.state='loading';}}const id=x=>({key:x,canonical:{z:11,x,y:1},wrapped(){return this;}});
 const samples=Array.from({length:4},(_,x)=>Object.assign(new Tile(id(x)),{dem:{},state:'loaded'}));
 function warmFixture(fail=false){const cached=new Map();let unloaded=0,loads=0;const source={tileSize:256,async loadTile(t){loads++;if(fail&&t.tileID.key===1)throw Error('offline');await Promise.resolve();t.dem={data:new Uint8Array(4)};},unloadTile(){unloaded++;},abortTile(){}};
  const manager={getSource:()=>source,getTile:()=>null,_outOfViewCache:{getByKey:k=>cached.get(k),add:(key,t)=>cached.set(key.key,t)}};
  return{map:{style:{tileManagers:{terrain:manager,'terrain-fallback':{_inViewTiles:{getAllTiles:()=>samples}}}}},cached,stats:()=>({unloaded,loads})};}
 const warmed=warmFixture();assert.equal(await Health.warmCover(warmed.map,new AbortController().signal),true);assert.equal(warmed.cached.size,4);
 const failedWarm=warmFixture(true);assert.equal(await Health.warmCover(failedWarm.map,new AbortController().signal),false);assert.equal(failedWarm.cached.size,0);assert.equal(failedWarm.stats().unloaded,failedWarm.stats().loads);
 // Pressure hysteresis applies an effective policy without overwriting preferences.
 const navigation={exports:{}};let navTime=0,underPressure=true;vm.runInNewContext(fs.readFileSync('public/navigation-policy.js','utf8'),{module:navigation,performance:{now:()=>navTime},document:{hidden:false},URL});
 const handlers={},pressureChanges=[],navMap={style:{tileManagers:{}},on:(n,f)=>handlers[n]=f,off:n=>delete handlers[n]},cache={stats:()=>({}),setViewPolicy(){},setPressure:v=>pressureChanges.push(v)};
 const nav=navigation.exports.attach(navMap,{engine:{},cache,residency:{resources:()=>({pressure:underPressure}),setPressure(){}},quality:{setMemoryPressure(){}}});
 for(let i=0;i<3;i++){navTime+=1201;handlers.render();}assert.equal(nav.stats().memoryConstrained,true);underPressure=false;for(let i=0;i<7;i++){navTime+=1201;handlers.render();}assert.equal(nav.stats().memoryConstrained,true);navTime+=1201;handlers.render();assert.equal(nav.stats().memoryConstrained,false);nav.destroy();assert.deepEqual(pressureChanges.slice(0,2),[true,false]);assert.equal(Object.keys(handlers).length,0);
 // Final route keys, including focused exact waypoints, must fit the limit.
 const G=require('../public/geo.js'),Terrain=require('../public/terrain-core.js');let requests=0;const heights=new Float32Array(65536).fill(2000),engine=Terrain.createEngine({G,fetcher:async()=>{requests++;return new Response(new Uint8Array([1]));},decoder:async()=>heights});let result;
 const points=Array.from({length:2000},(_,i)=>[166+i/1999*11,-45+Math.sin(i/50)*.2]);await engine.run({points,key:'test',bounds:[165,-49,180,-33],detailOnly:true},new AbortController().signal,r=>result=r);
 assert.ok(result.metrics.requestedTileCount<=64);assert.ok(result.profile.length>=2000);assert.ok(requests<=64);
 console.log(JSON.stringify({cachedReturnWhileMoving:true,defaultMovingPeak:peak,sliderLimits:limitsTested,queuedAbortSettles:true,movingZoomRefresh:true,costLimitedRefresh:true,correlatedPersistentTimings:true,inputDedupAndIndependentCancellation:true,providerRecovery:true,focusedRouteTileBudget:result.metrics.requestedTileCount,caveat:'Actual native CPU scheduling, controlled transport/decode/GPU; not Chromebook frame-time measurements.'}));
 }finally{fs.rmSync(folder,{recursive:true,force:true});}
})().catch(e=>{console.error(e);process.exitCode=1});
