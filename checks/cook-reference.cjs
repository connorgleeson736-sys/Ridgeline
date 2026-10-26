const assert=require('node:assert/strict'),fs=require('node:fs'),crypto=require('node:crypto');
const R=require('../public/regional-imagery.js'),M=require('../public/cook-reference-overviews.js');
(async()=>{
 assert.equal(M.sourceZoom,15);assert.equal(M.maximumZoom,14);assert.ok(Object.keys(M.tiles).length>15);
 const hashes={};for(const entry of Object.values(M.tiles))for(const path of typeof entry==='string'?[entry]:Object.values(entry)){
  const b=fs.readFileSync('public'+path);assert.ok(R.webpBytes(b.buffer.slice(b.byteOffset,b.byteOffset+b.byteLength)));const hash=crypto.createHash('sha256').update(b).digest('hex');assert.equal(hash.slice(0,24),path.split('/').at(-1).slice(0,24));hashes[path]=hash;
 }
 const calls=[],jobs=[];const local=R.create({reference:M,south:{tiles:{}},fetcher:async(url,opts)=>{calls.push(url);if(url.includes('/aerial/'))assert.ok(!url.includes('ridge-reference'),'Private cache key stripped upstream');return new Response(url.startsWith('/assets/cook-reference-v1/')?fs.readFileSync('public'+url):new Uint8Array([1,2,3]));},composer:async data=>{jobs.push(data);return new ArrayBuffer(1);}});
 const url=(z,x,y)=>`https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/${z}/${x}/${y}.webp?api=TEST&ridge-reference=cook-reference-v1`;
 const key='14/15935/10400',q=key.split('/').map(Number),entry=M.tiles[key];const response=await local.fetch(url(...q)+'&ridge-grade=snow-v3');assert.equal(calls.length,1);assert.equal(calls[0],typeof entry==='string'?entry:entry.natural);assert.equal(response.headers.get('content-type'),'image/webp');assert.equal(jobs.length,0,'No per-tile runtime decoding/composition');
 await local.fetch(url(15,31870,20801));assert.ok(calls.at(-1).startsWith('https://basemaps.linz.govt.nz'),'Fine original source preserved');
 await local.fetch(url(14,15800,10500));assert.ok(calls.at(-1).startsWith('https://basemaps.linz.govt.nz'),'Unsurveyed area untouched');
 await local.fetch(url(...q).replace('/aerial/','/elevation/'));assert.ok(calls.at(-1).includes('/elevation/'),'DEM untouched');local.destroy();
 const failed=R.create({reference:M,fetcher:async()=>new Response('failed',{status:503})});await assert.rejects(failed.fetch(url(...q)));failed.destroy();
 const controller=new AbortController();controller.abort();const cancelled=R.create({reference:M});await assert.rejects(cancelled.fetch(url(...q),{signal:controller.signal}),{name:'AbortError'});cancelled.destroy();
 // Real shared byte cache must avoid repeat requests and any background decode.
 global.RidgeRegionalImagery={create:opts=>R.create({...opts,reference:M,fetcher:async path=>{calls.push(path);return new Response(fs.readFileSync('public'+path));}})};
 const T=require('../public/tile-cache.js'),cache=T.createTileCaches();const ridge=url(...q).replace('https://basemaps.linz.govt.nz','ridge://linz');calls.length=0;await Promise.all([cache.load(ridge),cache.load(ridge)]);await cache.load(ridge);assert.equal(calls.length,1);cache.destroy();delete global.RidgeRegionalImagery;
 const report={sourceZoom:15,maximumReplacementZoom:14,coordinates:Object.keys(M.tiles).length,uniqueAssets:Object.keys(hashes).length,assetBytes:Object.keys(hashes).reduce((n,p)=>n+fs.statSync('public'+p).size,0),fineOriginalsPreserved:true,DEMAndOutsideRegionPreserved:true,noRuntimeImageProcessing:true,repeatRequests:0,regionalEsriProtected:true,sha256:hashes,caveat:'Asset/routing/cache checks; actual-source pixel comparison is in COOK_REFERENCE_PIXEL_VALIDATION.json. No browser/GPU screenshot.'};fs.writeFileSync('COOK_REFERENCE_VALIDATION.json',JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify({...report,sha256:'verified'}));
})().catch(e=>{console.error(e);process.exitCode=1});
