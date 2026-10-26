const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),{execFileSync}=require('node:child_process'),{createHash}=require('node:crypto');
const T=require('../public/tile-cache.js'),MiB=1048576,baseline='5cc27bd6bd725087f81b9402e079471cd4a708b2';
(async()=>{
 const fields=['imagery','elevation','views','spare','imageryBytes','elevationBytes'];
 for(const memory of [2,4,8])for(const saveData of [false,true])for(let n=96;n<=3000;n+=4){const p=T.budgetPlan(n,{memory,saveData});assert.equal(p.totalMiB,n);assert.equal(fields.reduce((sum,k)=>sum+p[k],0),n);assert.ok(fields.every(k=>p[k]>0));}
 assert.equal(T.budgetPlan(undefined,{memory:4}).totalMiB,352);assert.equal(T.budgetPlan(undefined,{memory:2}).totalMiB,188);assert.equal(T.budgetPlan(9999).totalMiB,3000);
 // Resize real byte caches without discarding hits; shrink evicts only LRU.
 let requests=0;const c=T.createCache({maxBytes:8,fetcher:async url=>{requests++;return new Response(new Uint8Array(4),{headers:{'cache-control':'max-age=3600'}});}});
 await c.load('a');await c.load('b');c.setBudget(16);await c.load('a');assert.equal(requests,2);c.setBudget(4);await c.load('a');assert.equal(requests,2);assert.equal(c.stats().bytes,4);assert.equal(c.stats().budgetBytes,4);c.clear();
 // Real native pool wiring and active source texture protection on resize.
 global.RidgeTiles=T;global.RidgeRasterResidency=require('../public/raster-residency.js');
 const E=await import('../public/assets/maplibre-gl.mjs');global.window={requestAnimationFrame:fn=>setTimeout(fn,0),cancelAnimationFrame:clearTimeout};
 const map={_maxTileCacheSize:192,_maxTileCacheZoomLevels:8,_requestManager:{transformRequest:async url=>({url})},_refreshExpiredTiles:false};
 const style=Object.assign(new E.Evented(),{map,tileManagers:{},dispatcher:{},_layers:{},_checkLoaded(){},_validate:()=>false});
 E.Style.prototype.addSource.call(style,'linz',{type:'raster',tiles:['https://example.test/{z}/{x}/{y}.webp'],tileSize:256,maxzoom:19});await new Promise(r=>setTimeout(r,10));
 const manager=style.tileManagers.linz,source=manager.getSource();let active;
 source.loadTile=async tile=>{tile.texture={size:[256,256]};tile.state='loaded';};source.unloadTile=tile=>{tile.texture=null;};source.abortTile=()=>{};manager.used=true;
 const provider={getCenter:t=>t.center,getWrap:()=>0,allowVariableZoom:()=>false,allowWorldCopies:()=>false,distanceToTile2d:()=>0,getTileBoundingVolume({z,x,y}){return{intersectsFrustum:()=>z<=8&&x===Math.floor(100/2**(8-z))&&y===Math.floor(100/2**(8-z))?1:0};}};
 const transform={center:new E.LngLat(0,0),elevation:0,pitch:58,bearing:0,bearingInRadians:0,cameraToCenterDistance:500,worldSize:512*2**7,tileSize:512,maxZoom:19,zoom:7,fov:36.87,renderWorldCopies:false,width:256,height:256,populateCache(){},getCameraFrustum:()=>({}),getClippingPlane:()=>null,getCoveringTilesDetailsProvider:()=>provider};
 map.style=style;const events=new E.Evented();map.on=events.on.bind(events);map.off=events.off.bind(events);let total=352;const controller=require('../public/native-residency.js').attach(map,{engineVersion:'6.14.0',cacheMiB:()=>total});manager.update(transform,null);await new Promise(r=>setImmediate(r));active=manager._inViewTiles.getAllTiles()[0];assert.ok(active.texture);const texture=active.texture;
 for(const target of [3000,96,352]){total=target;controller.refresh();assert.equal(controller.stats().cacheBudgetMiB,target);assert.equal(controller.stats().sourceBudgetBytes,T.budgetPlan(target).imagery*MiB);assert.equal(controller.stats().demSourceBudgetBytes,T.budgetPlan(target).elevation*MiB);assert.equal(active.texture,texture,'Resizing must not unload an active source texture');}
 controller.destroy();manager.clearTiles();delete global.RidgeTiles;delete global.RidgeRasterResidency;
 // Actual production UI and persistence, with the native map surface mocked.
 process.env.TEST_FIXTURE='1';const fixture=require('./base-viewer.cjs');delete process.env.TEST_FIXTURE;
 const {context,nodes,getMap}=fixture,timers=new Map(),stored=new Map();let next=0;context.navigator.deviceMemory=4;
 context.setTimeout=fn=>{timers.set(++next,fn);return next;};context.clearTimeout=id=>timers.delete(id);context.localStorage={getItem:k=>stored.get(k)||null,setItem:(k,v)=>stored.set(k,v)};
 const modules=[...fs.readFileSync('public/viewer.mjs','utf8').matchAll(/import '\.\/([^'?]+)(?:\?[^']*)?';/g)].map(m=>m[1]);for(const name of modules)vm.runInContext(fs.readFileSync('public/'+name,'utf8'),context);vm.runInContext(fs.readFileSync('public/app.js','utf8'),context);const native=getMap();native.trigger('style.load');native.trigger('load');
 assert.equal(nodes['cache-budget'].value,'352');const paint=JSON.stringify(native.layers.linz.paint),urls=JSON.stringify(native.sources.linz.tiles),detail=vm.runInContext('JSON.stringify([detailSettings.imagery,detailSettings.terrain])',context);
 for(const target of [3000,96,352]){nodes['cache-budget'].value=String(target);nodes['cache-budget'].oninput();const timer=vm.runInContext('detailTimer',context);assert.ok(timers.has(timer));timers.get(timer)();timers.delete(timer);assert.equal(nodes['cache-budget-value'].textContent,target+' MiB');assert.equal(JSON.parse(stored.get('ridgeline-detail-v1')).cacheMiB,target);assert.equal(vm.runInContext('RidgeTiles.install(maplibregl).stats().budgetMiB',context),target);assert.equal(vm.runInContext('renderQuality.stats().cacheBudgetMiB',context),target);assert.equal(getMap(),native);assert.ok(!native.removed);assert.equal(JSON.stringify(native.layers.linz.paint),paint);assert.equal(JSON.stringify(native.sources.linz.tiles),urls);assert.equal(vm.runInContext('JSON.stringify([detailSettings.imagery,detailSettings.terrain])',context),detail);}
 const prefix=fs.readFileSync('public/app.js','utf8').split('let renderQuality')[0];
 function restore(saved){const c=vm.createContext({document:{getElementById(){}},RidgeGeo:{},RidgeAlpine:{peaks:[]},RidgeTiles:T,navigator:{deviceMemory:4},matchMedia:()=>({matches:false}),localStorage:{getItem:()=>JSON.stringify(saved),setItem(){}}});vm.runInContext(prefix,c);return vm.runInContext('detailSettings.cacheMiB',c);}
 assert.equal(restore({cacheMiB:3000,cacheProfile:2,residentMB:96}),3000);assert.equal(restore({cacheProfile:2,residentMB:96}),352);assert.equal(restore({cacheProfile:2,residentMB:192}),448);assert.equal(restore({cacheMiB:9999}),3000);
 // Resize spare targets too; only unused objects may be destroyed.
 let spareDrops=0;const spares={_rttObjectRecyclePool:Array.from({length:4},()=>({size:1024,texture:{destroy(){spareDrops++;}}})),releaseRTT(){}};
 const spareControl=require('../public/native-residency.js').boundRecyclePool(spares,32*MiB);assert.equal(spareDrops,0);spareControl.setBudget(4*MiB);assert.equal(spareDrops,4);assert.equal(spareControl.stats().spareRTTBudgetBytes,4*MiB);spareControl.destroy();
 const unchanged={};for(const name of ['imagery.js','south-overviews.js','assets/west-coast-mask.png']){const old=execFileSync('git',['show',baseline+':public/'+name]);const current=fs.readFileSync('public/'+name);assert.equal(createHash('sha256').update(current).digest('hex'),createHash('sha256').update(old).digest('hex'));unchanged[name]=createHash('sha256').update(current).digest('hex');}
 // Routing/worker/visibility/streaming and phone LOD evolve independently of the slider. Their focused
 // regression suites now own those contracts; imagery/mask/vendor identities remain pinned.
 assert.ok(fs.readFileSync('public/app.js','utf8').includes('ridge-reference=cook-reference-v1'));
 const html=fs.readFileSync('public/index.html','utf8');assert.match(html,/id="cache-budget"[^>]*min="96"[^>]*max="3000"[^>]*step="4"/);assert.ok(!html.includes('id="view-cache"'));
 const report={baseline,rangeMiB:[96,3000],stepMiB:4,defaultFourGBMiB:352,allSixBudgetsSumToTarget:true,liveResize:'3000→96→352 without map recreation or active texture loss',persistedAndLegacyMigration:'passed',nativeSourcePoolResize:'passed',byteCacheRetainsHitsAndTrimsLRU:'passed',detailAndColourUnchanged:true,unchangedFiles:unchanged,caveat:'Actual native source/cache classes plus production UI with mocked map/GPU; no live browser/GPU QA.'};fs.writeFileSync('CACHE_SLIDER_VALIDATION.json',JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
})().catch(e=>{console.error(e);process.exitCode=1});
