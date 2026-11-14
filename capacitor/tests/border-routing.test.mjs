import {test} from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url);
const regional=await readFile(new URL('../www/regional-imagery.js',import.meta.url),'utf8');
const gapSource=await readFile(new URL('../www/gap-imagery.js',import.meta.url),'utf8');
const square=(x0,y0,x1,y1)=>[[[x0,y0],[x1,y0],[x1,y1],[x0,y1],[x0,y0]]];
function context(){const c={module:{exports:{}},URL,Response,Headers,Promise,ArrayBuffer,Uint8Array,AbortController,setTimeout,clearTimeout,Worker:class{},RidgeRegionalScheduler:require('../../public/regional-scheduler.js')};c.globalThis=c;vm.runInNewContext(regional,c);return c;}
test('diagonal bounding boxes are not geographic boundary intersections',()=>{
 const r=context().module.exports;
 const triangle=[[[0,0],[256,256],[256,0],[0,0]]];
 assert.equal(r.relation(triangle,[10,200,20,210]),-1);
 assert.equal(r.relation(triangle,[200,10,210,20]),1);
 assert.equal(r.relation(triangle,[100,100,110,110]),0);
 assert.equal(r.relation([...square(0,0,256,256),...square(40,40,80,80)],[50,50,60,60]),-1,'real holes stay outside');
});
test('a wholly LINZ tile returns its original response without any donor or compositor dependency',async()=>{
 const c=context();c.RidgeRegionalImagery=c.module.exports;vm.runInNewContext(gapSource,c);
 const entry={z:13,x:5,y:6,width:256,height:256,vector:true,outer:square(0,0,50,256),seamRings:square(0,0,100,256),fineSources:[{name:'survey-0.3m',maxZoom:19}]};
 const original=new Response(new Uint8Array([7,8,9]),{headers:{'Content-Type':'image/webp','Cache-Control':'max-age=60'}});let bases=0;
 const loader=c.RidgeGapImagery.create({atlas:{z:13,entries:{'5/6':entry},coarse:{}},base:async()=>{bases++;return original;},fetcher:async()=>{throw Error('No Esri or survey input should be needed');},compose:async()=>{throw Error('No canvas operation should be needed');}});
 // At z19 this is entirely outside the mask, but inside the old z13 padding.
 const response=await loader.fetch('https://localhost/_ridge/linz/v1/tiles/aerial/WebMercatorQuad/19/334/384.webp?api=demo');
 loader.destroy();
 assert.equal(response,original);assert.equal(bases,1);assert.deepEqual([...new Uint8Array(await response.arrayBuffer())],[7,8,9]);
});
test('repeated worker composition failures retain transient status for stationary tile recovery',async()=>{
 const c=context();let failures=0;
 const worker={postMessage(job){if(job.id!==undefined){failures++;queueMicrotask(()=>this.onmessage({data:{id:job.id,error:'Regional imagery composition failed'}}));}},terminate(){}};
 const region=c.module.exports.create({meta:{z:13,x:0,y:0,width:256,height:256,outer:square(0,0,256,256),inner:square(30,30,220,220)},workerFactory:()=>worker,fetcher:async()=>new Response(new Uint8Array([1]))});
 try{for(let i=0;i<4;i++)await assert.rejects(region.fetch('https://localhost/_ridge/linz/v1/tiles/aerial/WebMercatorQuad/13/0/0.webp?api=demo'),e=>e.status===503);}finally{region.destroy();}
 assert.equal(failures,4);
});
