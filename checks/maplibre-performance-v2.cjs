const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),os=require('node:os'),path=require('node:path');
(async()=>{
 // Burst complexity and cache-lane isolation use the exact shipped queue.
 const folder=fs.mkdtempSync(path.join(os.tmpdir(),'ridge-v2-'));let queueReport;
 try{
  const file=path.join(folder,'engine.mjs');fs.writeFileSync(file,fs.readFileSync('public/assets/maplibre-gl.mjs','utf8')+'\nexport {Un as Queue};');const E=await import('file://'+file);global.HTMLImageElement=class{};
  E.Queue.resetRequestQueue();E.config.MAX_PARALLEL_IMAGE_REQUESTS_PER_FRAME=8;E.Queue.addThrottleControl(()=>true);const policy=()=>({cached:false,priority:0});policy.reserve=()=>0;E.setImageRequestPolicy(policy);
  E.addProtocol('held',async(request,controller)=>new Promise((resolve,reject)=>{controller.signal.addEventListener('abort',()=>reject(Object.assign(Error('aborted'),{name:'AbortError'})),{once:true});}));
  const controllers=Array.from({length:512},()=>new AbortController()),jobs=controllers.map((c,i)=>E.Queue.getImage({url:'held://tiles/'+i},c).catch(e=>e));
  queueReport=E.getImageRequestQueueStats();assert.equal(queueReport.active,8,'Cold cache loans every slot');assert.equal(queueReport.queued,504);assert.ok(queueReport.policyEvaluations<1100,'Burst indexing is linear; no queue-wide scan per insertion');
  controllers.forEach(c=>c.abort());await Promise.all(jobs);assert.equal(E.getImageRequestQueueStats().queued,0);assert.equal(E.getImageRequestQueueStats().active,0);E.removeProtocol('held');
  E.Queue.resetRequestQueue();E.Queue.addThrottleControl(()=>true);const readyURLs=new Set(),wakePolicy=request=>({cached:readyURLs.has(request.url),priority:0});wakePolicy.key=request=>request.url;E.setImageRequestPolicy(wakePolicy);
  E.addProtocol('wake',async(request,controller)=>{if(readyURLs.has(request.url))return{data:new HTMLImageElement()};return new Promise((resolve,reject)=>controller.signal.addEventListener('abort',()=>reject(Object.assign(Error('cancel'),{name:'AbortError'})),{once:true}));});
  const slowControllers=Array.from({length:7},()=>new AbortController()),slow=slowControllers.map((c,i)=>E.Queue.getImage({url:'wake://slow/'+i},c).catch(e=>e));
  const cachedURL='wake://cached/1',cached=E.Queue.getImage({url:cachedURL},new AbortController());assert.equal(E.getImageRequestQueueStats().queued,1);readyURLs.add(cachedURL);E.notifyImageRequestBytesReady(cachedURL);let timeout;await Promise.race([cached,new Promise((resolve,reject)=>{timeout=setTimeout(()=>reject(Error('Ready bytes stranded behind cold jobs')),1000);})]);clearTimeout(timeout);assert.equal(E.getImageRequestQueueStats().active,7);slowControllers.forEach(c=>c.abort());await Promise.all(slow);E.removeProtocol('wake');delete global.HTMLImageElement;
 }finally{fs.rmSync(folder,{recursive:true,force:true});}
 // Coordinate index never falls back to the full-array filter.
 const N=require('../public/native-residency.js'),keys=Array.from({length:512},(_,i)=>i),manager={_tiles:Object.fromEntries(keys.map(i=>[i,{tileID:{canonical:{z:12,x:i,y:1}}}])),_renderableTilesKeys:keys};
 keys.filter=()=>{throw Error('Quadratic scan');};let candidates=0;const scope={};for(let i=0;i<512;i++)N.relatedCoordinates(manager,function(){candidates+=this._renderableTilesKeys.length;return{};},{canonical:{z:12,x:i,y:1}},scope);assert.equal(candidates,512);
 // Slow DPR-1 frames lower resolution, and a gesture boundary does not reset it.
 let now=1000,ratio=1,resizeCalls=0,timeout;const handlers={},moduleP={exports:{}};
 vm.runInNewContext(fs.readFileSync('public/performance.js','utf8'),{module:moduleP,performance:{now:()=>now},devicePixelRatio:1,document:{hidden:false,body:{classList:{add(){},remove(){}}}},setTimeout:fn=>(timeout=fn,1),clearTimeout(){}});
 const map={getPixelRatio:()=>ratio,getZoom:()=>12,getContainer:()=>({clientWidth:1366,clientHeight:768}),getSource:()=>null,on:(n,f)=>handlers[n]=f,off(){},setPixelRatio:r=>{ratio=r;resizeCalls++;}};
 const quality=moduleP.exports.attach(map,{lowPower:()=>false});handlers.movestart();for(let i=0;i<100;i++){now+=40;handlers.render();}assert.equal(ratio,.85);assert.equal(resizeCalls,1);handlers.moveend();timeout();assert.equal(ratio,.85);quality.destroy();
 // Disabled detailed capture has no trace subscriber; explicit capture toggles it.
 let subscriptions=0;const Dmod={exports:{}},dEvents={};vm.runInNewContext(fs.readFileSync('public/imagery-diagnostics.js','utf8'),{module:Dmod,performance,Float32Array,RidgeTileTrace:{subscribe(){subscriptions++;return()=>subscriptions--;}}});
 const diagnostic=Dmod.exports.attach({on:(n,f)=>dEvents[n]=f,off(){}},{stages:false});assert.equal(subscriptions,0);diagnostic.setEnabled(true);assert.equal(subscriptions,1);diagnostic.setEnabled(false);assert.equal(subscriptions,0);diagnostic.destroy();
 // The shared byte cache marks only native protocol work as Original.
 const T=require('../public/tile-cache.js'),flags=[];let protocol;
 const cache=T.install({addProtocol(name,fn){protocol=fn;},setImageRequestPolicy(){},pumpImageRequestQueue(){}},{fetcher:async(url,o)=>{flags.push(o.ridgeOriginal);return new Response('bytes',{headers:{'cache-control':'max-age=60'}});}});
 await cache.load('ridge://linz/v1/tiles/aerial/WebMercatorQuad/18/1/1.webp');
 await protocol({url:'ridge://linz/v1/tiles/aerial/WebMercatorQuad/18/2/2.webp'},new AbortController());assert.deepEqual(flags,[false,true]);cache.destroy();
 // Slow disk lookups cannot block visible fetch, while fast disk hits do avoid it.
 const R=require('../public/response-cache.js');let fetches=0;const wrapper=R.elevation(async()=>{fetches++;return new Response('network',{headers:{'cache-control':'no-store'}});},{lookupMs:5,cacheStorage:{open:async()=>({match:()=>new Promise(()=>{})})}});
 assert.equal(await(await wrapper('https://fixture/dem')).text(),'network');assert.equal(fetches,1);
 const fast=R.elevation(async()=>{throw Error('Unexpected fetch');},{cacheStorage:{open:async()=>({match:async()=>new Response('disk',{headers:{'cache-control':'max-age=60','x-ridgeline-cached-at':Date.now()}})})}});assert.equal(await(await fast('https://fixture/dem')).text(),'disk');
 let staleFetches=0;const expired=R.elevation(async()=>{staleFetches++;return new Response('fresh',{headers:{'cache-control':'no-store'}});},{cacheStorage:{open:async()=>({match:async()=>new Response('stale',{headers:{'cache-control':'max-age=0','x-ridgeline-cached-at':Date.now()}})})}});assert.equal(await(await expired('https://fixture/expired')).text(),'fresh');assert.equal(staleFetches,1);
 const abortController=new AbortController(),aborted=wrapper('https://fixture/abort',{signal:abortController.signal});abortController.abort();await assert.rejects(aborted,{name:'AbortError'});assert.equal(fetches,1);
 // Real catalog data: no routing/overview entry may be excluded by the index.
 const env=vm.createContext({Promise,Set});for(const f of ['gap-imagery-atlas.js','south-overviews.js','imagery-catalog-index.js'])vm.runInContext(fs.readFileSync('public/'+f,'utf8'),env);
 const requests=[],catalog=new vm.SourceTextModule(fs.readFileSync('public/imagery-catalog.mjs','utf8'),{context:env,importModuleDynamically:async spec=>{requests.push(spec);const module=new vm.SourceTextModule('',{context:env});await module.link(()=>{});await module.evaluate();return module;}});await catalog.link(()=>{});await catalog.evaluate();
 // Compare tile admission against full authoritative manifests in fresh contexts.
 const index=env.RidgeImageryCatalogIndex,fine=new Set(index.gapFine),coarse=new Set(index.gapCoarse);
 for(const key of Object.keys(env.RidgeGapAtlas.entries)){assert.ok(fine.has(key));const[x,y]=key.split('/').map(Number);assert.ok(fine.has(Math.floor((x*64+63)/64)+'/'+Math.floor((y*64+63)/64)));}
 for(const key of Object.keys(env.RidgeGapAtlas.coarse))assert.ok(coarse.has(key));
 for(const key of Object.keys(env.RidgeSouthOverviews.tiles)){const[z,x,y]=key.split('/').map(Number),box=index.south[z];assert.ok(box&&x>=box[0]&&y>=box[1]&&x<=box[2]&&y<=box[3]);}
 await env.RidgeImageryCatalog.ensure({z:18,x:0,y:0});assert.equal(requests.length,0,'Unrelated fine imagery starts without global catalogs');
 const[x,y]=index.gapFine[0].split('/').map(Number);await env.RidgeImageryCatalog.ensure({z:13,x,y});assert.equal(requests.length,2);assert.ok(requests.every(s=>!s.includes('south-overviews')));
 console.log(JSON.stringify({queue:queueReport,coordinateCandidates:candidates,subNativeResolution:ratio,optionalDetailedTracing:true,boundedDiskLookupAndCancellation:true,authoritativeCatalogEntriesChecked:Object.keys(env.RidgeGapAtlas.entries).length+Object.keys(env.RidgeGapAtlas.coarse).length+Object.keys(env.RidgeSouthOverviews.tiles).length,caveat:'Controlled CPU/lifecycle checks; not device FPS.'}));
})().catch(e=>{console.error(e);process.exitCode=1});
