const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const tick=()=>new Promise(r=>setImmediate(r));
(async()=>{
 let unblock,closed=0,encoded=0;const gate=new Promise(r=>unblock=r);
 class Worker {
  constructor(){const parent=this;const context=vm.createContext({Blob,Promise,Error,Uint8Array,ArrayBuffer,Uint8ClampedArray,Math,OffscreenCanvas:class{getContext(){return{drawImage(){},getImageData(){return{data:new Uint8ClampedArray(256*256*4)};},putImageData(){}};}async convertToBlob(){encoded++;return new Blob([new Uint8Array([1])]);}},fetch:async()=>({ok:true,blob:async()=>new Blob()}),createImageBitmap:async()=>{await gate;return{width:256,height:256,close(){closed++;}};}});context.self=context;context.postMessage=data=>queueMicrotask(()=>parent.onmessage?.({data}));context.importScripts=(...paths)=>paths.forEach(p=>vm.runInContext(fs.readFileSync('public/'+p.slice(1).split('?')[0],'utf8'),context));vm.runInContext(fs.readFileSync('public/regional-imagery-worker.js','utf8'),context);this.context=context;}
  postMessage(data){queueMicrotask(()=>this.context.onmessage({data}));}terminate(){}
 }
 const previous=global.Worker;global.Worker=Worker;
 const R=require('../public/regional-imagery.js'),meta={z:0,x:0,y:0,width:256,height:256,outer:[[[0,0],[256,0],[256,256],[0,256],[0,0]]],inner:[[[0,0],[256,0],[256,256],[0,256],[0,0]]]};
 const r=R.create({meta,overviews:null,south:null,reference:null,workerFactory:()=>new Worker(),fetcher:async()=>new Response(new Uint8Array(20))});
 const controller=new AbortController(),promise=r.fetch('https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/4/7/7.webp?ridge-grade=snow-v3',{signal:controller.signal}).catch(e=>assert.equal(e.name,'AbortError'));
 await tick();await tick();assert.equal(r.stats().workerJobs,1);controller.abort();await promise;assert.equal(r.stats().compositesActive,1);unblock();await tick();await tick();assert.equal(r.stats().compositesActive,0);assert.equal(r.stats().compositionFailures,0);assert.equal(closed,1);assert.equal(encoded,0);r.destroy();global.Worker=previous;
 fs.writeFileSync('AUDIT_WORKER_VALIDATION.json',JSON.stringify({shippedWorkerAndScheduler:true,abortSettlesBeforeDecode:true,slotRetainedUntilActualAcknowledgement:true,bitmapClosed:true,encodeSkipped:true,abortNotFailure:true,caveat:'Actual shipped worker/compositor in an async VM worker bridge with controlled decode; not browser worker scheduling latency.'},null,2)+'\n');console.log('PASS actual worker cancellation, acknowledgement, bitmap cleanup and slot bound');
})().catch(e=>{console.error(e);process.exitCode=1});
