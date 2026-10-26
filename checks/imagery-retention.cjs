const assert=require('node:assert/strict'),fs=require('node:fs');
const {createTileCaches,createCache,networkURL}=require('../public/tile-cache.js');
(async()=>{
 let downloads=0;const cache=createTileCaches({fetcher:async()=>{downloads++;return new Response(new Uint8Array(32),{headers:{'cache-control':'max-age=3600'}});}});
 const aerial=i=>`ridge://linz/v1/tiles/aerial/WebMercatorQuad/15/${i}/11000.webp?api=test`;
 const satellite=i=>`ridge://esri/imagery/15/11000/${i}`;
 const topo=i=>`ridge://esri/topo/15/11000/${i}`;
 const dem=i=>`ridge://linz/v1/tiles/elevation/WebMercatorQuad/15/${i}/11000.png?pipeline=terrain-rgb&api=test`;
 // Load 100 imagery tiles, look away while 600 DEM tiles load, then return.
 for(let i=0;i<100;i++)await cache.load(aerial(i));
 for(let i=0;i<600;i++)await cache.load(dem(i));
 let start=downloads;for(let i=0;i<100;i++)await cache.load(aerial(i));assert.equal(downloads-start,0,'Terrain churn cannot evict imagery');
 for(let i=0;i<100;i++)await cache.load(satellite(i));
 for(let i=0;i<100;i++)await cache.load(topo(i));
 start=downloads;for(let i=0;i<100;i++){await cache.load(satellite(i));await cache.load(topo(i));}assert.equal(downloads-start,0,'Both Esri layers must reuse off-screen imagery');
 assert.ok(networkURL(satellite(3)).endsWith('/World_Imagery/MapServer/tile/15/11000/3'));assert.ok(networkURL(topo(3)).includes('/World_Topo_Map/'));assert.throws(()=>networkURL('ridge://esri/imagery/1/2/3?url=https://example.com'));assert.throws(()=>networkURL('ridge://esri/other/1/2/3'));
 // Saturation/contrast edits operate in the existing shader, keeping fine image
 // pixels and geographic locations intact. Source maximum zooms are unchanged.
 let attempts=0;const retry=createCache({fetcher:async()=>++attempts===1?new Response('',{status:503}):new Response('tile')});assert.equal((await retry.load('retry')).data.byteLength,4);assert.equal(attempts,2);
 let limitedAttempts=0;const limited=createCache({fetcher:async()=>{limitedAttempts++;return new Response('',{status:429,headers:{'retry-after':'60'}});}});await assert.rejects(limited.load('limited'),e=>e.status===429);assert.equal(limitedAttempts,1,'Do not retry before a server rate-limit window');
 let authAttempts=0;const auth=createCache({fetcher:async()=>{authAttempts++;return new Response('',{status:403});}});await assert.rejects(auth.load('auth'),e=>e.status===403);assert.equal(authAttempts,1);
 // Exercise the actual native raster loader for each imagery provider. This
 // also guards the custom-protocol image path when refreshExpiredTiles is off.
 const {RasterTileSource,addProtocol,removeProtocol}=await import('../public/assets/maplibre-gl.mjs');
 global.self=global;global.HTMLImageElement=class{};global.ImageBitmap=class{constructor(){this.width=256;this.height=256;}};
 let decodes=0,uploads=0;global.createImageBitmap=async blob=>{assert.equal((await blob.arrayBuffer()).byteLength,32);decodes++;return new ImageBitmap();};
 addProtocol('ridge',(request,controller)=>cache.load(request.url,controller.signal));
 for(const url of [aerial(0),satellite(0),topo(0)]){
  const source=new RasterTileSource('imagery',{type:'raster',tileSize:256},null,null);source.tiles=[url];source.map={getPixelRatio:()=>1,_refreshExpiredTiles:false,_requestManager:{transformRequest:async url=>({url})},painter:{context:{gl:{}},getTileTexture:()=>({update(img,options){assert.equal(img.width,256);assert.equal(options.useMipmap,true);uploads++;}})}};
  const tile={state:'loading',tileID:{canonical:{url:()=>url}}};await source.loadTile(tile);assert.equal(tile.state,'loaded');
 }
 removeProtocol('ridge');assert.equal(decodes,3);assert.equal(uploads,3);
 const stats=cache.stats();assert.equal(stats.imagery.tiles,300);assert.equal(stats.terrain.tiles,512);assert.ok(stats.terrain.evictions>0);
 cache.clear();retry.clear();auth.clear();limited.clear();
 const result={scope:'Original',providers:['LINZ aerial','Esri imagery','Esri topo'],imageryTilesRetained:300,terrainLoadsWhileLookingAway:600,imageryRevisitDownloads:0,terrainEvictionsDoNotEvictImagery:true,transientRetry:'passed',authFailureNotRetried:'passed',nativeRasterProtocolHandoff:'passed',caveat:'Controlled source-fetch fixture; does not assert permanent GPU residency or browser FPS'};
 fs.writeFileSync('IMAGERY_RETENTION_VALIDATION.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
})().catch(e=>{console.error(e);process.exitCode=1});
