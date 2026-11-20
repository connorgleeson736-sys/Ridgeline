import fs from 'node:fs';import os from 'node:os';import path from 'node:path';import {pathToFileURL} from 'node:url';import {execFileSync} from 'node:child_process';import assert from 'node:assert/strict';import {performance} from 'node:perf_hooks';
if(!process.argv[2])throw Error('Usage: node checks/dem-parent-keys.mjs PERFORMANCE_017_FULL_APK [RESULT_JSON]');
const temporary=fs.mkdtempSync(path.join(os.tmpdir(),'ridge-dem-key-'));
try{
 const file=path.join(temporary,'baseline.mjs');fs.writeFileSync(file,execFileSync('unzip',['-p',path.resolve(process.argv[2]),'assets/public/assets/maplibre-gl.mjs'],{maxBuffer:8*1048576}));
 const engines=[await import(pathToFileURL(file)),await import(new URL('../'+(process.env.RIDGE_PERFORMANCE_SOURCE||'public')+'/assets/maplibre-gl.mjs',import.meta.url))];
 function manager(E){const source={_source:{tileSize:256,type:'raster-dem'},map:{painter:{}},reload(){},update(){}};const map={terrain:null,style:{_checkLoaded(){},off(){},on(){},tileManagers:{terrain:source},_layers:{},triggerSymbolPlacement(){}},painter:{},_camera:{setTerrain(){}},fire(){}};E.Map.prototype.setTerrain.call(map,{source:'terrain',exaggeration:1});const m=map.terrain.tileManager;
  let tr;E.Style.prototype._setProjectionInternal.call({map:{migrateProjection:t=>tr=t},tileManagers:{},_globalState:{}},'mercator');tr.resize(390,844);tr.setZoom(12);tr.setCenter(new E.LngLat(170,-43));m.update(tr);return{m,ID:m.getRenderableTiles()[0].tileID.constructor};}
 const native=engines.map(manager);let seed=7157;const rand=n=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed%n;};let comparisons=0;
 for(let trial=0;trial<2500;trial++){
  const z=rand(23),over=rand(4),wrap=rand(7)-3,x=rand(2**z),y=rand(2**z),delta=rand(4),min=rand(6),max=min+rand(18),fallback=!!rand(2),rows=new Map();
  const sample=new native[0].ID(z+over,wrap,z,x,y);
  for(let level=0;level<=sample.overscaledZ;level++){
   assert.equal(sample.calculateScaledKey(level,true),sample.scaledTo(level).key);
   if(rand(3)===0)rows.set(sample.scaledTo(level).key,{dem:rand(2)?{}:null,marker:level});
  }
  const lookups=[[],[]];const results=native.map(({m,ID},i)=>{m._sourceTileCache={};m.deltaZoom=delta;m.tileManager._source.minzoom=min;m.tileManager._source.maxzoom=max;m.findTileInCaches=key=>{lookups[i].push(key);return rows.get(key);};return m.getSourceTile(new ID(z+over,wrap,z,x,y),fallback);});
  assert.equal(results[1],results[0]);assert.deepEqual(lookups[1],lookups[0]);assert.deepEqual(native[1].m._sourceTileCache,native[0].m._sourceTileCache);comparisons++;
 }
 const runs=native.map(({m,ID})=>{m._sourceTileCache={};m.deltaZoom=1;m.tileManager._source.minzoom=0;m.tileManager._source.maxzoom=18;const tile={dem:{min:0,max:4000}};m.findTileInCaches=key=>key==='000'?tile:undefined;const ids=Array.from({length:512},(_,i)=>new ID(15,0,15,31000+i%16,20000+(i>>4)));return()=>{for(const id of ids)m.getSourceTile(id,true);};});
 // Count the exact allocations removed; source lookup/cache touches are retained.
 const allocations=[];for(let i=0;i<2;i++){const prototype=native[i].ID.prototype,old=prototype.scaledTo;let count=0;prototype.scaledTo=function(...args){count++;return old.apply(this,args);};try{runs[i]();allocations.push(count);}finally{prototype.scaledTo=old;}}
 assert.ok(allocations[0]>0);assert.equal(allocations[1],0);
 for(let n=0;n<30;n++){runs[0]();runs[1]();}const times=[[],[]];
 for(let round=0;round<9;round++)for(const i of round%2?[1,0]:[0,1]){const start=performance.now();for(let n=0;n<30;n++)runs[i]();times[i].push((performance.now()-start)/30);}
 const median=a=>a.slice().sort((a,b)=>a-b)[4];const report={reference:'android-preview-download / 0.17.0 Full',scope:'Sparse native DEM ancestor lookup only, warmed cloud CPU; not whole-app performance',exactLookupCases:comparisons,identicalLookupOrder:true,temporaryTileIdsPer512Queries:allocations,beforeMs:median(times[0]),afterMs:median(times[1]),samplesMs:times};report.speedRatio=report.beforeMs/report.afterMs;
 if(process.argv[3])fs.writeFileSync(process.argv[3],JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report,null,2));
}finally{fs.rmSync(temporary,{recursive:true,force:true});}
