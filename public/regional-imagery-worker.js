'use strict';
importScripts('/imagery.js?v=9','/regional-blend.js?v=7');
const masks=new Map();const active=new Map();
function retire(row){row.retired=true;if(!row.users)row.promise.then(m=>m.close()).catch(()=>{});}
function loadMask(url){
 let row=masks.get(url);
 if(row){masks.delete(url);masks.set(url,row);}else{
  row={users:0,retired:false,promise:null};
  row.promise=fetch(url).then(r=>{if(!r.ok)throw Error('Mask unavailable');return r.blob();}).then(b=>createImageBitmap(b)).catch(e=>{if(masks.get(url)===row)masks.delete(url);throw e;});
  masks.set(url,row);
 }
 row.users++;
 while(masks.size>8){const key=masks.keys().next().value,old=masks.get(key);masks.delete(key);retire(old);}
 return{promise:row.promise,release(){if(!--row.users&&row.retired)row.promise.then(m=>m.close()).catch(()=>{});}};
}
function release(row){row.prepared?.close();row.lease?.release();active.delete(row.job.id);}
async function finish(row,esri){
 let data;
 try{data=await row.prepared.finish(esri);row.check();self.postMessage({id:row.job.id,data},[data]);}
 catch(error){data?.close?.();self.postMessage(error.name==='AbortError'?{id:row.job.id,cancelled:true}:{id:row.job.id,error:'Regional imagery composition failed'});}
 finally{release(row);}
}
self.onmessage=async({data:job})=>{
 if(job.cancel!==undefined){const row=active.get(job.cancel);if(row){row.cancelled=true;row.prepared?.close();if(row.waiting){release(row);self.postMessage({id:job.cancel,cancelled:true});}}return;}
 if(job.esriFor!==undefined){const row=active.get(job.esriFor);if(row?.waiting){row.waiting=false;await finish(row,job.data);}return;}
 const row={job,cancelled:false,prepared:null,lease:null,waiting:false};active.set(job.id,row);
 row.check=()=>{if(row.cancelled)throw RidgeRegionalBlend.cancelled();};
 row.lease=job.linz&&!job.overlay&&!job.meta?.rings?loadMask(job.meta?.maskURL||'/assets/west-coast-mask.png?v=1'):null;
 try{
  row.prepared=await RidgeRegionalBlend.prepare({...job,mask:row.lease?.promise||Promise.resolve(null),checkCancelled:row.check});row.check();
  // All raster-mask reads finish in prepare; no cache lease is held over I/O.
  row.lease?.release();row.lease=null;
  if(job.deferEsri&&row.prepared.needsEsri){row.waiting=true;self.postMessage({id:job.id,needsEsri:true});return;}
  await finish(row,job.esri);
 }catch(error){release(row);self.postMessage(error.name==='AbortError'?{id:job.id,cancelled:true}:{id:job.id,error:'Regional imagery composition failed'});}
};
