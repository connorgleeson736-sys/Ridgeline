const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict'),R=require('../public/regional-imagery.js');
const env={URL,Response,Promise,ArrayBuffer,Uint8Array,RidgeRegionalImagery:R};vm.runInNewContext(fs.readFileSync('public/gap-imagery.js','utf8'),env);
const square=(x0,y0,x1,y1)=>[[[x0,y0],[x1,y0],[x1,y1],[x0,y1],[x0,y0]]],entry={z:13,x:5,y:6,width:256,height:256,vector:true,outer:square(0,0,256,256),seamRings:square(0,0,50,256),fineSources:[{name:'fine-survey',maxZoom:17}]},atlas={z:13,entries:{'5/6':entry},coarse:{}};
const url=(z,x,y)=>`https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/${z}/${x}/${y}.webp?api=TEST&ridge-gap=gap-v4`;
(async()=>{
 const calls=[],jobs=[];let failFine=false;
 const fetcher=async u=>{calls.push(u);return new Response(new Uint8Array([u.includes('/fine-survey/')?3:2]),{status:failFine&&u.includes('/fine-survey/')?503:200,headers:{'cache-control':'max-age=90'}});};
 const loader=env.RidgeGapImagery.create({atlas,fetcher,base:async u=>{calls.push('base:'+u);return new Response(new Uint8Array([1]));},compose:async job=>{jobs.push(job);return new Uint8Array([4]).buffer;}});
 const edge=url(19,320,384);await loader.fetch(edge);assert.equal(jobs.length,1);assert.equal(jobs[0].fine.length,1);assert.equal(jobs[0].fine[0].z,17,'Fine source must respect its native zoom ceiling');assert.ok(calls.some(u=>u.includes('/fine-survey/WebMercatorQuad/17/80/96.png?api=TEST')));assert.ok(calls.some(u=>u.includes('/World_Imagery/MapServer/tile/18/192/160')));assert.ok(calls.filter(u=>u.includes('services.arcgisonline.com')).every(u=>!u.includes('api=')));
 calls.length=0;jobs.length=0;await loader.fetch(url(18,5*32+24,6*32+10));assert.equal(calls.length,1);assert.equal(jobs.length,0);assert.ok(calls[0].includes('World_Imagery'),'Interior tiles retain one-request fast path without survey downloads');
 failFine=true;jobs.length=0;await assert.rejects(loader.fetch(edge),{status:503});assert.equal(jobs.length,0,'Missing fine alpha must never silently overwrite aerial imagery');
 loader.destroy();console.log('PASS seam routing: native alpha, per-source zoom ceiling, Esri crop, interior fast path and fail-safe survey failures');
})().catch(e=>{console.error(e);process.exitCode=1});
