/* Compressed imagery and DEM tile reuse for Original. Never retain decoded images or GPU resources here. */
(function(root){
'use strict';
const aborted=()=>Object.assign(new Error('Tile request cancelled'),{name:'AbortError'});
// Estimated off-screen/source/byte-cache allocations, not total browser memory.
// Preserve the device's default proportions; the slider scales all six pools.
function budgetPlan(totalMiB,{memory=8,saveData=false}={}){
 const compact=memory<4,base={imagery:saveData?32:compact?64:128,elevation:saveData?12:compact?24:48,views:compact?48:96,spare:saveData?8:16,imageryBytes:saveData?12:compact?24:48,elevationBytes:saveData?8:compact?12:16},sum=Object.values(base).reduce((a,b)=>a+b,0);
 const total=Number.isFinite(totalMiB)?Math.max(96,Math.min(3000,Math.round(totalMiB/4)*4)):sum;
 const plan=Object.fromEntries(Object.entries(base).map(([key,value])=>[key,Math.floor(value*total/sum)]));
 plan.imagery+=total-Object.values(plan).reduce((a,b)=>a+b,0);return{...plan,totalMiB:total};
}
function createCache({maxBytes=32*1048576,maxItems=512,fetcher=(...args)=>root.fetch(...args),clock=()=>Date.now(),grace=600,rank=()=>0,onReady=()=>{}}={}){
 const items=new Map(),pending=new Map(),metrics={network:0,hits:0,shared:0,evictions:0,cancelled:0};let bytes=0;
 function remove(id){const old=items.get(id);if(old){bytes-=old.data.byteLength;items.delete(id);}}
 function store(id,item,prefetchOnly=false){if(item.data.byteLength>maxBytes)return;if(prefetchOnly&&(bytes+item.data.byteLength>maxBytes*.9||items.size>=maxItems*.9))return;remove(id);items.set(id,item);bytes+=item.data.byteLength;while(bytes>maxBytes||items.size>maxItems){remove(items.keys().next().value);metrics.evictions++;}}
 function setBudget(value){if(!Number.isFinite(value)||value<0||value===maxBytes)return;maxBytes=value;while(bytes>maxBytes&&items.size){remove(items.keys().next().value);metrics.evictions++;}}
 function load(url,signal,priority,returnData=true,context={}){
  if(signal?.aborted)return Promise.reject(aborted());
  const cached=items.get(url);if(cached&&cached.until>clock()){items.delete(url);items.set(url,cached);metrics.hits++;root.RidgeTileTrace?.url('request-queued',url);root.RidgeTileTrace?.url('byte-hit',url,{tier:'compressed',bytes:cached.data.byteLength});return Promise.resolve(returnData?{data:cached.data.slice(0),...(context.overview?{until:cached.until,headers:cached.headers}: {})}:{});}if(cached)remove(url);
  let job=pending.get(url);
  if(job?.controller.signal.aborted){pending.delete(url);job=null;}
  if(!job){
   root.RidgeTileTrace?.url('request-queued',url);const controller=new AbortController();job={controller,users:0,abortTimer:0,done:false,prefetchOnly:priority==='low'};pending.set(url,job);
   job.promise=(async()=>{const timeout=setTimeout(()=>controller.abort(),20000);try{
    let response;
    for(let attempt=0;attempt<2;attempt++){
     metrics.network++;root.RidgeTileTrace?.url('fetch-start',url);response=await fetcher(url,{signal:controller.signal,priority:priority||'auto',ridgeOriginal:context.original===true,ridgePriority:()=>job.prefetchOnly?'low':'high',ridgeRank:()=>rank(url)});
     if(attempt||!(response.status===429||response.status>=500))break;
     const retryAfter=response.headers.get('retry-after'),seconds=retryAfter===null?0:Number(retryAfter),requested=Number.isFinite(seconds)?seconds*1000:Date.parse(retryAfter)-Date.now(),delay=Math.max(300,requested||0);
     if(delay>2000)break;
     await response.body?.cancel();
     await new Promise((resolve,reject)=>{const stop=()=>{clearTimeout(timer);reject(aborted());},timer=setTimeout(()=>{controller.signal.removeEventListener('abort',stop);resolve();},delay);controller.signal.addEventListener('abort',stop,{once:true});if(controller.signal.aborted)stop();});
    }
    if(!response.ok){const value=response.headers.get('retry-after'),seconds=value===null?NaN:Number(value),delay=Number.isFinite(seconds)?seconds*1000:Date.parse(value)-clock();await response.body?.cancel();throw Object.assign(new Error('Map tile HTTP '+response.status),{status:response.status,retryAfterMs:Number.isFinite(delay)?Math.max(0,delay):0});}
    const data=await response.arrayBuffer();root.RidgeTileTrace?.url('body-ready',url,{bytes:data.byteLength});if(controller.signal.aborted)throw aborted();
    const cc=response.headers.get('cache-control')||'',maxAge=cc.match(/(?:^|,)\s*max-age=(\d+)/i),age=Number(response.headers.get('age'))||0;
    const seconds=Math.max(0,Math.min(21600,(maxAge?Number(maxAge[1]):3600)-age));
    job.until=seconds&&!/no-store|no-cache/i.test(cc)?clock()+seconds*1000:0;job.headers=[...response.headers];
    if(job.until)store(url,{data,until:job.until,headers:job.headers},job.prefetchOnly);
    if(items.get(url)?.until>clock())onReady(url);return data;
   }finally{clearTimeout(timeout);clearTimeout(job.abortTimer);job.done=true;if(pending.get(url)===job)pending.delete(url);}})();
   // All subscribers may cancel before the fetch settles.
   job.promise.catch(()=>{});
  }else{metrics.shared++;if(priority!=='low')job.prefetchOnly=false;}
  clearTimeout(job.abortTimer);job.users++;
  return new Promise((resolve,reject)=>{let finished=false;
   const release=()=>{if(finished)return false;finished=true;signal?.removeEventListener('abort',cancel);job.users--;if(!job.users&&!job.done)job.abortTimer=setTimeout(()=>{metrics.cancelled++;job.controller.abort();},grace);return true;};
   const cancel=()=>{if(release())reject(aborted());};signal?.addEventListener('abort',cancel,{once:true});
   job.promise.then(data=>{if(release())resolve(returnData?{data:data.slice(0),...(context.overview?{until:job.until,headers:job.headers}: {})}:{});},error=>{if(release())reject(error);});
   if(signal?.aborted)cancel();
  });
 }
 return{hasEntries:()=>items.size>0,load,setBudget,ready:url=>items.get(url)?.until>clock(),canPrefetch:()=>bytes<maxBytes*.85&&items.size<maxItems*.9,has:url=>items.get(url)?.until>clock()||pending.has(url),stats:()=>({...metrics,bytes,budgetBytes:maxBytes,maxItems,tiles:items.size,pending:pending.size}),clear(){items.clear();bytes=0;for(const job of pending.values()){clearTimeout(job.abortTimer);job.controller.abort();}pending.clear();}};
}
function networkURL(value){
 const u=new URL(value);if(u.protocol!=='ridge:')throw Error('Unsupported tile URL');
 if(u.hostname==='linz'&&/^\/v1\/tiles\/(?:aerial|elevation|topo-raster)\/WebMercatorQuad\/\d+\/\d+\/\d+\.(?:webp|png)$/.test(u.pathname))return 'https://basemaps.linz.govt.nz'+u.pathname+u.search;
 const esri=u.hostname==='esri'&&u.pathname.match(/^\/(imagery|topo)\/(\d+)\/(\d+)\/(\d+)$/);
 if(esri&&!u.search)return `https://services.arcgisonline.com/ArcGIS/rest/services/${esri[1]==='imagery'?'World_Imagery':'World_Topo_Map'}/MapServer/tile/${esri[2]}/${esri[3]}/${esri[4]}`;
 throw Error('Unsupported tile URL');
}
function createTileCaches({memory=8,saveData=false,totalMiB,fetcher,clock,onReady=()=>{}}={}){
 let budget=budgetPlan(totalMiB,{memory,saveData}),viewPolicy=()=>0,foregroundBusy=()=>false,constrained=false;const routes=new Map();
 const route=value=>{let r=routes.get(value);if(!r){r={url:networkURL(value),dem:value.includes('/tiles/elevation/')};routes.set(value,r);if(routes.size>2048)routes.delete(routes.keys().next().value);}return r;};
 const regional=root.RidgeRegionalImagery?.create({fetcher}),baseImagery=regional?.fetch||fetcher||((...args)=>root.fetch(...args)),baseTerrain=root.RidgeResponses?.elevation(fetcher)||fetcher||((...args)=>root.fetch(...args)),overview=root.RidgeNZOverview,imagery=createCache({maxBytes:budget.imageryBytes*1048576,maxItems:saveData?512:2048,fetcher:overview?overview.wrap(baseImagery):baseImagery,rank:url=>viewPolicy(url),clock,onReady}),
 terrain=createCache({maxBytes:budget.elevationBytes*1048576,maxItems:saveData?192:512,fetcher:overview?overview.wrap(baseTerrain):baseTerrain,clock,onReady});
 return{async preloadOverview(value,signal){const url=networkURL(value),store=value.includes('/tiles/elevation/')?terrain:imagery;const result=await store.load(url,signal,'low',true,{original:true,overview:true});if(signal?.aborted)throw aborted();return overview.save(url,result,signal);},hasReadyBytes:()=>imagery.hasEntries()||terrain.hasEntries(),load(value,signal,context){const url=networkURL(value),cache=value.includes('/tiles/elevation/')?terrain:imagery;return cache.load(url,signal,undefined,true,context);},
  ready(value){try{const r=route(value);return(r.dem?terrain:imagery).ready(r.url);}catch{return false;}},
  setPressure(value){constrained=!!value;imagery.setBudget(budget.imageryBytes*1048576*(constrained?.65:1));terrain.setBudget(budget.elevationBytes*1048576*(constrained?.65:1));},setBudget(total){budget=budgetPlan(total,{memory,saveData});imagery.setBudget(budget.imageryBytes*1048576*(constrained?.65:1));terrain.setBudget(budget.elevationBytes*1048576*(constrained?.65:1));},
  setViewPolicy(priority,busy){viewPolicy=priority||(()=>0);foregroundBusy=busy||(()=>false);},requestPolicy(value){try{const r=route(value),priority=viewPolicy(value);return{cached:priority<100&&(r.dem?terrain:imagery).ready(r.url),priority};}catch{return{};}},canPrefetch:()=>!foregroundBusy()&&imagery.canPrefetch(),prefetch(value,signal,context){if(value.includes('/tiles/elevation/'))return Promise.reject(Error('Imagery warmup only'));return imagery.load(networkURL(value),signal,'low',false,context);},has(value){return imagery.has(networkURL(value));},stats:()=>({budgetMiB:budget.totalMiB,pressureConstrained:constrained,imagery:imagery.stats(),terrain:terrain.stats(),regional:regional?.stats()}),clear(){imagery.clear();terrain.clear();},destroy(){routes.clear();imagery.clear();terrain.clear();regional?.destroy();}};
}
let installed;
function install(engine,options={}){
 if(installed)return installed;
 const wake=url=>engine.notifyImageRequestBytesReady?.(url);
 installed=createTileCaches({...options,onReady:wake});
 const policy=request=>installed.requestPolicy(request.url);policy.reserve=()=>installed.hasReadyBytes()?1:0;policy.key=request=>{try{return networkURL(request.url);}catch{return request.url;}};
 engine.setImageRequestPolicy?.(policy);
 engine.addProtocol('ridge',(request,controller)=>installed.load(request.url,controller.signal,{original:true}));return installed;
}
const api={createCache,networkURL,createTileCaches,budgetPlan,install};if(typeof module!=='undefined')module.exports=api;else root.RidgeTiles=api;
})(globalThis);
