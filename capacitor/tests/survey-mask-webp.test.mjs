import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

const source=await readFile(new URL('../www/gap-imagery.js',import.meta.url),'utf8'),imageResult=await readFile(new URL('../mobile/image-result.js',import.meta.url),'utf8');
const L='https://localhost/_ridge/linz';
function setup(respond){
 const root={URL,Response,Headers,AbortController,Object,Error,Promise,Math,Number,
  RidgeRegionalImagery:{mode:()=> 'boundary',relation:()=>0}};root.globalThis=root;
 vm.runInNewContext(imageResult,root);vm.runInNewContext(source,root);
 const atlas={z:13,coarse:{},entries:{'8000/5000':{z:13,x:8000,y:5000,vector:true,outer:[],seamRings:[[[0,0]]],maskURL:null,
  fineSources:[{name:'canterbury-2015-2016-0.3m',maxZoom:19}]}}};
 const calls=[];const fetcher=async(url,options)=>{calls.push(url);return respond(url,options);};
 const base=async()=>new Response(new Uint8Array([1]),{status:200,headers:{'Cache-Control':'max-age=60'}});
 let given=null;const gap=root.RidgeGapImagery.create({fetcher,base,atlas,deferEsri:true,compose:async data=>{given=data;return new Uint8Array([9]);}});
 return {gap,calls,given:()=>given};
}
const tile=`${L}/v1/tiles/aerial/WebMercatorQuad/15/32000/20000.webp?api=k`;
const ok=bytes=>new Response(new Uint8Array(bytes),{status:200,headers:{'Cache-Control':'max-age=60'}});
const masks=calls=>calls.filter(u=>u.includes('canterbury'));

test('seam masks request WebP and use it directly when available',async()=>{
 const {gap,calls,given}=setup(url=>url.includes('.webp')&&url.includes('canterbury')?ok([7,7]):ok([1]));
 const r=await gap.fetch(tile,{});assert.equal(r.status,200);
 assert.deepEqual(masks(calls),[`${L}/v1/tiles/canterbury-2015-2016-0.3m/WebMercatorQuad/15/32000/20000.webp?api=k`]);
 assert.deepEqual([...new Uint8Array(given().fine[0].data)],[7,7]);assert.equal(given().fine[0].z,15);
 assert.equal(gap.stats().gapMaskPngFallbacks,0);
});

test('an unavailable or failed WebP mask falls back to the original PNG',async()=>{
 for(const failure of ['status','network']){
  const {gap,calls,given}=setup(url=>{if(url.includes('canterbury')&&url.includes('.webp')){if(failure==='network')throw new TypeError('network');return new Response(null,{status:503});}return url.includes('canterbury')?ok([5]):ok([1]);});
  await gap.fetch(tile,{});
  assert.deepEqual(masks(calls),[`${L}/v1/tiles/canterbury-2015-2016-0.3m/WebMercatorQuad/15/32000/20000.webp?api=k`,`${L}/v1/tiles/canterbury-2015-2016-0.3m/WebMercatorQuad/15/32000/20000.png?api=k`],failure);
  assert.deepEqual([...new Uint8Array(given().fine[0].data)],[5]);assert.equal(gap.stats().gapMaskPngFallbacks,1);
 }
});

test('a cancelled mask request never starts a PNG fallback',async()=>{
 const controller=new AbortController();
 const {gap,calls}=setup(url=>{if(url.includes('canterbury')){controller.abort();throw Object.assign(Error('Cancelled'),{name:'AbortError'});}return ok([1]);});
 await assert.rejects(gap.fetch(tile,{signal:controller.signal}),e=>e.name==='AbortError');
 assert.equal(masks(calls).length,1);assert.equal(gap.stats().gapMaskPngFallbacks,0);
});
