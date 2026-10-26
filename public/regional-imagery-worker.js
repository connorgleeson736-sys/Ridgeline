'use strict';
importScripts('/imagery.js?v=9','/regional-blend.js?v=6');
const masks=new Map();const active=new Map();
function loadMask(url){if(masks.has(url)){const p=masks.get(url);masks.delete(url);masks.set(url,p);return p;}const p=fetch(url).then(r=>{if(!r.ok)throw Error('Mask unavailable');return r.blob();}).then(b=>createImageBitmap(b)).catch(e=>{if(masks.get(url)===p)masks.delete(url);throw e;});masks.set(url,p);while(masks.size>8){const k=masks.keys().next().value,old=masks.get(k);masks.delete(k);old.then(m=>m.close()).catch(()=>{});}return p;}
self.onmessage=async({data:job})=>{
 if(job.cancel!==undefined){const token=active.get(job.cancel);if(token)token.cancelled=true;return;}
 const token={cancelled:false};active.set(job.id,token);
 const checkCancelled=()=>{if(token.cancelled)throw RidgeRegionalBlend.cancelled();};
 try{
  const mask=job.linz&&!job.overlay&&!job.meta?.rings?loadMask(job.meta?.maskURL||'/assets/west-coast-mask.png?v=1'):Promise.resolve(null);
  const data=await RidgeRegionalBlend.composite({...job,mask,checkCancelled});checkCancelled();self.postMessage({id:job.id,data},[data]);
 }catch(error){self.postMessage(error.name==='AbortError'?{id:job.id,cancelled:true}:{id:job.id,error:'Regional imagery composition failed'});}
 finally{active.delete(job.id);}
};
