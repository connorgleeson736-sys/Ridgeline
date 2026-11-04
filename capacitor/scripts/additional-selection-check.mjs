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
const E=await import(pathToFileURL(path.join(root,'capacitor/www/assets/maplibre-gl.mjs')));
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
const engineText=fs.readFileSync(path.join(root,'capacitor/www/assets/maplibre-gl.mjs'),'utf8');
vm.runInNewContext(engineText.slice(engineText.indexOf('function ridgeIdealFor'),engineText.indexOf('const _ridgeRegularMatrixCache'))+fs.readFileSync(path.join(root,'capacitor/www/selection-interval.js'),'utf8'),env);
function selection(typed){let updates=0;const view={width:390,height:844,zoom:13,pitch:58,bearing:0,elevation:1200,center:{lng:170,lat:-43}},manager={_source:{tileSize:256,type:'raster'},used:true,update(){env.ridgeIdealFor(this,view,null,()=>{updates++;return [1,2];});}},map=Object.assign(new E.Evented(),{style:{tileManagers:{photo:manager}},_render(){manager.update();},_update(){},triggerRepaint(){}});
 const control=env.RidgeSelectionInterval.attach(map,{interval:()=>100,engine:E});
 for(let i=0;i<60;i++){now=i*1000/60;const event=new E.MapSourceDataEvent('sourcedata',{...events[0],sourceId:'photo',...(typed?{sourceDataType:'content'}:{})});map.fire(event);map._render();}
 control.destroy();return updates;}
const ordinary=selection(false),content=selection(true);assert.ok(ordinary<12);assert.equal(content,60);
console.log(JSON.stringify({ordinaryTileCompletionHasContentTag:false,ordinarySelectionUpdates:ordinary,explicitContentSelectionUpdates:content,frames:60},null,2));
