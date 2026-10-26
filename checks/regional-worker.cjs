const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
function runtime(failFirst=false){
 let downloads=0,closed=0;const replies=[];
 class Canvas{getContext(){return{drawImage(){},getImageData(){return{data:new Uint8ClampedArray(256*256*4).fill(180)};},putImageData(){},globalCompositeOperation:'source-over'};}async convertToBlob(){return new Blob([new Uint8Array([7,8])]);}}
 const context=vm.createContext({Blob,Promise,Error,Uint8Array,ArrayBuffer,Uint8ClampedArray,Math,OffscreenCanvas:Canvas,fetch:async()=>({ok:!(failFirst&&++downloads===1),blob:async()=>new Blob([new Uint8Array([3])])}),createImageBitmap:async()=>({width:256,height:256,close(){closed++;}})});if(!failFirst)context.fetch=async()=>{downloads++;return{ok:true,blob:async()=>new Blob([new Uint8Array([3])])};};
 context.self=context;context.postMessage=(data,transfers)=>{if(data.data)assert.equal(transfers[0],data.data);replies.push(data);};context.importScripts=(...paths)=>paths.forEach(p=>vm.runInContext(fs.readFileSync('public/'+p.slice(1).split('?')[0],'utf8'),context));vm.runInContext(fs.readFileSync('public/regional-imagery-worker.js','utf8'),context);
 return{async send(id,linz=true,compensate=false){await context.onmessage({data:{id,compensate,linz:linz?new Uint8Array([1]).buffer:null,esri:new Uint8Array([2]).buffer,z:14,x:10,y:12,esriZ:13,meta:{z:13,x:5,y:6,width:256,height:256}}});return replies.at(-1);},stats:()=>({downloads,closed})};
}
(async()=>{
 const r=runtime();assert.deepEqual([...new Uint8Array((await r.send(1)).data)],[7,8]);await r.send(2);await r.send(3,false);assert.equal(r.stats().downloads,1,'Mask loaded once, never fetched for a crop-only job');assert.equal(r.stats().closed,5,'All per-job images closed; one reusable mask remains');
 const retry=runtime(true);assert.equal((await retry.send(1)).error,'Regional imagery composition failed');assert.ok((await retry.send(2)).data);assert.equal(retry.stats().downloads,2,'A failed mask fetch must be retryable');
 const crop=runtime();await crop.send(1,false);assert.equal(crop.stats().downloads,0);assert.ok((await crop.send(2,false,true)).data,'Worker imports the shared grade and executes compensation');
 console.log('PASS: shipped worker imports actual compositor, transfers output, reuses one mask, skips mask for overzoom, closes job bitmaps and recovers failed mask fetch');
})().catch(e=>{console.error(e);process.exitCode=1});
