/* Bounded encoded-input reuse. Each consumer owns cancellation and a copy;
   a canceled child cannot abort an input still used by another child. */
(function(root){'use strict';
const abort=()=>Object.assign(Error('Input cancelled'),{name:'AbortError'});
function create({fetcher=(...a)=>root.fetch(...a),maxBytes=8*1048576,maxItems=128}={}){
 const ready=new Map(),pending=new Map();let bytes=0,dead=false,hits=0,shared=0;
 function trim(){while(bytes>maxBytes||ready.size>maxItems){const [k,v]=ready.entries().next().value;ready.delete(k);bytes-=v.data.byteLength;}}
 async function fetch(url,options={}){
  if(dead||options.signal?.aborted)throw abort();const old=ready.get(url);
  const response=v=>new Response([204,205,304].includes(v.status)?null:v.data.slice(0),{status:v.status,headers:v.headers});
  if(old&&old.until>Date.now()){ready.delete(url);ready.set(url,old);hits++;return response(old);}
  if(old){bytes-=old.data.byteLength;ready.delete(url);}
  let job=pending.get(url);if(job?.controller.signal.aborted){pending.delete(url);job=null;}
  if(!job){const controller=new AbortController();job={controller,users:0,done:false,abortTimer:null,original:options.ridgeOriginal===true};pending.set(url,job);
   job.promise=(async()=>{const timer=setTimeout(()=>controller.abort(),20000);try{const tracing=job.original&&root.RidgeTileTrace?.enabled?.(),started=tracing?root.performance.now():0;if(tracing)root.RidgeTileTrace.url('network-start',url);const r=await fetcher(url,{...options,signal:controller.signal});const headersAt=tracing?root.performance.now():0;if(tracing)root.RidgeTileTrace.url('network-headers',url,{elapsedMs:headersAt-started});const data=await r.arrayBuffer();if(tracing)root.RidgeTileTrace.url('network-body-ready',url,{elapsedMs:root.performance.now()-headersAt,bytes:data.byteLength});if(dead||controller.signal.aborted)throw abort();
    const headers=[...r.headers],cc=r.headers.get('cache-control')||'',age=Number(r.headers.get('age'))||0,seconds=Math.max(0,Math.min(3600,Number(cc.match(/max-age=(\d+)/i)?.[1]||300)-age)),v={data,status:r.status,headers,until:Date.now()+seconds*1000};
    if(r.ok&&seconds&&!/no-store|no-cache/i.test(cc)&&data.byteLength<=maxBytes){ready.set(url,v);bytes+=data.byteLength;trim();}return v;
   }finally{clearTimeout(timer);clearTimeout(job.abortTimer);job.done=true;if(pending.get(url)===job)pending.delete(url);}})();job.promise.catch(()=>{});
  }else shared++;
  clearTimeout(job.abortTimer);job.abortTimer=null;job.users++;return new Promise((resolve,reject)=>{let done=false;const release=()=>{if(done)return false;done=true;options.signal?.removeEventListener('abort',cancel);if(!--job.users&&!job.done){if(job.original)job.abortTimer=setTimeout(()=>{if(!job.users&&!job.done)job.controller.abort();},250);else job.controller.abort();}return true;},cancel=()=>{if(release())reject(abort());};options.signal?.addEventListener('abort',cancel,{once:true});job.promise.then(v=>{if(release())resolve(response(v));},e=>{if(release())reject(e);});if(options.signal?.aborted)cancel();});
 }
 return{fetch,stats:()=>({inputBytes:bytes,inputTiles:ready.size,inputHits:hits,inputShared:shared,inputPending:pending.size}),destroy(){dead=true;for(const j of pending.values()){clearTimeout(j.abortTimer);j.controller.abort();}pending.clear();ready.clear();bytes=0;}};
}
/* Same origin CacheStorage namespace as the route/Three elevation readers.
   Optional and best-effort; a quota/permission failure never blocks a tile. */
function elevation(fetcher=(...a)=>root.fetch(...a),{lookupMs=40,cacheStorage=root.caches}={}){
 let writes=0,cooldown=0;let disk;try{disk=Promise.resolve(cacheStorage?.open('ridgeline-elevation-v1')).catch(()=>null);}catch{disk=Promise.resolve(null);}
 const lifetime=headers=>{const cc=headers.get('cache-control')||'';if(/no-store|no-cache/i.test(cc))return 0;return Math.max(0,Math.min(86400,Number(cc.match(/max-age=(\d+)/i)?.[1]??86400)-(Number(headers.get('age'))||0)))*1000;};
 return async(url,options={})=>{
  if(options.signal?.aborted)throw abort();let cache=null;
  const lookup=Date.now()<cooldown?Promise.resolve(null):disk.then(async value=>{cache=value;if(options.signal?.aborted)return null;try{const r=await value?.match(url);if(r){if(Date.now()-Number(r.headers.get('x-ridgeline-cached-at')||0)<lifetime(r.headers))return r;}}catch{}return null;});
  const cached=await new Promise((resolve,reject)=>{let done=false;const finish=(error,value)=>{if(done)return;done=true;clearTimeout(timer);options.signal?.removeEventListener('abort',stop);error?reject(error):resolve(value);},stop=()=>finish(abort()),timer=setTimeout(()=>{cooldown=Date.now()+30000;finish(null,null);},options.ridgeOriginal?Math.min(lookupMs,16):lookupMs);options.signal?.addEventListener('abort',stop,{once:true});lookup.then(value=>finish(null,value),()=>finish(null,null));if(options.signal?.aborted)stop();});
  if(options.signal?.aborted)throw abort();if(cached)return cached;
  const r=await fetcher(url,options);if(options.signal?.aborted){await r.body?.cancel();throw abort();}
  if(cache&&r.ok&&lifetime(r.headers)>0){
   const copy=r.clone();void(async()=>{try{const h=new Headers(copy.headers);h.set('x-ridgeline-cached-at',String(Date.now()));await cache.put(url,new Response(copy.body,{headers:h}));if(++writes%8===1){const keys=await cache.keys();await Promise.all(keys.slice(0,Math.max(0,keys.length-64)).map(k=>cache.delete(k)));}}catch{}})();
  }return r;
 };
}
const api={create,elevation};if(typeof module!=='undefined')module.exports=api;else root.RidgeResponses=api;
})(globalThis);
