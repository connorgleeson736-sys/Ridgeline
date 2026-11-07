import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';
import {performance} from 'node:perf_hooks';
const baselineApk=process.argv[2];
if(!baselineApk)throw Error('Usage: node checks/lod-invariants.mjs PERFORMANCE_017_FULL_APK [RESULT_JSON]');
const directory=fs.mkdtempSync(path.join(os.tmpdir(),'ridge-lod-'));
try{
 const file=path.join(directory,'baseline.mjs');
 fs.writeFileSync(file,execFileSync('unzip',['-p',path.resolve(baselineApk),'assets/public/assets/maplibre-gl.mjs'],{maxBuffer:8*1048576}));
 const engines=[await import(pathToFileURL(file)),await import(new URL('../'+(process.env.RIDGE_PERFORMANCE_SOURCE||'public')+'/assets/maplibre-gl.mjs',import.meta.url))];
 const policy=(E,levels,ratio)=>{const source={};E.Map.prototype.setSourceTileLodParams.call({getSource:()=>source,_update(){}},levels,ratio,'test');return source.calculateTileZoom;};
 let seed=0x183746;const random=()=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed/4294967296;};
 let comparisons=0;
 for(const [levels,ratio]of [[1,1],[6,3.5],[8,3.5],[10.3,2.75],[12,2],[1.001,1],[20,6]]){
  const functions=engines.map(E=>policy(E,levels,ratio));
  for(let batch=0;batch<100;batch++){
   const z=random()*.004,a=z+random()*.01,fov=10+random()*110;
   for(let tile=0;tile<80;tile++){
    const args=[random()*23,random()*.015,z,a,fov];
    assert.equal(functions[1](...args),functions[0](...args));comparisons++;
   }
  }
  const edge=[0,-0,1e-30,Infinity,-Infinity,NaN];
  for(const z of edge)for(const range of edge)for(const fov of [0,-0,36.86989764584402,90,180,NaN]){
   const args=[13,.0008,z,range,fov];assert.equal(functions[1](...args),functions[0](...args));comparisons++;
  }
 }
 const transforms=engines.map(E=>{let tr;E.Style.prototype._setProjectionInternal.call({map:{migrateProjection:t=>tr=t},tileManagers:{},_globalState:{}},'mercator');return tr;});
 const scenes=[];
 for(const viewport of [[390,844],[844,390]])for(const pitch of [0,45,58,75])for(const zoom of [9,12,15,17])for(const levels of [6,10.3]){
  const ids=engines.map((E,i)=>{const tr=transforms[i];tr.resize(...viewport);tr.setPitch(pitch);tr.setZoom(zoom);tr.setBearing(-24);tr.setCenter(new E.LngLat(170.141,-43.595));tr.setElevation(2300);
   return E.Map.prototype.coveringTiles.call({_camera:{transform:tr}},{tileSize:431,maxzoom:19,terrain:{getMinMaxElevation:()=>({minElevation:-100,maxElevation:4000})},calculateTileZoom:policy(E,levels,3.5)}).map(id=>[id.key,id.overscaledZ,id.wrap,id.canonical.z,id.canonical.x,id.canonical.y]);});
  assert.deepEqual(ids[1],ids[0]);scenes.push({viewport,pitch,zoom,levels,tiles:ids[0].length});
 }
 // Alternate warmed batches. The inputs model one selection pass, where camera
 // quantities are constant and distanceXY varies. This is not a phone FPS test.
 const times=[[],[]],functions=engines.map(E=>policy(E,6,3.5)),args=Array.from({length:4096},(_,i)=>[13,.0002+i*.000001,.0004,.002,36.86989764584402]);
 let checksum=0;const run=i=>{for(const row of args)checksum+=functions[i](...row);};
 for(let n=0;n<30;n++){run(0);run(1);}
 for(let round=0;round<9;round++)for(const i of round%2?[1,0]:[0,1]){const start=performance.now();for(let n=0;n<30;n++)run(i);times[i].push((performance.now()-start)/30);}
 const median=a=>a.slice().sort((a,b)=>a-b)[4];
 const report={reference:'android-preview-download / 0.17.0 Full',scope:'Native LOD arithmetic only, warmed cloud CPU; not whole-app performance',exactPolicyComparisons:comparisons,exactOrderedCoverScenes:scenes.length,totalSelectedTiles:scenes.reduce((n,s)=>n+s.tiles,0),batchTiles:args.length,beforeMs:median(times[0]),afterMs:median(times[1]),samplesMs:times,checksum};report.speedRatio=report.beforeMs/report.afterMs;
 if(process.argv[3])fs.writeFileSync(process.argv[3],JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report,null,2));
}finally{fs.rmSync(directory,{recursive:true,force:true});}
