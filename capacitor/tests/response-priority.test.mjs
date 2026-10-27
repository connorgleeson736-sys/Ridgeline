import {test} from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
const source=await readFile(new URL('../www/response-cache.js',import.meta.url),'utf8');
test('a visible consumer promotes a shared source input originally requested by prefetch',async()=>{
 const ctx={module:{exports:{}},Response,Headers,AbortController,setTimeout,clearTimeout,Date};ctx.globalThis=ctx;vm.runInNewContext(source,ctx);
 let nativeOptions;const cache=ctx.module.exports.create({fetcher:async(url,options)=>{nativeOptions=options;return new Promise((resolve,reject)=>options.signal.addEventListener('abort',()=>reject(Object.assign(Error('cancel'),{name:'AbortError'})),{once:true}));}});
 const prefetch=new AbortController(),visible=new AbortController();
 const a=cache.fetch('https://localhost/_ridge/esri/parent',{signal:prefetch.signal,ridgeOriginal:true,ridgePriority:()=> 'low',ridgeRank:()=>100});
 assert.equal(nativeOptions.ridgePriority(),'low');
 const b=cache.fetch('https://localhost/_ridge/esri/parent',{signal:visible.signal,ridgeOriginal:true,ridgePriority:()=> 'high',ridgeRank:()=>0});assert.equal(nativeOptions.ridgePriority(),'high');assert.equal(nativeOptions.ridgeRank(),0);
 visible.abort();await assert.rejects(b,{name:'AbortError'});assert.equal(nativeOptions.ridgePriority(),'low');assert.equal(nativeOptions.signal.aborted,false);
 prefetch.abort();await assert.rejects(a,{name:'AbortError'});await new Promise(r=>setTimeout(r,10));assert.equal(nativeOptions.signal.aborted,true);cache.destroy();
});

test('encoded input cache hits advertise remaining freshness rather than a restarted lifetime',async()=>{
 let now=100000,calls=0;const ctx={module:{exports:{}},Response,Headers,AbortController,setTimeout,clearTimeout,Date:{now:()=>now}};ctx.globalThis=ctx;vm.runInNewContext(source,ctx);
 const cache=ctx.module.exports.create({fetcher:async()=>{calls++;return new Response('tile',{headers:{'Cache-Control':'max-age=60'}});}});
 try{await cache.fetch('tile');now+=40000;const hit=await cache.fetch('tile');assert.equal(hit.headers.get('Cache-Control'),'private, max-age=20');assert.equal(calls,1);}finally{cache.destroy();}
});
test('elevation disk-cache hits preserve an absolute expiry and do not perform another fetch',async()=>{
 let now=100000,calls=0;const ctx={module:{exports:{}},Response,Headers,AbortController,setTimeout,clearTimeout,Date:{now:()=>now}};ctx.globalThis=ctx;vm.runInNewContext(source,ctx);
 const response=new Response('elevation',{headers:{'Cache-Control':'max-age=60','X-Ridge-Expires-At':'120000','x-ridgeline-cached-at':'60000'}});
 const fetch=ctx.module.exports.elevation(async()=>{calls++;return new Response('new');},{cacheStorage:{open:async()=>({match:async()=>response.clone()})}});
 const hit=await fetch('tile');assert.equal(hit.headers.get('Cache-Control'),'private, max-age=20');assert.equal(calls,0);assert.equal(await hit.text(),'elevation');
});
