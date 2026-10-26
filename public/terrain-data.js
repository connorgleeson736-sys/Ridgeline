/* Original raster elevations -> edge-aligned Three heightmaps. Shared worker/test code. */
(function(root){
'use strict';
const SIZE=256,MAX_ZOOM=18,NZ=[165,-49,180,-33];
const abort=()=>Object.assign(new Error('Terrain request cancelled'),{name:'AbortError'});
const check=signal=>{if(signal?.aborted)throw abort();};
const clamp=(v,a,b)=>Math.min(b,Math.max(a,v));
const longitude=(x,z)=>x/2**z*360-180;
const latitude=(y,z)=>Math.atan(Math.sinh(Math.PI*(1-2*y/2**z)))*180/Math.PI;
function intersectsNZ(x,y,z){return longitude(x+1,z)>=NZ[0]&&longitude(x,z)<=NZ[2]&&latitude(y,z)>=NZ[1]&&latitude(y+1,z)<=NZ[3];}
function bilinear(h,x,y){
 x=clamp(x,0,255);y=clamp(y,0,255);const ix=Math.floor(x),iy=Math.floor(y),nx=Math.min(255,ix+1),ny=Math.min(255,iy+1),a=x-ix,b=y-iy;
 return h[iy*256+ix]*(1-a)*(1-b)+h[iy*256+nx]*a*(1-b)+h[ny*256+ix]*(1-a)*b+h[ny*256+nx]*a*b;
}
function sampleRecord(record,gx,gy,z){const scale=2**(z-record.z);return bilinear(record.heights,(gx+.5)/scale-.5-record.x*256,(gy+.5)/scale-.5-record.y*256);}
function decodeRGBA(rgba){
 if(rgba.length!==256*256*4)throw Error('Unexpected elevation tile dimensions');
 const h=new Float32Array(256*256);for(let i=0;i<h.length;i++){const j=i*4;h[i]=rgba[j+3]?-10000+(rgba[j]*65536+rgba[j+1]*256+rgba[j+2])*.1:NaN;}return h;
}
// Raster samples are pixel centres; Three heightmap endpoints are tile edges.
// Four real neighbouring samples define every output node, including shared edges.
function assemble(records,x,y,z){
 const n=2**z,total=n*256,out=new Float32Array(257*257),normalX=v=>((v%n)+n)%n;
 const value=(gx,gy)=>{gy=clamp(gy,0,total-1);const tx=normalX(Math.floor(gx/256)),ty=Math.floor(gy/256),record=records.get(tx+'/'+ty);if(!record)throw Error('Missing elevation neighbour');return sampleRecord(record,((gx%total)+total)%total,gy,z);};
 for(let j=0;j<=256;j++){
  const gy=y*256+j-1;
  for(let i=0;i<=256;i++){
   const gx=x*256+i-1,h=(value(gx,gy)+value(gx+1,gy)+value(gx,gy+1)+value(gx+1,gy+1))*.25;
   if(!Number.isFinite(h))throw Error('Non-finite terrain elevation');
   out[j*257+i]=h;
  }
 }
 return out;
}
function createEngine({key,fetcher=root.fetch.bind(root),decoder,cacheStorage=root.caches,maxBytes=24*1024*1024,concurrency=6}={}){
 const cache=new Map(),inflight=new Map(),queue=[],controllers=new Set();let bytes=0,active=0,disposed=false,writes=0;
 const metrics={network:0,hits:0,diskHits:0,failed:0,coarse:0};
 const disk=cacheStorage?.open('ridgeline-elevation-v1').catch(()=>null);
 const run=()=>{while(active<concurrency&&queue.length){const job=queue.shift();if(job.signal.aborted){job.reject(abort());continue;}active++;job.fn().then(job.resolve,job.reject).finally(()=>{active--;run();});}};
 const schedule=(fn,signal)=>new Promise((resolve,reject)=>{queue.push({fn,signal,resolve,reject});run();});
 const remember=(id,record)=>{if(disposed)return;const old=cache.get(id);if(old)bytes-=old.heights.byteLength;cache.delete(id);cache.set(id,record);bytes+=record.heights.byteLength;while(bytes>maxBytes&&cache.size>1){const k=cache.keys().next().value;bytes-=cache.get(k).heights.byteLength;cache.delete(k);}};
 async function imageDecode(blob){
  const bitmap=await root.createImageBitmap(blob,{colorSpaceConversion:'none',premultiplyAlpha:'none'});
  try{if(bitmap.width!==256||bitmap.height!==256)throw Error('Unexpected LINZ tile dimensions');
   const canvas=typeof root.OffscreenCanvas!=='undefined'?new root.OffscreenCanvas(256,256):Object.assign(root.document.createElement('canvas'),{width:256,height:256});
   const ctx=canvas.getContext('2d',{willReadFrequently:true});ctx.drawImage(bitmap,0,0);return decodeRGBA(ctx.getImageData(0,0,256,256).data);
  }finally{bitmap.close();}
 }
 async function raw(z,x,y,signal){
  check(signal);if(disposed)throw abort();const id=z+'/'+x+'/'+y,found=cache.get(id);
  if(found){metrics.hits++;cache.delete(id);cache.set(id,found);return found;}
  let task=inflight.get(id);
  if(!task||task.controller.signal.aborted){
   const controller=new AbortController();controllers.add(controller);task={controller,users:0};
   task.promise=(async()=>{
    const url=`https://basemaps.linz.govt.nz/v1/tiles/elevation/WebMercatorQuad/${id}.png?pipeline=terrain-rgb&api=${encodeURIComponent(key)}`;
    const persistent=disk?await disk:null;check(controller.signal);let response,fromDisk=false;
    try{response=await persistent?.match(url);if(response&&Date.now()-Number(response.headers.get('x-ridgeline-cached-at')||0)<Math.max(0,Math.min(86400,Number((response.headers.get('cache-control')||'').match(/max-age=(\d+)/i)?.[1]||86400)-(Number(response.headers.get('age'))||0)))*1000){metrics.diskHits++;fromDisk=true;}else response=null;}catch{}
    let blob,heights,policy='private, max-age=86400',sourceAge='0';
    try{
     if(response)blob=await response.blob();
     else blob=await schedule(async()=>{check(controller.signal);const timeout=setTimeout(()=>controller.abort(),18000);try{metrics.network++;const r=await fetcher(url,{signal:controller.signal});if(!r.ok)throw Object.assign(Error('LINZ elevation HTTP '+r.status),{status:r.status});policy=r.headers?.get('cache-control')||policy;sourceAge=r.headers?.get('age')||'0';return await r.blob();}finally{clearTimeout(timeout);}},controller.signal);
     check(controller.signal);heights=await(decoder||imageDecode)(blob);check(controller.signal);
    }catch(e){
     check(controller.signal);if(e.status!==404||z===0)throw e;
     metrics.coarse++;const parent=await raw(z-1,Math.floor(x/2),Math.floor(y/2),controller.signal);return parent;
    }
    if(heights.some(v=>!Number.isFinite(v))){
     if(z===0)throw Error('LINZ root elevation contains missing measurements');
     const parent=await raw(z-1,Math.floor(x/2),Math.floor(y/2),controller.signal);metrics.coarse++;
     for(let j=0;j<256;j++)for(let i=0;i<256;i++)if(!Number.isFinite(heights[j*256+i]))heights[j*256+i]=sampleRecord(parent,x*256+i,y*256+j,z);
    }
    const record={z,x,y,heights};remember(id,record);
    if(persistent&&!fromDisk&&!disposed&&!/no-store|no-cache/i.test(policy)){void(async()=>{try{await persistent.put(url,new Response(blob,{headers:{'Content-Type':'image/png','Cache-Control':policy,'Age':sourceAge,'x-ridgeline-cached-at':String(Date.now())}}));if(++writes%8===1){const keys=await persistent.keys();await Promise.all(keys.slice(0,Math.max(0,keys.length-64)).map(k=>persistent.delete(k)));}}catch{}})();}
    return record;
   })().finally(()=>{controllers.delete(controller);if(inflight.get(id)===task)inflight.delete(id);});
   inflight.set(id,task);
  }
  task.users++;
  return new Promise((resolve,reject)=>{
   let done=false;const finish=(fn,v)=>{if(done)return;done=true;signal.removeEventListener('abort',cancel);task.users--;if(!task.users)task.controller.abort();fn(v);};
   const cancel=()=>finish(reject,abort());signal.addEventListener('abort',cancel,{once:true});
   task.promise.then(v=>finish(resolve,v),e=>finish(reject,e));if(signal.aborted)cancel();
  });
 }
 async function tile(x,y,z,signal){
  if(!Number.isInteger(z)||z<0||z>MAX_ZOOM||x<0||y<0||x>=2**z||y>=2**z)throw Error('Invalid terrain tile');
  check(signal);if(disposed)throw abort();if(!intersectsNZ(x,y,z))return new Float32Array(257*257);
  const n=2**z,records=new Map(),ids=new Map();
  for(let dy=-1;dy<=1;dy++)for(let dx=-1;dx<=1;dx++){const tx=((x+dx)%n+n)%n,ty=clamp(y+dy,0,n-1);ids.set(tx+'/'+ty,[tx,ty]);}
  try{await Promise.all([...ids].map(async([id,[tx,ty]])=>records.set(id,await raw(z,tx,ty,signal))));check(signal);return assemble(records,x,y,z);}
  catch(e){if(e.name!=='AbortError')metrics.failed++;throw e;}
 }
 return{tile,configure(options={}){if(Number.isFinite(options.maxBytes))maxBytes=Math.max(1048576,options.maxBytes);if(Number.isFinite(options.concurrency))concurrency=Math.max(1,Math.min(16,Math.round(options.concurrency)));while(bytes>maxBytes&&cache.size){const k=cache.keys().next().value;bytes-=cache.get(k).heights.byteLength;cache.delete(k);}run();},stats:()=>({...metrics,cacheBytes:bytes,active,queued:queue.length}),destroy(){disposed=true;controllers.forEach(c=>c.abort());queue.splice(0).forEach(j=>j.reject(abort()));cache.clear();bytes=0;}};
}
const api={MAX_ZOOM,NZ,longitude,latitude,intersectsNZ,bilinear,sampleRecord,decodeRGBA,assemble,createEngine};
if(typeof module!=='undefined')module.exports=api;else root.RidgeTerrainData=api;
})(globalThis);
