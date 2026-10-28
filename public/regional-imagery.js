/* Reviewed survey gaps only. Unaffected LINZ responses pass through unchanged;
   complete gap tiles use one Esri request; only boundary tiles use two images. */
(function(root){
'use strict';
function inside(rings,x,y){let hit=false;for(const ring of rings)for(let i=0,j=ring.length-1;i<ring.length;j=i++){
 const a=ring[i],b=ring[j];if((a[1]>y)!==(b[1]>y)&&x<(b[0]-a[0])*(y-a[1])/(b[1]-a[1])+a[0])hit=!hit;
 }return hit;
}
function relation(rings,box){
 const [x0,y0,x1,y1]=box;
 for(const ring of rings)for(let i=1;i<ring.length;i++){
  const a=ring[i-1],b=ring[i];if(Math.max(a[0],b[0])>=x0&&Math.min(a[0],b[0])<=x1&&Math.max(a[1],b[1])>=y0&&Math.min(a[1],b[1])<=y1)return 0;
 }
 return inside(rings,(x0+x1)/2,(y0+y1)/2)?1:-1;
}
function mode(z,x,y,meta=root.RidgeWestCoastFootprint){
 if(!meta)return'linz';const size=256*2**(meta.z-z),px=x*size-meta.x*256,py=y*size-meta.y*256;
 if(px+size<0||py+size<0||px>meta.width||py>meta.height)return'linz';
 // Routing is conservative. The official vector or legacy raster mask
 // determines the actual source of every boundary pixel.
 const b=[px-2,py-2,px+size+2,py+size+2];
 if(meta.seamRings?.length&&relation(meta.seamRings,b)!==-1)return'boundary';
 if(relation(meta.outer,b)===-1)return'linz';if(relation(meta.vector?meta.outer:meta.inner,b)===1)return'esri';return'boundary';
}
function webpBytes(data){
 const b=new Uint8Array(data);
 // Static hosting may serve WebP as application/octet-stream. Validate the
 // actual container instead of rejecting valid imagery because of its MIME.
 return b.length>=20&&b[0]===82&&b[1]===73&&b[2]===70&&b[3]===70&&
  b[8]===87&&b[9]===69&&b[10]===66&&b[11]===80&&
  new DataView(data).getUint32(4,true)+8===b.length&&
  b[12]===86&&b[13]===80&&b[14]===56&&[32,76,88].includes(b[15]);
}
function create({fetcher=(...a)=>root.fetch(...a),meta=root.RidgeWestCoastFootprint,overviews=root.RidgeAlpineOverviews,south=root.RidgeSouthOverviews,reference=root.RidgeReferenceOverviews,composer,workerFactory=()=>new root.Worker('/regional-imagery-worker.js?v=8'),concurrency=2}={}){
 const inputs=root.RidgeResponses?.create({fetcher}),inputFetch=inputs?.fetch||fetcher;
 const Scheduler=root.RidgeRegionalScheduler||(typeof require==='function'?require('./regional-scheduler.js'):null);
 const scheduler=Scheduler.create({concurrency});let worker=null,restartAt=0,workerFailures=0,failureWindow=0,fallbackBusy=false,nextId=0,dead=false,mask=null;const jobs=new Map(),overviewCooldown=new Map(),metrics={untouchedLINZ:0,regionalEsri:0,boundaryComposites:0,overzoomCrops:0,esriColourCompensations:0,precomputedOverviews:0,southOverviews:0,southRangeBytes:0,southFailures:0,southStatic:0,overviewGenericContentType:0,overviewFallbacks:0,workerJobs:0,mainThreadJobs:0,compositionFailures:0,referenceOverviews:0};
 function failWorker(error){const now=Date.now();if(now-failureWindow>60000){workerFailures=0;failureWindow=now;}workerFailures++;restartAt=workerFailures>=3?failureWindow+60000:now+Math.min(30000,1000*2**workerFailures);worker?.terminate();worker=null;for(const job of jobs.values()){clearTimeout(job.timer);job.cleanup?.();job.reject(error);}jobs.clear();}
 function ensureWorker(){
  if(worker||dead||Date.now()<restartAt)return worker;
  if(typeof root.Worker!=='function')return null;
  try{worker=workerFactory();worker.onmessage=({data})=>{const job=jobs.get(data.id);if(!job)return;jobs.delete(data.id);clearTimeout(job.timer);job.cleanup?.();data.cancelled?job.reject(Scheduler.abort()):data.error?job.reject(Error(data.error)):job.resolve(data.data);};
   worker.onerror=()=>failWorker(Error('Imagery worker unavailable'));
  }catch{failWorker(Error('Imagery worker unavailable'));}return worker;
 }
 async function compose(data,signal,original=false){
  if(dead||signal?.aborted)throw Object.assign(Error('Cancelled'),{name:'AbortError'});
  if(composer)return composer(data,signal);
  const w=ensureWorker();
  if(w){metrics.workerJobs++;return new Promise((resolve,reject)=>{const id=++nextId,timer=setTimeout(()=>failWorker(Error('Imagery worker timeout')),12000);const cancel=()=>{try{w.postMessage({cancel:id});}catch(error){failWorker(error);}},cleanup=()=>signal?.removeEventListener('abort',cancel);jobs.set(id,{resolve,reject,timer,cleanup});signal?.addEventListener('abort',cancel,{once:true});try{w.postMessage({...data,id},[data.linz,data.esri,...(data.fine||[]).map(v=>v.data)].filter(buffer=>buffer instanceof ArrayBuffer));if(signal?.aborted)cancel();}catch(error){failWorker(error);}});}
  if(typeof root.OffscreenCanvas!=='function'||!root.RidgeRegionalBlend)throw Error('Regional composition unavailable');
  if(fallbackBusy)throw Object.assign(Error('Regional fallback busy'),{status:503});fallbackBusy=true;metrics.mainThreadJobs++;if(original)root.dispatchEvent?.(new Event('ridge-imagery-main-thread'));if(data.linz&&!data.overlay&&!data.meta?.rings)mask=fetcher(data.meta?.maskURL||'/assets/west-coast-mask.png?v=1',{}).then(r=>{if(!r.ok)throw Error('Mask unavailable');return r.blob();}).then(b=>root.createImageBitmap(b)).catch(error=>{mask=null;throw error;});
  try{return await root.RidgeRegionalBlend.composite({...data,mask:data.linz&&!data.overlay?mask:Promise.resolve(null),checkCancelled:()=>{if(dead||signal?.aborted)throw Scheduler.abort();}});}finally{mask?.then(m=>m.close()).catch(()=>{});mask=null;fallbackBusy=false;}
 }
 const schedule=(run,signal,options)=>scheduler.schedule(run,signal,options);
 async function regionalFetch(url,options={},catalogReady=false){
  if(dead||options.signal?.aborted)throw Object.assign(Error('Cancelled'),{name:'AbortError'});
  const u=new URL(url,'https://viewer.invalid'),q=u.hostname==='basemaps.linz.govt.nz'&&u.pathname.match(/^\/v1\/tiles\/aerial\/WebMercatorQuad\/(\d+)\/(\d+)\/(\d+)\.webp$/);
  if(!q)return fetcher(url,options);
  if(!catalogReady&&root.RidgeImageryCatalog)await root.RidgeImageryCatalog.ensure(options.ridgeOriginal?{z:+q[1],x:+q[2],y:+q[3]}:undefined);meta??=root.RidgeWestCoastFootprint;south??=root.RidgeSouthOverviews;
  const compensate=u.searchParams.get('ridge-grade')==='snow-v3',useReference=reference&&u.searchParams.get('ridge-reference')===reference.version;u.searchParams.delete('ridge-reference');
  // This flag belongs to our byte-cache key, never to the upstream LINZ API.
  u.searchParams.delete('ridge-grade');u.searchParams.delete('ridge-gap');url=u.toString();
  const [z,x,y]=q.slice(1).map(Number),baked=z<=12?overviews?.tiles?.[z+'/'+x+'/'+y]:null;
  const entry=z<=12?south?.tiles?.[z+'/'+x+'/'+y]:null;
  const replacement=useReference&&z<=14?reference.tiles?.[z+'/'+x+'/'+y]:null;
  if(replacement){
   const path=typeof replacement==='string'?replacement:replacement[compensate?'natural':'raw'];
   if(!/^\/assets\/cook-reference-v1\/[a-f0-9]{24}\.webp$/.test(path))throw Error('Reference imagery path invalid');
   const response=await fetcher(path,options);if(!response.ok){await response.body?.cancel();throw Object.assign(Error('Reference imagery unavailable'),{status:503});}
   const data=await response.arrayBuffer();if(dead||options.signal?.aborted)throw Object.assign(Error('Cancelled'),{name:'AbortError'});
   if(!webpBytes(data))throw Error('Reference imagery invalid');metrics.referenceOverviews++;
   return new Response(data,{headers:{'Content-Type':'image/webp','Cache-Control':'private, max-age=21600'}});
  }

  if(entry){
   const row=Array.isArray(entry)||entry.url?entry:entry[compensate?'natural':'raw'],dataRange=row.range||row;
   const file=Array.isArray(dataRange)?south.packs[dataRange[0]]:dataRange.file,offset=Array.isArray(dataRange)?dataRange[1]:dataRange.offset,bytes=Array.isArray(dataRange)?dataRange[2]:dataRange.bytes;
   if(!/^[a-f0-9]{64}$/.test(file)||!Number.isSafeInteger(offset)||offset<0||!Number.isSafeInteger(bytes)||bytes<20)throw Error('Saved imagery range invalid');
   if(row.url){
    if(!/^\/assets\/south-overviews-v1\/[a-f0-9]{24}\.webp$/.test(row.url))throw Error('Saved overview path invalid');
    try{
     const response=await fetcher(row.url,options);
     if(!response.ok){await response.body?.cancel();throw Error('Coarse overview unavailable');}
     const data=await response.arrayBuffer();if(dead||options.signal?.aborted)throw Object.assign(Error('Cancelled'),{name:'AbortError'});
     if(data.byteLength!==bytes||!webpBytes(data))throw Error('Coarse overview invalid');
     metrics.southStatic++;metrics.southOverviews++;metrics.precomputedOverviews++;
     return new Response(data,{headers:{'Content-Type':'image/webp','Cache-Control':'private, max-age=21600'}});
    }catch(error){if(dead||options.signal?.aborted||error.name==='AbortError')throw error;}
    // Static and R2 fallback contain exactly the same photo bytes.
   }
   const headers=new Headers(options.headers);headers.set('Range',`bytes=${offset}-${offset+bytes-1}`);
   const path='/api/lidar/files/'+file+'.bin?tile='+z+'-'+x+'-'+y+'&mode='+(compensate?'natural':'raw');
   try{
    const response=await fetcher(path,{...options,headers});
    if(!response.ok){metrics.southFailures++;return response;}
    const expected=`bytes ${offset}-${offset+bytes-1}/`,range=response.headers.get('content-range')||'';
    if(response.status!==206||!range.startsWith(expected)){await response.body?.cancel();throw Error('Saved imagery range unavailable');}
    const data=await response.arrayBuffer();if(dead||options.signal?.aborted)throw Object.assign(Error('Cancelled'),{name:'AbortError'});
    if(data.byteLength!==bytes||!webpBytes(data))throw Error('Saved imagery invalid');
    metrics.southOverviews++;metrics.precomputedOverviews++;metrics.southRangeBytes+=bytes;
    return new Response(data,{headers:{'Content-Type':'image/webp','Cache-Control':'private, max-age=21600'}});
   }catch(error){
    if(dead||options.signal?.aborted||error.name==='AbortError')throw error;
    metrics.southFailures++;
    // Let the existing native hierarchy retain its ready parent. Never swap
    // a failed saved tile silently back to the differently coloured 10 m mosaic.
    throw Object.assign(Error('Saved imagery temporarily unavailable'),{status:503});
   }
  }
  const asset=baked?.[compensate?'natural':'raw'];
  if(asset&&(overviewCooldown.get(asset)||0)<=Date.now()){
   try{
    const response=await fetcher(asset,options);
    if(!response.ok){await response.body?.cancel();throw Error('Overview unavailable');}
    const data=await response.arrayBuffer();if(dead||options.signal?.aborted)throw Object.assign(Error('Cancelled'),{name:'AbortError'});
    if(!webpBytes(data))throw Error('Overview image invalid');
    if(!response.headers.get('content-type')?.startsWith('image/'))metrics.overviewGenericContentType++;
    metrics.precomputedOverviews++;
    // Content-addressed, immutable photo snapshots. The existing shared byte
    // cache still enforces its normal memory limit and six-hour TTL ceiling.
    return new Response(data,{headers:{'Content-Type':'image/webp','Cache-Control':'private, max-age=21600'}});
   }catch(error){
    if(dead||options.signal?.aborted||error.name==='AbortError')throw error;
    metrics.overviewFallbacks++;overviewCooldown.set(asset,Date.now()+30000);
   }
  }
  if(!meta||root.RidgeGapAtlas&&root.RidgeGapImagery)return fetcher(url,options);
  const selection=mode(z,x,y,meta);
  if(selection==='linz'){metrics.untouchedLINZ++;return fetcher(url,options);}
  const esriZ=Math.min(z,18),factor=2**(z-esriZ),esri=`https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/${esriZ}/${Math.floor(y/factor)}/${Math.floor(x/factor)}`;
  if(selection==='esri'&&z===esriZ&&!compensate){metrics.regionalEsri++;return fetcher(esri,options);}
  const pair=await Promise.allSettled([selection==='boundary'?inputFetch(url,options):Promise.resolve(null),inputFetch(esri,options)]);
  const failed=pair.find(r=>r.status==='rejected'),a=pair[0].value,b=pair[1].value;
  if(failed||a&&!a.ok||b&&!b.ok){await Promise.allSettled(pair.filter(r=>r.status==='fulfilled'&&r.value).map(r=>r.value.body?.cancel()));if(failed)throw failed.reason;return a&&!a.ok?a:b;}

  // Work is queued only after downloads, so coarse/focus fetches do not wait
  // for a distant boundary tile's decode/encode job.
  const linz=a?await a.arrayBuffer():null,satellite=await b.arrayBuffer();
  try{
   root.RidgeTileTrace?.emit('transform-queued','linz',{z,x,y},{bytes:(linz?.byteLength||0)+satellite.byteLength});
   const data=await schedule(async()=>{root.RidgeTileTrace?.emit('transform-start','linz',{z,x,y});try{return await compose({linz,esri:satellite,z,x,y,esriZ,compensate,meta:{z:meta.z,x:meta.x,y:meta.y,width:meta.width,height:meta.height}},options.signal,options.ridgeOriginal===true);}finally{root.RidgeTileTrace?.emit('transform-finished','linz',{z,x,y});}},options.signal,{bytes:(linz?.byteLength||0)+satellite.byteLength,priority:options.ridgePriority||(()=>options.priority||'high'),rank:options.ridgeRank});
   if(options.signal?.aborted)throw Object.assign(Error('Cancelled'),{name:'AbortError'});
   if(compensate)metrics.esriColourCompensations++;if(selection==='boundary')metrics.boundaryComposites++;else metrics.regionalEsri++;if(esriZ<z)metrics.overzoomCrops++;let ttl=3600;
   for(const response of [a,b].filter(Boolean)){const cc=response.headers.get('cache-control')||'',age=Number(response.headers.get('age'))||0,max=cc.match(/(?:^|,)\s*max-age=(\d+)/i);if(/no-store|no-cache/i.test(cc))ttl=0;else if(max)ttl=Math.min(ttl,Math.max(0,Number(max[1])-age));}
   return new Response(data,{headers:{'Content-Type':'image/png','Cache-Control':ttl?'private, max-age='+ttl:'no-store'}});
  }catch(error){if(error.name!=='AbortError')metrics.compositionFailures++;throw error;}
 }
 function destroy(){dead=true;inputs?.destroy();scheduler.destroy();overviewCooldown.clear();worker?.terminate();worker=null;for(const job of jobs.values()){clearTimeout(job.timer);job.cleanup?.();job.reject(Object.assign(Error('Cancelled'),{name:'AbortError'}));}jobs.clear();mask?.then(m=>m.close()).catch(()=>{});}
 const expanded=root.RidgeGapImagery?.create({fetcher:inputFetch,base:regionalFetch,compose:(data,signal,options)=>{const bytes=(data.linz?.byteLength||0)+data.esri.byteLength+(data.fine||[]).reduce((n,v)=>n+v.data.byteLength,0);root.RidgeTileTrace?.emit('transform-queued','linz',data,{bytes});return schedule(async()=>{root.RidgeTileTrace?.emit('transform-start','linz',data);try{return await compose(data,signal);}catch(error){if(error.name!=='AbortError')metrics.compositionFailures++;throw error;}finally{root.RidgeTileTrace?.emit('transform-finished','linz',data);}},signal,{bytes,priority:options.ridgePriority||(()=>options.priority||'high'),rank:options.ridgeRank});}});
 return{fetch:expanded?.fetch||regionalFetch,destroy(){expanded?.destroy();destroy();},stats:()=>({...metrics,...inputs?.stats(),...expanded?.stats(),...scheduler.stats(),workerFailures,workerRestartAfterMs:Math.max(0,restartAt-Date.now()),mainThreadFallback:fallbackBusy})};
}
const api={mode,inside,relation,webpBytes,create};if(typeof module!=='undefined')module.exports=api;else root.RidgeRegionalImagery=api;
})(globalThis);
