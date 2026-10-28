/* RIDGE_SCHEDULER_V2: indexed admission; one adaptive ready-byte reserve.
 * This template runs inside the pinned bundle and uses its private helpers. */
let Un;
(function(e){
 let pending=new Set(),byKey=new Map(),n=0,r=0,i={},ready=[],cold=[],policy=null,dirty=false,serial=0,cachedActive=0,cachedStreak=0,scheduled=false,policyEvaluations=0,rebuilds=0;
 const moving=()=>{for(const key of Object.keys(i))if(i[key]())return true;return false;};
 const limit=()=>Math.max(1,moving()?On.MAX_PARALLEL_IMAGE_REQUESTS_PER_FRAME:On.MAX_PARALLEL_IMAGE_REQUESTS);
 const reserve=()=>policy?Math.min(limit()-1,Math.max(0,Math.min(1,typeof policy.reserve==='function'?policy.reserve():1))):0;
 const info=j=>{policyEvaluations++;try{return policy?.(j.requestParameters)||{};}catch{return{};}};
 const trace=(stage,j,extra={})=>{if(globalThis.RidgeTileTrace?.enabled?.())globalThis.RidgeTileTrace.url(stage,j.requestParameters.url,{attempt:j.ridgeAttempt,...extra});};
 const before=(a,b)=>a.score<b.score||a.score===b.score&&a.ordinal<b.ordinal;
 function push(heap,j){let k=heap.length;heap.push(j);while(k){const p=(k-1)>>1;if(!before(j,heap[p]))break;heap[k]=heap[p];k=p;}heap[k]=j;}
 function pop(heap){const first=heap[0],last=heap.pop();if(heap.length){let k=0;while(k*2+1<heap.length){let child=k*2+1;if(child+1<heap.length&&before(heap[child+1],heap[child]))child++;if(!before(heap[child],last))break;heap[k]=heap[child];k=child;}heap[k]=last;}return first;}
 function top(heap){while(heap.length&&!pending.has(heap[0]))pop(heap);return heap[0];}
 function keyOf(j){try{return policy?.key?.(j.requestParameters)||j.requestParameters.url;}catch{return j.requestParameters.url;}}
 function track(j){j.queueKey=keyOf(j);let rows=byKey.get(j.queueKey);if(!rows)byKey.set(j.queueKey,rows=new Set());rows.add(j);}
 function untrack(j){const rows=byKey.get(j.queueKey);rows?.delete(j);if(!rows?.size)byKey.delete(j.queueKey);}
 function index(j){const v=info(j);j.ridgeCached=v.cached===true;j.score=Number.isFinite(v.priority)?v.priority:0;push(j.ridgeCached?ready:cold,j);}
 function rebuild(){ready=[];cold=[];byKey.clear();for(const j of pending){track(j);index(j);}dirty=false;rebuilds++;}
 function defer(){if(scheduled)return;scheduled=true;Promise.resolve().then(()=>{scheduled=false;pump();});}
 function pump(){
  if(!pending.size){ready=[];cold=[];byKey.clear();return;}
  const overgrown=ready.length+cold.length>pending.size*2+64;
  // Dirty priorities cannot affect admission while every slot is occupied.
  // Keep the dirty flag until capacity returns; still bound stale heap nodes.
  if(n>=limit()&&!overgrown)return;
  if(dirty||overgrown)rebuild();
  while(n<limit()){
   const a=top(ready),b=n-cachedActive<limit()-reserve()?top(cold):null;
   const job=a&&!(cachedStreak>=3&&b)?pop(ready):b?pop(cold):null;if(!job)break;
   const current=info(job).cached===true;if(!current&&job.ridgeCached&&n-cachedActive>=limit()-reserve()){job.ridgeCached=false;push(cold,job);continue;}job.ridgeCached=current;
   pending.delete(job);untrack(job);job.abortController.signal.removeEventListener('abort',job.cancelQueued);
   cachedStreak=job.ridgeCached?cachedStreak+1:0;run(job);
  }
 }
 e.setPolicy=f=>{policy=typeof f==='function'?f:null;dirty=true;pump();};
 e.pump=()=>{dirty=true;pump();};
 e.notifyReady=key=>{if(byKey.has(key)){dirty=true;defer();}};
 e.stats=()=>({active:n,cachedActive,queued:pending.size,moving:moving(),limit:limit(),cachedReserve:reserve(),policyEvaluations,queueRebuilds:rebuilds,activeDecodes:decoding,queuedDecodes:decodeWaiting.size,decodeLimit:decodeCap()});
 e.resetRequestQueue=()=>{for(const j of pending){j.abortController.signal.removeEventListener('abort',j.cancelQueued);j.onError(new un());}pending=new Set();byKey.clear();ready=[];cold=[];n=0;r=0;i={};cachedActive=0;cachedStreak=0;dirty=false;};
 e.addThrottleControl=f=>{const key=r++;i[key]=f;return key;};
 e.removeThrottleControl=key=>{delete i[key];pump();};
 e.transformAndGetImage=async(manager,url,type,controller,refresh=true,options)=>{const request=await manager.transformRequest(url,type);fn(controller.signal);return e.getImage(request,controller,refresh,options);};
 e.getImage=(request,controller,refresh=true,options)=>new Promise((resolve,reject)=>{
  request.headers||={};request.headers.accept='image/webp,*/*';I(request,{type:'image'});
  const j={requestParameters:request,abortController:controller,supportImageRefresh:refresh,imageBitmapOptions:options,state:'queued',onError:reject,onSuccess:resolve,ordinal:serial++};
  j.cancelQueued=()=>{if(!pending.delete(j))return;untrack(j);controller.signal.removeEventListener('abort',j.cancelQueued);reject(new un(controller.signal.reason));defer();};
  if(controller.signal.aborted){reject(new un(controller.signal.reason));return;}
  j.ridgeAttempt=globalThis.RidgeTileTrace?.url('native-image-queued',request.url);pending.add(j);track(j);controller.signal.addEventListener('abort',j.cancelQueued,{once:true});index(j);pump();
 });
 let decoding=0,decodeLimit=null;const decodeWaiting=new Set();
 const decodeCap=()=>decodeLimit??limit();
 function pumpDecodes(){for(const job of decodeWaiting){if(decoding>=decodeCap())break;decodeWaiting.delete(job);job.start();}}
 e.setDecodeLimit=value=>{decodeLimit=value==null?null:Math.max(1,Math.min(96,Math.floor(Number(value)||1)));pumpDecodes();};
 async function prepareImage(data,options,signal,onStart){
  fn(signal);
  if(decoding<decodeCap()&&!decodeWaiting.size){decoding++;try{onStart();return await decode(data,options);}finally{decoding--;pumpDecodes();}}
  return new Promise((resolve,reject)=>{
  const job={start(){signal.removeEventListener('abort',cancel);if(signal.aborted){reject(new un(signal.reason));return;}decoding++;onStart();
   Promise.resolve().then(()=>decode(data,options)).then(resolve,reject).finally(()=>{decoding--;pumpDecodes();});
  }},cancel=()=>{if(decodeWaiting.delete(job)){reject(new un(signal.reason));pumpDecodes();}};
  if(signal.aborted){reject(new un(signal.reason));return;}decodeWaiting.add(job);signal.addEventListener('abort',cancel,{once:true});pumpDecodes();
 });}
 const decode=(data,options)=>typeof createImageBitmap==='function'?Gt(data,options):Kt(data);
 async function run(j){
  j.state='running';if(j.ridgeCached)cachedActive++;trace('native-image-admitted',j,{active:n+1,queued:pending.size});n++;
  const {requestParameters:request,supportImageRefresh:refresh,imageBitmapOptions:options,abortController:controller}=j;
  const element=refresh===false&&!options&&!Bt(self)&&!kn(request.url)&&(!request.headers||Object.keys(request.headers).every(k=>k==='accept'));
  try{
   trace('image-delivery-start',j);const response=await(element?loadElement(request,controller):Rn(request,controller));trace('image-delivery-finished',j);if(controller.signal.aborted){response.data?.close?.();fn(controller.signal);}delete j.abortController;j.state='completed';
   if(response.data instanceof HTMLImageElement||Wt(response.data))j.onSuccess(response);
   else if(!response.data||response.data.byteLength===0)j.onSuccess({data:null,cacheControl:response.cacheControl,expires:response.expires});
   else{fn(controller.signal);trace('decode-queued',j);const data=await prepareImage(response.data,options,controller.signal,()=>trace('decode-start',j));trace('decode-finished',j);if(controller.signal.aborted){data?.close?.();fn(controller.signal);}j.onSuccess({data,cacheControl:response.cacheControl,expires:response.expires});}
  }catch(error){delete j.abortController;j.onError(nt(error));}
  finally{n--;if(j.ridgeCached)cachedActive--;pump();}
 }
 function loadElement(request,controller){return new Promise((resolve,reject)=>{
  const image=new Image(),url=request.url,credentials=request.credentials;
  if(credentials==='include')image.crossOrigin='use-credentials';else if(credentials==='same-origin'||!Vn(url))image.crossOrigin='anonymous';
  controller.signal.addEventListener('abort',()=>{image.src='';reject(new un(controller.signal.reason));});image.fetchPriority='high';
  image.onload=()=>{image.onerror=image.onload=null;resolve({data:image});};image.onerror=()=>{image.onerror=image.onload=null;if(!controller.signal.aborted)reject(Error('Could not load image. Please make sure to use a supported image type such as PNG or JPEG. Note that SVGs are not supported.'));};image.src=url;
 });}
})(Un||={});Un.resetRequestQueue();
