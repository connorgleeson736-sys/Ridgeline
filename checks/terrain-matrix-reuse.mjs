import {createRequire} from 'node:module';import fs from 'node:fs';import os from 'node:os';import path from 'node:path';import {pathToFileURL} from 'node:url';import {execFileSync} from 'node:child_process';import assert from 'node:assert/strict';import {performance} from 'node:perf_hooks';
if(!process.argv[2])throw Error('Usage: node checks/terrain-matrix-reuse.mjs PERFORMANCE_017_FULL_APK [RESULT_JSON]');
const temporary=fs.mkdtempSync(path.join(os.tmpdir(),'ridge-matrix-'));
try{
 const beforeFile=path.join(temporary,'baseline.mjs');fs.writeFileSync(beforeFile,execFileSync('unzip',['-p',path.resolve(process.argv[2]),'assets/public/assets/maplibre-gl.mjs'],{maxBuffer:8*1048576}));
 const currentFile=path.join(temporary,'current.mjs');fs.writeFileSync(currentFile,fs.readFileSync((process.env.RIDGE_PERFORMANCE_SOURCE||'public')+'/assets/maplibre-gl.mjs','utf8')+'\nexport {_ridgeRegularMatrixCache as matrixCache};');
 const engines=[await import(pathToFileURL(beforeFile)),await import(pathToFileURL(currentFile))];
 function manager(E){const source={_source:{tileSize:256,type:'raster-dem'},map:{painter:{}},reload(){},update(){}};const map={terrain:null,style:{_checkLoaded(){},off(){},on(){},tileManagers:{terrain:source},_layers:{},triggerSymbolPlacement(){}},painter:{},_camera:{setTerrain(){}},fire(){}};E.Map.prototype.setTerrain.call(map,{source:'terrain',exaggeration:1});const m=map.terrain.tileManager;
  let tr;E.Style.prototype._setProjectionInternal.call({map:{migrateProjection:t=>tr=t},tileManagers:{},_globalState:{}},'mercator');tr.resize(390,844);tr.setZoom(12);tr.setCenter(new E.LngLat(170,-43));m.update(tr);return{m,ID:m.getRenderableTiles()[0].tileID.constructor};}
 const native=engines.map(manager);let seed=157;const rand=n=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed%n;};let comparisons=0;
 const plain=r=>Object.fromEntries(Object.entries(r).map(([key,id])=>[key,{id:[id.key,id.wrap,id.overscaledZ,id.canonical.z,id.canonical.x,id.canonical.y],matrix:[...id.terrainRttPosMatrix32f]}]));
 for(let trial=0;trial<600;trial++){
  const z=1+rand(19),x=rand(2**z),y=rand(2**z),wrap=rand(5)-2,over=rand(4),dz=rand(Math.min(z,10)),ancestor=!!rand(2),specs=[];
  for(let j=0;j<12;j++){const qz=ancestor?z-dz:Math.min(22,z+dz),qx=ancestor?x>>dz:x*2**(qz-z)+rand(2**(qz-z)),qy=ancestor?y>>dz:y*2**(qz-z)+rand(2**(qz-z));specs.push([qz+rand(3),rand(5)-2,qz,qx,qy]);}
  const results=native.map(({m,ID})=>{const tiles=specs.map(s=>new ID(...s));m._tiles=Object.fromEntries(tiles.map(tileID=>[tileID.key,{tileID}]));m._renderableTilesKeys=tiles.map(t=>t.key);return m._getTerrainCoordsForRegularTile(new ID(z+over,wrap,z,x,y));});assert.deepEqual(plain(results[1]),plain(results[0]));comparisons++;
  // No cached matrix or ID may be exposed to a caller that can mutate it.
  for(const id of Object.values(results[1])){id.terrainRttPosMatrix32f.fill(999);id.wrap=99;}
  const {m,ID}=native[1];assert.deepEqual(plain(m._getTerrainCoordsForRegularTile(new ID(z+over,wrap,z,x,y))),plain(results[0]));
 }
 assert.ok(engines[1].matrixCache.size<=128);for(const a of engines[1].matrixCache.values())assert.equal(a.byteLength,64);
 const {relatedCoordinates}=createRequire(import.meta.url)('../public/native-residency.js');
 const run=native.map(({m,ID})=>{const tileIDs=Array.from({length:64},(_,i)=>new ID(12,0,12,4000+i%8,2700+(i>>3)));m._tiles=Object.fromEntries(tileIDs.map(tileID=>[tileID.key,{tileID}]));m._renderableTilesKeys=tileIDs.map(id=>id.key);const images=Array.from({length:16},(_,i)=>new ID(11,0,11,2000+i%4,1350+(i>>2)));const scope={};return()=>{let sum=0;for(const id of images)sum+=Object.keys(relatedCoordinates(m,m._getTerrainCoordsForRegularTile,id,scope)).length;return sum;};});
 assert.equal(run[0](),run[1]());for(let i=0;i<50;i++){run[0]();run[1]();}
 const times=[[],[]];for(let round=0;round<9;round++)for(const i of round%2?[1,0]:[0,1]){const start=performance.now();for(let n=0;n<100;n++)run[i]();times[i].push((performance.now()-start)/100);}
 const median=a=>a.slice().sort((a,b)=>a-b)[4];const report={reference:'android-preview-download / 0.17.0 Full',scope:'Native regular projection with the existing geographic prefilter in both versions, warmed cloud CPU; not phone FPS',exactMatrixCases:comparisons,independentOutputOwnership:true,maxCachedMatrices:128,maxMatrixPayloadBytes:8192,beforeMs:median(times[0]),afterMs:median(times[1]),samplesMs:times};report.speedRatio=report.beforeMs/report.afterMs;
 if(process.argv[3])fs.writeFileSync(process.argv[3],JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report,null,2));
}finally{fs.rmSync(temporary,{recursive:true,force:true});}
