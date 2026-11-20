import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
const require=createRequire(import.meta.url),root=path.resolve(process.argv[2]||'audit');
globalThis.window={requestAnimationFrame:fn=>setTimeout(fn,0),cancelAnimationFrame:clearTimeout};
globalThis.self=globalThis;
globalThis.HTMLImageElement=class{};
globalThis.ImageBitmap=class{width=256;height=256;close(){}};
globalThis.createImageBitmap=async()=>new ImageBitmap();
const E=await import(pathToFileURL(path.join(root,'public/assets/maplibre-gl.mjs')));
const map={_maxTileCacheSize:192,_maxTileCacheZoomLevels:8,_requestManager:{transformRequest:async url=>({url})},_refreshExpiredTiles:false};
const style=Object.assign(new E.Evented(),{map,tileManagers:{},dispatcher:{},_layers:{},_order:[],_checkLoaded(){},_validate:()=>false});map.style=style;style.on('error',()=>{});
E.Style.prototype.addSource.call(style,'photo',{type:'raster',tiles:['https://example.test/{z}/{x}/{y}'],tileSize:256,maxzoom:18});
await new Promise(r=>setTimeout(r,10));
const manager=style.tileManagers.photo,events=[];
const tile={tileID:{canonical:{z:13,x:7967,y:5152},key:'fixture'},state:'loaded'};
manager._tileLoaded.call({getSource:()=>({type:'raster'}),_setTileReloadTimer(){},_state:{initializeTileState(){}},_source:{fire:e=>events.push(e)}},tile,'fixture','loading',false,{});
assert.equal(events.length,1);assert.equal(events[0].sourceDataType,undefined);assert.equal(events[0].dataType,'source');
// Replay the map's own forwarding form with a deterministic frame clock.
let now=0;const env={performance:{now:()=>now},setTimeout:()=>1,clearTimeout(){}};
vm.runInNewContext(fs.readFileSync(path.join(root,'public/selection-interval.js'),'utf8'),env);
function selection(typed){let updates=0;const manager={update(){updates++;}},map=Object.assign(new E.Evented(),{style:{tileManagers:{photo:manager}},_render(){manager.update();},_update(){},triggerRepaint(){}});
 const control=env.RidgeSelectionInterval.attach(map,{interval:()=>100,engine:E});
 for(let i=0;i<60;i++){now=i*1000/60;const event=new E.MapSourceDataEvent('sourcedata',{...events[0],sourceId:'photo',...(typed?{sourceDataType:'content'}:{})});map.fire(event);map._render();}
 control.destroy();return updates;}
const ordinary=selection(false),content=selection(true);assert.ok(ordinary<12);assert.equal(content,60);
// A successful parent-crop response gets the requested child's native loaded
// state. Stub only the decoded bitmap/GPU upload, not RasterTileSource.loadTile.
let transfers=0,uploads=0,expiryCalls=0;
const Cache=require(path.join(root,'public/tile-cache.js'));
const cache=Cache.createCache({fetcher:async()=>{transfers++;return new Response(new Uint8Array(8),{headers:{'Content-Type':'image/png','Cache-Control':'no-store'}});}});
E.addProtocol('auditfallback',(request,controller)=>cache.load(request.url,controller.signal));
const fallbackTile={uid:1,tileID:{key:'fixture',canonical:{z:13,x:7967,y:5152,url:()=> 'auditfallback://tile'}},state:'loading',setExpiryData(){expiryCalls++;}};
const source={tiles:[],scheme:'xyz',_premultiplyAlpha:true,map:{_refreshExpiredTiles:false,getPixelRatio:()=>1,_requestManager:{transformRequest:async url=>({url})},painter:{context:{gl:{RGBA:6408}},getTileTexture:()=>({update(){uploads++;}})}}};
await E.RasterTileSource.prototype.loadTile.call(source,fallbackTile);assert.equal(fallbackTile.state,'loaded');assert.equal(expiryCalls,0);assert.equal(uploads,1);
const Retry=require(path.join(root,'public/tile-retry.js'));let retries=0;
const retry=Retry.create({style:{tileManagers:{linz:{getTile:()=>fallbackTile}}}},()=>{retries++;return true;});
retry.data({sourceId:'linz',tile:fallbackTile});retry.recover();
assert.equal(retries,0);assert.equal(retry.stats().retryRecords,0);retry.destroy();E.removeProtocol('auditfallback');
assert.equal(cache.stats().tiles,0);
console.log(JSON.stringify({ordinaryTileCompletionHasContentTag:false,selectionUpdatesPer60Frames:{ordinary,explicitContent:content},parentCrop:{state:fallbackTile.state,transfers,uploads,expiryCalls,compressedCacheEntries:cache.stats().tiles,retryRecords:0,retries}},null,2));
cache.clear();
