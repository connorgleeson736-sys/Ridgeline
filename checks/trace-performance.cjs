const sourceRoot=process.env.RIDGE_PERFORMANCE_SOURCE||'public';
const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const {imageReadiness}=require('../'+sourceRoot+'/terrain-streaming.js');
const {composite}=require('../'+sourceRoot+'/regional-blend.js');
const tick=()=>new Promise(resolve=>setImmediate(resolve));
test('one readiness scan per visible imagery source, without stale results',()=>{
 let scans=0,ready=true;const id={canonical:{z:1,x:0,y:0}};
 const manager={getVisibleCoordinates(){scans++;return[id];},getTile:()=>({texture:ready?{}:null})};
 const map={style:{_layers:{hidden:{type:'raster',source:'linz',layout:{visibility:'none'}},a:{type:'raster',source:'linz'},b:{type:'raster',source:'linz'}},tileManagers:{linz:manager}}};
 assert.equal(imageReadiness(map)(id),true);assert.equal(scans,1);
 ready=false;assert.equal(imageReadiness(map)(id),false);assert.equal(scans,2);
});
test('failed composition closes finished and late decoded images without waiting for another decode',async()=>{
 let late,closed=0;const bitmap=()=>({close(){closed++;}});let calls=0;
 const work=composite({linz:new ArrayBuffer(1),esri:new ArrayBuffer(1),fine:[{data:new ArrayBuffer(1)}],z:1,x:0,y:0},
 {createImageBitmap(){calls++;return calls===1?Promise.resolve(bitmap()):calls===2?Promise.reject(Error('bad image')):new Promise(resolve=>late=resolve);}});
 await assert.rejects(work,/bad image/);assert.equal(closed,1);
 late(bitmap());await tick();assert.equal(closed,2);
});
test('evicted worker masks stay alive until the borrowing composition releases them',async()=>{
 const messages=[],jobs=new Map(),closed=new Map();
 const c={importScripts(){},fetch:async url=>({ok:true,blob:async()=>url}),createImageBitmap:async url=>({close(){closed.set(url,(closed.get(url)||0)+1);}}),RidgeRegionalBlend:{cancelled:()=>Error('cancelled'),async composite(job){await job.mask;await new Promise(resolve=>jobs.set(job.id,resolve));return new ArrayBuffer(1);}},self:{postMessage:m=>messages.push(m)}};
 vm.runInNewContext(fs.readFileSync(sourceRoot+'/regional-imagery-worker.js','utf8'),c);
 const work=[];for(let id=0;id<9;id++){work.push(c.self.onmessage({data:{id,linz:new ArrayBuffer(1),meta:{maskURL:'mask'+id}}}));await tick();}
 assert.equal(closed.get('mask0'),undefined);
 jobs.get(0)();await work[0];assert.equal(closed.get('mask0'),1);
 for(let id=1;id<9;id++)jobs.get(id)();await Promise.all(work);assert.equal(messages.length,9);
});
test('cancelled decode releases its bitmap and image admission slot',async()=>{
 let decoded,closed=0;class Cancelled extends Error{}
 const context={AbortController,URL,Promise,HTMLImageElement:class{},self:{},On:{MAX_PARALLEL_IMAGE_REQUESTS:1,MAX_PARALLEL_IMAGE_REQUESTS_PER_FRAME:1},un:Cancelled,fn:signal=>{if(signal.aborted)throw new Cancelled();},I(){},nt:e=>e,Bt:()=>false,kn:()=>true,Wt:()=>false,Rn:async()=>({data:new ArrayBuffer(1)}),createImageBitmap(){},Gt:()=>new Promise(resolve=>decoded=resolve)};
 context.globalThis=context;vm.runInNewContext(fs.readFileSync('scripts/maplibre-image-queue.js','utf8')+'\nglobalThis.queue=Un;',context);
 const controller=new AbortController(),work=context.queue.getImage({url:'https://example.test/tile'},controller);await tick();controller.abort();decoded({close(){closed++;}});
 await assert.rejects(work,Cancelled);assert.equal(closed,1);assert.equal(context.queue.stats().active,0);
});
test('paint fallback classification is shared only within the current preparation',()=>{
 const {zoomIndependent}=require('../'+sourceRoot+'/native-residency.js');let serializations=0,paint={};
 const layer={type:'raster',serialize(){serializations++;return{paint};}};
 const style={_order:['photo'],_layers:{photo:layer}},cache=new Map();
 for(let i=0;i<100;i++)assert.equal(zoomIndependent({},style,{},12,undefined,cache),true);
 assert.equal(serializations,1);
 paint={'raster-opacity':['interpolate',['linear'],['zoom'],0,0,12,1]};
 assert.equal(zoomIndependent({},style,{},12,undefined,new Map()),false);
});
test('encoded-input response consumers remain independent',async()=>{
 const {create}=require('../'+sourceRoot+'/response-cache.js');let fetches=0;
 const cache=create({fetcher:async()=>{fetches++;return new Response(new Uint8Array([1,2,3]));}});
 const a=await cache.fetch('a'),first=new Uint8Array(await a.arrayBuffer());first[0]=255;
 assert.deepEqual([...new Uint8Array(await(await cache.fetch('a')).arrayBuffer())],[1,2,3]);
 assert.equal(fetches,1);cache.destroy();
});
test('fetch and decode concurrency can be tuned independently without changing source bytes',async()=>{
 let reads=0;const decoders=[];const context={AbortController,URL,Promise,HTMLImageElement:class{},self:{},On:{MAX_PARALLEL_IMAGE_REQUESTS:3,MAX_PARALLEL_IMAGE_REQUESTS_PER_FRAME:3},un:Error,fn:signal=>{if(signal.aborted)throw Error('aborted');},I(){},nt:e=>e,Bt:()=>false,kn:()=>true,Wt:()=>false,Rn:async()=>{reads++;return{data:new ArrayBuffer(1)};},createImageBitmap(){},Gt:()=>new Promise(resolve=>decoders.push(resolve))};
 context.globalThis=context;vm.runInNewContext(fs.readFileSync('scripts/maplibre-image-queue.js','utf8')+'\nglobalThis.queue=Un;',context);
 context.queue.setDecodeLimit(1);
 const controllers=Array.from({length:3},()=>new AbortController());
 const work=controllers.map((controller,i)=>context.queue.getImage({url:'https://example.test/'+i},controller));
 // Attach rejection handlers before cancellation.
 const done=Promise.allSettled(work);await tick();assert.equal(reads,3);assert.equal(decoders.length,1);assert.equal(context.queue.stats().queuedDecodes,2);
 controllers[1].abort();await tick();assert.equal(context.queue.stats().queuedDecodes,1);
 context.queue.setDecodeLimit(2);await tick();assert.equal(decoders.length,2);
 for(const resolve of decoders)resolve({width:256,height:256,close(){}});
 const results=await done;assert.equal(results.filter(r=>r.status==='fulfilled').length,2);await tick();assert.equal(context.queue.stats().activeDecodes,0);
 assert.equal(context.queue.stats().active,0);context.queue.setDecodeLimit(null);
});
test('gap pass-through performs catalogue selection once through the real regional wrapper',async()=>{
 let ensures=0,requests=0;
 const c={URL,Response,Headers,ArrayBuffer,Uint8Array,DataView,AbortController,setTimeout,clearTimeout,RidgeImageryCatalog:{async ensure(){ensures++;}},RidgeGapAtlas:{z:13,entries:{},coarse:{}},RidgeRegionalScheduler:require('../'+sourceRoot+'/regional-scheduler.js')};c.globalThis=c;
 vm.runInNewContext(fs.readFileSync(sourceRoot+'/gap-imagery.js','utf8'),c);
 vm.runInNewContext(fs.readFileSync(sourceRoot+'/regional-imagery.js','utf8'),c);
 const loader=c.RidgeRegionalImagery.create({fetcher:async()=>{requests++;return new Response(new Uint8Array([1,2,3]));}});
 const origin=sourceRoot==='public'?'https://basemaps.linz.govt.nz':'https://localhost/_ridge/linz';
 const response=await loader.fetch(origin+'/v1/tiles/aerial/WebMercatorQuad/15/10/20.webp',{ridgeOriginal:true});
 assert.equal(ensures,1);assert.equal(requests,1);assert.deepEqual([...new Uint8Array(await response.arrayBuffer())],[1,2,3]);loader.destroy();
});
test('diagnostics distinguish image delivery, decode admission and decode execution',()=>{
 const {attach}=require('../'+sourceRoot+'/bottleneck-diagnostics.js');let trace,now=1000;
 const env={setInterval:()=>1,clearInterval(){},RidgeTileTrace:{subscribe(fn){trace=fn;return()=>{};}}};
 const map={on(){},off(){},isMoving:()=>false};const diagnostics=attach(map,{environment:env,clock:()=>now});diagnostics.setEnabled(true);
 for(const [stage,ms] of [['image-delivery-start',10],['image-delivery-finished',30],['decode-queued',31],['decode-start',41],['decode-finished',61]])trace({id:1,stage,ms});
 const s=diagnostics.snapshot().stages;assert.equal(s.imageDelivery.meanMs,20);assert.equal(s.decodeAdmission.meanMs,10);assert.equal(s.decode.meanMs,20);diagnostics.destroy();
});

test('a saturated image queue defers priority rebuilds but uses the latest priorities on admission',async()=>{
 const started=[],network=new Map();let priorities={a:0,b:10};
 const c={AbortController,URL,Promise,HTMLImageElement:class{},self:{},On:{MAX_PARALLEL_IMAGE_REQUESTS:1,MAX_PARALLEL_IMAGE_REQUESTS_PER_FRAME:1},un:Error,fn:s=>{if(s.aborted)throw Error('aborted');},I(){},nt:e=>e,Bt:()=>false,kn:()=>true,Wt:()=>false,Rn:request=>{started.push(request.url);return new Promise(resolve=>network.set(request.url,()=>resolve({data:null})));}};
 c.globalThis=c;vm.runInNewContext(fs.readFileSync('scripts/maplibre-image-queue.js','utf8')+'\nglobalThis.queue=Un;',c);
 c.queue.setPolicy(request=>({priority:priorities[request.url]||0}));
 const controllers=[new AbortController(),new AbortController(),new AbortController()];
 const work=['first','a','b'].map((url,i)=>c.queue.getImage({url},controllers[i]));const all=Promise.allSettled(work);await tick();
 const before=c.queue.stats().queueRebuilds;priorities={a:10,b:0};for(let i=0;i<1000;i++)c.queue.pump();
 assert.equal(c.queue.stats().queueRebuilds,before);network.get('first')();await tick();assert.deepEqual(started,['first','b']);
 assert.ok(c.queue.stats().queueRebuilds>before);controllers[1].abort();network.get('b')();await all;assert.equal(c.queue.stats().active,0);
});
