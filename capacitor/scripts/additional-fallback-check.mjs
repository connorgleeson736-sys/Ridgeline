import fs from 'node:fs';import vm from 'node:vm';import assert from 'node:assert/strict';import {pathToFileURL} from 'node:url';
globalThis.window={requestAnimationFrame:fn=>setTimeout(fn,0),cancelAnimationFrame:clearTimeout};globalThis.self=globalThis;
globalThis.HTMLImageElement=class{};globalThis.ImageBitmap=class{width=256;height=256;close(){this.width=0;}};globalThis.createImageBitmap=async()=>new ImageBitmap();
const root=new URL('../www/',import.meta.url);
for(const name of ['image-result.js'])vm.runInThisContext(fs.readFileSync(new URL('../mobile/'+name,import.meta.url),'utf8'));
for(const name of ['tile-cache.js','tile-retry.js','native-residency.js','native-transport.js'])vm.runInThisContext(fs.readFileSync(new URL(name,root),'utf8'));
const E=await import(new URL('assets/maplibre-gl.mjs',root));
let fine=false,transfers=0,uploads=0,now=0,serial=0,recovery;
const timers=new Map(),transport=RidgeNativeTransport.install({send(){},fetcher:async()=>{
 transfers++;return new Response(new Uint8Array([1,2,3]),{headers:fine?{'Cache-Control':'max-age=60'}:{'Cache-Control':'no-store','X-Ridge-Fallback':'12/1991/1288','X-Ridge-Fallback-Retry':'1','Retry-After':'10'}});
}});
const cache=RidgeTiles.createCache({fetcher:transport.fetch});
const requested={z:13,x:3983,y:2576},tile={uid:1,tileID:{key:'fixture',canonical:{...requested,url:()=> 'auditfallback://tile'}},state:'loading'};
const source={tiles:[],scheme:'xyz',_premultiplyAlpha:true,map:{_refreshExpiredTiles:false,getPixelRatio:()=>1,_requestManager:{transformRequest:async url=>({url})},painter:{context:{gl:{RGBA:6408}},getTileTexture:()=>({update(){uploads++;}})}}};
const manager={_tiles:{fixture:tile},getTile:()=>tile,_reloadTile(key,state){assert.equal(key,'fixture');assert.ok(tile.texture);tile.state=state;recovery=E.RasterTileSource.prototype.loadTile.call(source,tile).then(()=>retry.data({sourceId:'linz',tile}));}};
const map={style:{tileManagers:{linz:manager}}};
const retry=RidgeTileRetry.create(map,RidgeResidency.retryFailedTile,{clock:()=>now,random:()=>.5,setTimer(fn,delay){timers.set(++serial,{fn,delay});return serial;},clearTimer(id){timers.delete(id);}});
E.addProtocol('auditfallback',(request,controller)=>cache.load('https://localhost/_ridge/linz/v1/tiles/aerial/WebMercatorQuad/13/3983/2576.webp',controller.signal,undefined,true,{original:true}));
try{
 await E.RasterTileSource.prototype.loadTile.call(source,tile);retry.data({sourceId:'linz',tile});
 assert.equal(tile.state,'loaded');assert.equal(tile._ridgeFallback.z,12);assert.equal(tile._ridgeFallback.x,1991);assert.equal(tile._ridgeFallback.retry,true);
 assert.equal(uploads,1);assert.equal(cache.stats().tiles,0);assert.equal(timers.size,1);assert.equal(retry.stats().retryRecords,1);
 retry.recover();assert.equal(timers.size,1);assert.ok([...timers.values()][0].delay>=10000,'Retry-After remains authoritative');
 fine=true;const [key,t]=timers.entries().next().value;timers.delete(key);now+=t.delay;t.fn();await recovery;
 assert.equal(tile.state,'loaded');assert.equal(tile._ridgeFallback,null);assert.equal(uploads,2);assert.equal(transfers,2);assert.equal(retry.stats().retryRecords,0);assert.equal(cache.stats().tiles,1);
 tile._ridgeFallback={z:12,x:1991,y:1288,retry:false};retry.data({sourceId:'linz',tile});assert.equal(timers.size,0,'Downloaded-only fallback does not poll online');
 tile._ridgeFallback.retry=true;retry.scan();assert.equal(timers.size,1,'A restored/cached active fallback is rediscovered');
 const before=uploads;map.style.tileManagers.linz={getTile:()=>null};const [old,wake]=timers.entries().next().value;timers.delete(old);wake.fn();assert.equal(uploads,before);assert.equal(retry.stats().retryRecords,0);
 console.log(JSON.stringify({parentProvenance:true,initialFallbackVisible:true,compressedFallbackEntries:0,visibleStationaryRecovery:true,uploads,transfers,sharpSuccessClearsRecord:true,retryAfterRespected:true,offlineOnlyDoesNotPoll:true,restoredFallbackRescanned:true,retiredSourceDoesNotReload:true},null,2));
}finally{retry.destroy();cache.clear();transport.destroy();E.removeProtocol('auditfallback');}
