/* Native transport controls only. The website renderer and its quality policy remain owners. */
(function(root){'use strict';
const MESSAGE='__ridge_transport_v1',abort=()=>Object.assign(Error('Map request cancelled'),{name:'AbortError'});
function install({send,fetcher=root.fetch.bind(root),timers=root}={}){
 if(root.__ridgeTransportInstalled)return root.__ridgeTransportInstalled;
 const active=new Map(),workers=new Set();let timer=null,dead=false;
 function control(op,id,priority){try{send(op,id,priority);}catch{}}
 function score(options){let p;try{p=options.ridgePriority?.()??options.priority;const rank=Number(options.ridgeRank?.())||0;return Math.max(0,Math.min(1000,Math.round(p==='low'?200+Math.max(0,rank):rank>=100?100+rank:Math.max(0,rank))));}catch{return 0;}}
 function pump(){timer=null;for(const [id,j]of active){const p=score(j.options);if(p!==j.priority){j.priority=p;control('priority',id,p);}}if(active.size)timer=timers.setTimeout(pump,120);}
 function done(id){const j=active.get(id);if(!j)return;active.delete(id);j.signal?.removeEventListener('abort',j.cancel);control('cancel',id);if(!active.size&&timer!==null){timers.clearTimeout(timer);timer=null;}}
 function normalize(headers){const until=Number(headers.get('X-Ridge-Expires-At'));if(headers.has('X-Ridge-Expires-At'))headers.set('Cache-Control',until>Date.now()?'private, max-age='+Math.max(0,Math.floor((until-Date.now())/1000)):'no-store');}
 async function fetch(input,options={}){
  const original=input instanceof Request?input:null;let url;
  try{url=new URL(original?original.url:String(input),root.location?.href);}catch{return fetcher(input,options);}
  if((options.method??original?.method??'GET').toUpperCase()!=='GET'||url.origin!=='https://localhost'||!url.pathname.startsWith('/_ridge/')||url.pathname.startsWith('/_ridge/overviews/'))return fetcher(input,options);
  const signal=options.signal??original?.signal;
  if(dead||signal?.aborted)throw abort();
  const id=Array.from(root.crypto.getRandomValues(new Uint32Array(4)),n=>n.toString(16).padStart(8,'0')).join('');
  const headers=new Headers(options.headers??original?.headers);headers.set('X-Ridge-Request',id);headers.set('X-Ridge-Priority',String(score(options)));
  const j={signal,options,priority:score(options),cancel:()=>done(id)};active.set(id,j);control('begin',id,j.priority);signal?.addEventListener('abort',j.cancel,{once:true});if(timer===null)timer=timers.setTimeout(pump,120);
  let response;
  try{response=await fetcher(input,{...options,headers,signal});if(signal?.aborted){await response.body?.cancel();throw abort();}}
  catch(error){done(id);throw error;}
  if(!response.body){done(id);return response;}
  const reader=response.body.getReader(),h=new Headers(response.headers);
  const stream=new ReadableStream({async pull(controller){try{const row=await reader.read();if(row.done){normalize(wrapped.headers);done(id);controller.close();}else controller.enqueue(row.value);}catch(error){done(id);controller.error(error);}},async cancel(reason){done(id);await reader.cancel(reason);}});
  const wrapped=new Response(stream,{status:response.status,statusText:response.statusText,headers:h});
  function decorate(result){
  const arrayBuffer=result.arrayBuffer.bind(result),clone=result.clone.bind(result);
  result.arrayBuffer=async()=>{
   try{const bytes=await arrayBuffer();normalize(result.headers);return bytes;}
   catch(error){
    // Streaming cannot replace bytes already emitted. Whole-tile readers can
    // recover the original saved-ancestor fallback after a mid-body failure.
    if(signal?.aborted||options.__ridgeFallback||response.status!==200)throw error;
    const fallbackHeaders=new Headers(options.headers??original?.headers);fallbackHeaders.set('X-Ridge-Only-Saved','1');
    const fallback=await fetch(input,{...options,headers:fallbackHeaders,__ridgeFallback:true});
    if(!fallback.ok){await fallback.body?.cancel();throw error;}
    const bytes=await fallback.arrayBuffer();for(const k of [...result.headers.keys()])result.headers.delete(k);for(const [k,v]of fallback.headers)result.headers.set(k,v);return bytes;
   }
  };
  result.blob=async()=>new Blob([await result.arrayBuffer()],{type:result.headers.get('Content-Type')||''});
  result.clone=()=>decorate(clone());
  return result;
  }
  // CacheStorage copies may consume the stream directly; validation failures
  // reject their write instead of retaining an invalid image.
  return decorate(wrapped);
 }
 const Worker=root.Worker;
 if(Worker)root.Worker=class extends Worker{
  constructor(...args){super(...args);if(String(args[0]).includes("native-regional-worker"))workers.add(this);const ids=new Set();this.addEventListener('message',e=>{const m=e.data;if(!m||m[MESSAGE]!==true)return;e.stopImmediatePropagation();if(m.op==='begin')ids.add(m.id);if(m.op==='cancel')ids.delete(m.id);control(m.op,m.id,m.priority);},{capture:true});const terminate=this.terminate.bind(this);this.terminate=()=>{for(const id of ids)control('cancel',id);ids.clear();workers.delete(this);terminate();};this.addEventListener('error',()=>{for(const id of ids)control('cancel',id);ids.clear();});}
 };
 root.fetch=fetch;
 const api={fetch,stats:()=>({active:active.size}),trim(){for(const w of workers)w.postMessage({__ridgeTrimImages:true});},destroy(){dead=true;for(const id of [...active.keys()])done(id);}};
 root.addEventListener?.('pagehide',()=>{for(const id of [...active.keys()])done(id);});
 root.__ridgeTransportInstalled=api;return api;
}
function installWorker(){if(root.location?.origin!=='https://localhost')return;return install({send:(op,id,priority)=>root.postMessage({[MESSAGE]:true,op,id,priority})});}
function installPage(){if(root.location?.origin!=='https://localhost'||!root.RidgeTileControl)return;return install({send:(op,id,priority)=>{if(op==='cancel')root.RidgeTileControl.cancel(id);else root.RidgeTileControl[op](id,priority);}});}
const api={install,installPage,installWorker};if(typeof module!=='undefined')module.exports=api;else root.RidgeNativeTransport=api;
})(globalThis);
