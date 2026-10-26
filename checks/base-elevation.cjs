const assert=require('node:assert/strict'),fs=require('fs'),vm=require('vm'),path=require('node:path'),G=require('../public/geo.js'),T=require('../public/terrain-core.js');const publicRoot=path.resolve(__dirname,'../public');
const response=()=>({ok:true,headers:{get:()=>null},blob:async()=>({})});
const heights=value=>new Float32Array(256*256).fill(value);
(async()=>{
 // Exact waypoints and clipped viewport samples survive small budgets.
 const route=[[170.14,-43.595],[170.14004,-43.59497],[170.148,-43.59]],sampled=G.samples(route,5,100);
 assert.ok(sampled.some(s=>s.p===route[1]));const focused=G.focusSamples([[170,-44],[171,-44]],[170.4,-44.1,170.6,-43.9],400,2);assert.ok(focused.length>0&&focused.every(s=>s.p[0]>=170.4-1e-9&&s.p[0]<=170.6+1e-9));
 const spike=Array.from({length:10000},(_,i)=>({d:i,h:i===5155?999:i===5110?null:0}));const chart=G.chartSamples(spike);assert.ok(chart.some(p=>p.h===999));assert.ok(chart.some(p=>p.h===null));assert.ok(chart.length<spike.length/2);
 // Repeat profiles hit decoded cache; the engine holds at most three network requests at once.
 let calls=0,active=0,peak=0;const engine=T.createEngine({G,cacheStorage:null,fetcher:async()=>{calls++;active++;peak=Math.max(peak,active);await new Promise(r=>setTimeout(r,2));active--;return response();},decoder:async()=>heights(1234.5)});
 const options={points:[[170.14,-43.595],[170.145,-43.59]],key:'test',detailOnly:true};let result;await engine.run(options,new AbortController().signal,r=>result=r);assert.ok(result.profile.every(s=>s.h!==null&&Math.abs(s.h-1234.5)<1e-6));const coldCalls=calls;await engine.run(options,new AbortController().signal,()=>{});assert.equal(calls,coldCalls);assert.ok(peak<=3);assert.ok(engine.cache.bytes<=8*1024*1024);
 // Distinct high-resolution failures share one canonical coarse parent, not duplicate decodes.
 let fallbackCalls=0;const fallback=T.createEngine({G,cacheStorage:null,fetcher:async url=>{if(url.includes('linz.govt.nz'))throw Error('Unavailable');fallbackCalls++;return response();},decoder:async()=>heights(800)});const signal=new AbortController().signal;
 const tiles=await Promise.all([fallback.read(17,100000,70000,'test',signal),fallback.read(17,100001,70000,'test',signal)]);assert.equal(fallbackCalls,1);assert.equal(tiles[0].z,12);
 // Edits abort obsolete requests and must not start fallback requests after cancellation.
 let starts=0,aborts=0;const cancellable=T.createEngine({G,cacheStorage:null,fetcher:async(url,{signal})=>{starts++;return new Promise((resolve,reject)=>{signal.addEventListener('abort',()=>{aborts++;reject(Object.assign(Error('abort'),{name:'AbortError'}));},{once:true});});},decoder:async()=>heights(1)});const controller=new AbortController();const obsolete=cancellable.run(options,controller.signal,()=>{throw Error('Stale results published');});await new Promise(r=>setTimeout(r,5));controller.abort();await assert.rejects(obsolete,{name:'AbortError'});assert.equal(aborts,starts);assert.ok(starts<=3);
 // LRU byte budget, including replacement of existing entries.
 const lru=new T.Lru(8);lru.set('a',{heights:new Float32Array(1)});lru.set('b',{heights:new Float32Array(1)});lru.get('a');lru.set('c',{heights:new Float32Array(1)});assert.equal(lru.get('b'),undefined);assert.equal(lru.bytes,8);
 // Execute the actual worker entry point, including imports and progress messages.
 const rgba=new Uint8ClampedArray(256*256*4);for(let i=0;i<rgba.length;i+=4)rgba.set([1,182,217,255],i);const messages=[];
 class Canvas{getContext(){return{drawImage(){},getImageData(){return{data:rgba};}};}}
 const worker=vm.createContext({self:null,AbortController,Float32Array,OffscreenCanvas:Canvas,createImageBitmap:async()=>({close(){}}),fetch:async()=>response(),setTimeout,clearTimeout,Response,Date,postMessage:m=>messages.push(m)});worker.self=worker;worker.importScripts=(...paths)=>paths.forEach(p=>vm.runInContext(fs.readFileSync(publicRoot+'/'+p.slice(1).split('?')[0],'utf8'),worker));vm.runInContext(fs.readFileSync(publicRoot+'/terrain-worker.js','utf8'),worker);await worker.onmessage({data:{type:'profile',id:7,options}});assert.ok(messages.some(m=>m.type==='progress'&&m.result.phase==='detail'));assert.ok(messages.some(m=>m.type==='done'&&m.id===7));
 console.log(JSON.stringify({passed:true,coldDetailTileRequests:coldCalls,repeatedDetailTileRequests:calls-coldCalls,peakProfileConcurrency:peak,cancelledRequests:aborts,duplicateFallbackParentRequests:fallbackCalls,decodedCacheBytes:engine.cache.bytes,chartVerticesBefore:spike.length,chartVerticesAfter:chart.length,workerEntryPoint:'passed'}));
})().catch(e=>{console.error(e);process.exitCode=1;});
