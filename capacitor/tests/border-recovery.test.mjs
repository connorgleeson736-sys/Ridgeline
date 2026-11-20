import {test} from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
const generated=await readFile(new URL('../www/gap-imagery.js',import.meta.url),'utf8');
const retrySource=await readFile(new URL('../www/tile-retry.js',import.meta.url),'utf8');
test('a failed seam donor cannot become successful LINZ-only imagery; recovery composes all sources',async()=>{
 let fail=true,baseCalls=0,composed=0;const api={module:{exports:{}},URL,Response,Headers,Promise};api.globalThis=api;
 api.RidgeRegionalImagery={mode:()=> 'boundary',relation:()=>0};
 vm.runInNewContext(await readFile(new URL('../mobile/image-result.js',import.meta.url),'utf8'),api);
 vm.runInNewContext(generated,api);
 const atlas={z:13,entries:{'7966/5200':{x:7966,y:5200,z:13,outer:[],vector:true,seamRings:[[]],fineSources:[{name:'canterbury-2015-2016-0.3m',maxZoom:19}]}},coarse:{}};
 const gap=api.RidgeGapImagery.create({atlas,base:async()=>{baseCalls++;return new Response('original',{headers:{'Cache-Control':'max-age=60'}});},fetcher:async url=>{if(url.includes('canterbury-')){if(fail)throw new TypeError('Failed to fetch');return new Response('survey');}return new Response('esri');},compose:async data=>{composed++;assert.equal(new TextDecoder().decode(data.fine[0].data),'survey');return new TextEncoder().encode('complete border');}});
 const url='https://localhost/_ridge/linz/v1/tiles/aerial/WebMercatorQuad/17/127471/83202.webp?api=test';
 await assert.rejects(gap.fetch(url),/Failed to fetch/);assert.equal(composed,0);assert.equal(baseCalls,1);
 fail=false;assert.equal(await (await gap.fetch(url)).text(),'complete border');assert.equal(composed,1);assert.equal(baseCalls,2);gap.destroy();
});
test('stationary transient failures keep recovering; retired tiles, aborts and permanent errors stop',()=>{
 const ctx={module:{exports:{}}};ctx.globalThis=ctx;vm.runInNewContext(retrySource,ctx);
 const pending=new Map();let next=0,now=0,attempts=0;const id={key:'cook',canonical:{z:17,x:127471,y:83202}},tile={tileID:id,state:'errored'};
 let present=tile;const manager={getTile:()=>present},map={style:{tileManagers:{linz:manager}}};
 const retry=ctx.module.exports.create(map,()=>{attempts++;return true;},{clock:()=>now,random:()=>.5,setTimer(fn,delay){pending.set(++next,{fn,delay});return next;},clearTimer(id){pending.delete(id);}});
 const error=(status=503)=>retry.error({sourceId:'linz',tile,error:{status}});
 for(let i=0;i<5;i++){error();assert.equal(pending.size,1);const [k,t]=pending.entries().next().value;assert.ok(t.delay<=15000);pending.delete(k);now+=t.delay;t.fn();}
 assert.equal(attempts,5);tile.state='loaded';retry.data({sourceId:'linz',tile});assert.equal(retry.stats().retryRecords,0);
 tile.state='errored';error();present=null;const [k,t]=pending.entries().next().value;pending.delete(k);t.fn();assert.equal(attempts,5);assert.equal(retry.stats().retryRecords,0);
 present=tile;error(404);assert.equal(pending.size,0);retry.error({sourceId:'linz',tile,error:{name:'AbortError'}});assert.equal(pending.size,0);retry.destroy();
});
