import {test} from 'node:test';import assert from 'node:assert/strict';import vm from 'node:vm';import {readFile} from 'node:fs/promises';
async function load(name,globals){const c={module:{exports:{}},Response,Headers,Blob,AbortController,setTimeout,clearTimeout,...globals};c.globalThis=c;vm.runInNewContext(await readFile(new URL('../www/'+name,import.meta.url),'utf8'),c);return c.module.exports;}
test('map elevation requests go directly to shared native transport without WebView storage work',async()=>{
 let opens=0,calls=0;const caches={open(){opens++;throw Error('Duplicate storage should not open');}};const api=await load('response-cache.js',{caches});
 const fetcher=async(url,options)=>{calls++;assert.equal(url,'tile');assert.equal(options.priority,'high');return new Response(new Uint8Array([1,2,3]),{headers:{'cache-control':'no-store'}});};
 const direct=api.elevation(fetcher);assert.equal(direct,fetcher);const r=await direct('tile',{priority:'high'});assert.deepEqual([...new Uint8Array(await r.arrayBuffer())],[1,2,3]);assert.equal(r.headers.get('cache-control'),'no-store');assert.equal(opens,0);assert.equal(calls,1);
});
test('profile worker keeps exact decoded heights and memory reuse without duplicate persistent storage',async()=>{
 let opens=0,requests=0,decodes=0;const api=await load('terrain-core.js',{caches:{open(){opens++;throw Error('Must use native storage');}}});
 const engine=api.createEngine({G:{},fetcher:async url=>{requests++;assert.match(url,/localhost\/_ridge\/linz/);return new Response('encoded');},decoder:async()=>{decodes++;return new Float32Array([12.5,98.75]);}});
 const signal=new AbortController().signal,a=await engine.read(14,12000,9000,'demo',signal),b=await engine.read(14,12000,9000,'demo',signal);
 assert.equal(a,b);assert.deepEqual([...a.heights],[12.5,98.75]);assert.equal(a.provider,'linz');assert.equal(requests,1);assert.equal(decodes,1);assert.equal(opens,0);engine.clear();
});
