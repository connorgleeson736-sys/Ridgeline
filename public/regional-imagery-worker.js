'use strict';
importScripts('/imagery.js?v=9','/regional-blend.js?v=6');
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
self.onmessage=async({data:job})=>{
 if(job.cancel!==undefined){const token=active.get(job.cancel);if(token)token.cancelled=true;return;}
 const token={cancelled:false};active.set(job.id,token);
 const checkCancelled=()=>{if(token.cancelled)throw RidgeRegionalBlend.cancelled();};
 const lease=job.linz&&!job.overlay&&!job.meta?.rings?loadMask(job.meta?.maskURL||'/assets/west-coast-mask.png?v=1'):null;
 try{
  const mask=lease?.promise||Promise.resolve(null);
  const data=await RidgeRegionalBlend.composite({...job,mask,checkCancelled});checkCancelled();self.postMessage({id:job.id,data},[data]);
 }catch(error){self.postMessage(error.name==='AbortError'?{id:job.id,cancelled:true}:{id:job.id,error:'Regional imagery composition failed'});}
 finally{lease?.release();active.delete(job.id);}
};
