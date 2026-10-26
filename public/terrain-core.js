/* Shared by the worker and the no-worker fallback. No learned/synthetic elevation. */
(function(root){
'use strict';
const abort=()=>Object.assign(new Error('Cancelled'),{name:'AbortError'});
const check=signal=>{if(signal.aborted)throw abort();};
class Lru{
 constructor(bytes){this.limit=bytes;this.bytes=0;this.items=new Map();}
 get(key){const v=this.items.get(key);if(v){this.items.delete(key);this.items.set(key,v);}return v;}
 set(key,tile){const old=this.items.get(key);if(old)this.bytes-=old.heights.byteLength;this.items.delete(key);this.items.set(key,tile);this.bytes+=tile.heights.byteLength;while(this.bytes>this.limit){const key=this.items.keys().next().value;this.bytes-=this.items.get(key).heights.byteLength;this.items.delete(key);}}
 clear(){this.items.clear();this.bytes=0;}
}
function interpolate(tile,p,G){
 const t=G.tile(p,tile.z),x=t.fx,y=t.fy,ix=Math.min(255,Math.floor(x)),iy=Math.min(255,Math.floor(y)),nx=Math.min(255,ix+1),ny=Math.min(255,iy+1),a=x-ix,b=y-iy;
 const values=[tile.heights[iy*256+ix],tile.heights[iy*256+nx],tile.heights[ny*256+ix],tile.heights[ny*256+nx]];
 if(values.some(v=>!Number.isFinite(v)))return null;
 return values[0]*(1-a)*(1-b)+values[1]*a*(1-b)+values[2]*(1-a)*b+values[3]*a*b;
}
async function decode(blob,provider,G){
 const bitmap=await root.createImageBitmap(blob,{colorSpaceConversion:'none',premultiplyAlpha:'none'});
 const canvas=typeof root.OffscreenCanvas!=='undefined'?new root.OffscreenCanvas(256,256):Object.assign(root.document.createElement('canvas'),{width:256,height:256});
 const ctx=canvas.getContext('2d',{willReadFrequently:true});ctx.drawImage(bitmap,0,0,256,256);bitmap.close();
 const rgba=ctx.getImageData(0,0,256,256).data,heights=new Float32Array(256*256),convert=provider==='linz'?G.terrainRGB:G.terrarium;
 for(let i=0,j=0;i<rgba.length;i+=4,j++)heights[j]=rgba[i+3]?convert(rgba[i],rgba[i+1],rgba[i+2]):NaN;
 return heights;
}
function createEngine({G,fetcher=root.fetch?.bind(root),decoder=(blob,p)=>decode(blob,p,G),cacheStorage=root.caches,maxBytes=8*1024*1024}={}){
 const cache=new Lru(maxBytes),inflight=new Map(),metrics={network:0,hits:0,diskHits:0,aborted:0},disk=cacheStorage?.open('ridgeline-elevation-v1').catch(()=>null);let writes=0;
 const url=(provider,z,x,y,key)=>provider==='linz'?`https://basemaps.linz.govt.nz/v1/tiles/elevation/WebMercatorQuad/${z}/${x}/${y}.png?pipeline=terrain-rgb&api=${encodeURIComponent(key)}`:`https://s3.amazonaws.com/elevation-tiles-prod/terrarium/${z}/${x}/${y}.png`;
 async function load(provider,z,x,y,key,signal){
  check(signal);const id=url(provider,z,x,y,key),existing=cache.get(id);if(existing){metrics.hits++;return existing;}
  const pending=inflight.get(id);if(pending&&!pending.signal.aborted)return pending.promise;
  const task=(async()=>{
   let response;const persistent=disk?await disk:null;check(signal);
   if(persistent){try{response=await persistent.match(id);if(response&&Date.now()-Number(response.headers.get('x-ridgeline-cached-at')||0)>Math.max(0,Math.min(86400,Number((response.headers.get('cache-control')||'').match(/max-age=(\d+)/i)?.[1]||86400)-(Number(response.headers.get('age'))||0)))*1000)response=null;if(response)metrics.diskHits++;}catch{}}
   check(signal);
   let blob;
   if(!response){const controller=new AbortController(),cancel=()=>controller.abort(),timeout=setTimeout(cancel,12000);signal.addEventListener('abort',cancel,{once:true});try{metrics.network++;response=await fetcher(id,{signal:controller.signal});if(!response.ok)throw Error('Elevation unavailable');blob=await response.blob();}finally{clearTimeout(timeout);signal.removeEventListener('abort',cancel);}}
   else blob=await response.blob();check(signal);
   const heights=await decoder(blob,provider);check(signal);const tile={heights,z,x,y,provider};cache.set(id,tile);
   // The optional disk cache is bounded and expires; a storage error never blocks a profile.
   if(persistent&&!response.headers.get('x-ridgeline-cached-at')&&!/no-store|no-cache/i.test(response.headers.get('cache-control')||'')){void (async()=>{try{const stored=new Response(blob,{headers:{'Content-Type':'image/png','Cache-Control':response.headers.get('cache-control')||'private, max-age=86400','Age':response.headers.get('age')||'0','x-ridgeline-cached-at':String(Date.now())}});await persistent.put(id,stored);if(++writes%8===1){const keys=await persistent.keys();await Promise.all(keys.slice(0,Math.max(0,keys.length-64)).map(k=>persistent.delete(k)));}}catch{}})();}
   return tile;
  })();inflight.set(id,{promise:task,signal});try{return await task;}finally{if(inflight.get(id)?.promise===task)inflight.delete(id);}
 }
 async function read(z,x,y,key,signal){
  try{return await load('linz',z,x,y,key,signal);}catch(e){if(signal.aborted){metrics.aborted++;throw abort();}const shift=Math.max(0,z-12);return load('mapzen',Math.min(z,12),x>>shift,y>>shift,key,signal);}
 }
 const inside=(p,b)=>b&&p[0]>=b[0]&&p[0]<=b[2]&&p[1]>=b[1]&&p[1]<=b[3];
 async function stage(options,signal,onProgress,phase){
  check(signal);const fine=phase==='detail',samples=G.samples(options.points,fine?1600:320,fine?5:40),budget=fine?64:16;
  if(fine&&options.bounds)samples.push(...G.focusSamples(options.points,options.bounds,400,2));
  samples.sort((a,b)=>a.d-b.d);const unique=samples.filter((s,i)=>!i||s.d-samples[i-1].d>.01);
  let z=fine?17:13;const keyFor=(p,z)=>{const t=G.tile(p,z);return z+'/'+t.x+'/'+t.y;};
  while(z>11&&new Set(unique.map(s=>keyFor(s.p,z))).size>budget)z--;
  let focusZoom=17;
  const finalKeys=()=>unique.map(s=>keyFor(s.p,fine&&inside(s.p,options.bounds)?Math.max(z,focusZoom):z));
  while(focusZoom>z&&new Set(finalKeys()).size>budget)focusZoom--;
  let sampleKeys=finalKeys();
  while(z>0&&new Set(sampleKeys).size>budget){z--;focusZoom=z;sampleKeys=finalKeys();}
  const keys=[...new Set(sampleKeys)],data=new Map();let next=0;
  await Promise.all(Array.from({length:3},async()=>{while(next<keys.length){check(signal);const id=keys[next++],[tz,x,y]=id.split('/').map(Number);try{data.set(id,await read(tz,x,y,options.key,signal));}catch(e){check(signal);data.set(id,null);}}}));check(signal);
  const profile=unique.map((sample,i)=>{const tile=data.get(sampleKeys[i]);return{...sample,h:tile?interpolate(tile,sample.p,G):null,provider:tile?.provider||null,z:tile?.z||null};});
  onProgress({profile,phase,zoom:z,focused:!!(fine&&options.bounds),metrics:{...metrics,cacheBytes:cache.bytes,requestedTileCount:keys.length,tileBudget:budget,focusZoom}});
 }
 async function run(options,signal,onProgress){
  if(options.points.length<2)return;
  if(!options.detailOnly)await stage(options,signal,onProgress,'preview');
  if(!options.previewOnly)await stage(options,signal,onProgress,'detail');
 }
 return{run,read,metrics,cache,clear:()=>cache.clear()};
}
function createClient(G){
 let engine,worker,sequence=0,pending,controller;const canWorker=typeof root.Worker!=='undefined'&&typeof root.OffscreenCanvas!=='undefined';
 const cancel=()=>{const wasPending=!!pending;sequence++;controller?.abort();controller=null;if(wasPending)worker?.postMessage({type:'cancel'});pending?.resolve();pending=null;return wasPending;};
 const runLocal=async(job)=>{engine??=createEngine({G});controller=new AbortController();try{await engine.run(job.options,controller.signal,data=>{if(job.id===sequence)job.onProgress(data);});}catch(e){if(e.name!=='AbortError'&&job.id===sequence)job.onError(e);}finally{if(job.id===sequence){job.resolve();pending=null;}}};
 const start=options=>new Promise(resolve=>{
  cancel();const job={id:sequence,options:options.data,onProgress:options.onProgress,onError:options.onError||(()=>{}),resolve};pending=job;
  if(canWorker&&!worker&&!engine){try{worker=new root.Worker('/terrain-worker.js?v=5');worker.onmessage=({data})=>{if(!pending||data.id!==sequence)return;if(data.type==='progress')pending.onProgress(data.result);else if(data.type==='done'){pending.resolve();pending=null;}else if(data.type==='error'){pending.onError(Error(data.message));pending.resolve();pending=null;}};worker.onerror=()=>{const latest=pending;worker.terminate();worker=null;if(latest)void runLocal(latest);};}catch{engine=createEngine({G});}}
  if(worker)worker.postMessage({type:'profile',id:job.id,options:job.options});else void runLocal(job);
 });
 return{start,cancel,destroy(){cancel();worker?.terminate();worker=null;engine?.clear();}};
}
const api={Lru,interpolate,createEngine,createClient};if(typeof module!=='undefined')module.exports=api;else root.RidgeTerrain=api;
})(globalThis);
