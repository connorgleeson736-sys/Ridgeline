const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),{execFileSync}=require('node:child_process'),{performance}=require('node:perf_hooks');
const baselineCommit='f44153a2a551955524f229ed83801d310ebb33e7';
const read=(name,old=false)=>old?execFileSync('git',['show',baselineCommit+':public/'+name],{encoding:'utf8'}):fs.readFileSync('public/'+name,'utf8');
function load(name,old=false,globals={}){const module={exports:{}};globals={RidgeTileRetry:require('../public/tile-retry.js'),...globals};vm.runInNewContext(read(name,old),{module,performance,...globals});return module.exports;}
(async()=>{
 const E=await import('../public/assets/maplibre-gl.mjs'),S=require('../public/terrain-streaming.js');
 // Actual native RTT preparation; no browser/GPU/FPS claims. Repeated complete
 // frames should not rebuild the source coverage hierarchy.
 function renderer(old){let builds=0,demReads=0;const C=load('native-residency.js',old,{RidgeTerrainStreaming:S}),painter={acquireRTT:size=>({size,texture:{}}),releaseRTT(){}},sourceManager={_source:{tileSize:256,type:'raster-dem'},map:{painter},reload(){},update(){}};
  const map={terrain:null,style:{_checkLoaded(){},off(){},on(){},tileManagers:{terrain:sourceManager},_layers:{},triggerSymbolPlacement(){}},painter,_camera:{setTerrain(){}},fire(){}};
  E.Map.prototype.setTerrain.call(map,{source:'terrain',exaggeration:1});const m=map.terrain.tileManager;m.getSourceTile=()=>{demReads++;return{dem:{}};};
  const wanted={z:3,x:4,y:4},provider={getCenter:t=>t.center,getWrap:()=>0,allowVariableZoom:()=>false,allowWorldCopies:()=>false,distanceToTile2d:()=>0,getTileBoundingVolume({z,x,y}){const shift=wanted.z-z,visible=shift>=0?x===Math.floor(wanted.x/2**shift)&&y===Math.floor(wanted.y/2**shift):Math.floor(x/2**-shift)===wanted.x&&Math.floor(y/2**-shift)===wanted.y;return{intersectsFrustum:()=>visible?(z>=wanted.z?2:1):0};}};
  const t={center:new E.LngLat(0,0),elevation:100,pitch:58,bearing:0,cameraToCenterDistance:500,worldSize:4096,tileSize:512,maxZoom:19,zoom:6,fov:36.87,renderWorldCopies:false,getCameraFrustum:()=>({}),getClippingPlane:()=>null,getCoveringTilesDetailsProvider:()=>provider};
  const cache=C.retainDrapes(m,painter,{readiness:()=>{builds++;return S.imageReadiness(map);}});m.update(t,map.terrain);const tiles=m.getRenderableTiles(),ids=tiles.map(t=>t.tileID);
  map.getZoom=()=>6;const photo={getSource:()=>({type:'raster'}),getState:()=>({revision:0}),getVisibleCoordinates:()=>ids,getTile:()=>({texture:{}})};
  map.style={_order:['photo'],_layers:{photo:{id:'photo',type:'raster',source:'linz',isHidden:()=>false}},tileManagers:{linz:photo}};
  const prepare=()=>painter.renderToTexture.prepareForRender(map.style,6,false);prepare();
  for(const tile of tiles){tile.acquireRTT(painter,0,512);cache.recordImageCoverage(tile,()=>true);tile.rttFingerprint.linz=painter.renderToTexture._rttFingerprints.linz[tile.tileID.key];}
  for(let i=0;i<20;i++)prepare();builds=0;const samples=[];
  for(let sample=0;sample<7;sample++){const start=performance.now();for(let i=0;i<100;i++)prepare();samples.push((performance.now()-start)/100);}
  const sorted=samples.slice().sort((a,b)=>a-b),coverageBuilds=builds,updates=[];demReads=0;for(let sample=0;sample<7;sample++){const start=performance.now();for(let i=0;i<100;i++)m.update(t,map.terrain);updates.push((performance.now()-start)/100);}const result={terrainTiles:tiles.length,completeFrames:700,coverageBuilds,medianPreparationMs:sorted[3],samplesMs:samples,stableUpdateDEMReads:demReads,medianUpdateMs:updates.slice().sort((a,b)=>a-b)[3],updateSamplesMs:updates};cache.destroy();m.destruct();return result;
 }
 const runs=[];for(let i=0;i<3;i++){const order=i%2?[false,true]:[true,false];const row={};for(const old of order)row[old?'baseline':'current']=renderer(old);runs.push(row);}const report={baselineCommit,runs,caveat:'Actual native CPU preparation, controlled GPU; alternated warmed variants. Not browser FPS.'};fs.writeFileSync(process.env.AUDIT_CPU_REPORT||'AUDIT_CPU_VALIDATION.json',JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
})().catch(e=>{console.error(e);process.exitCode=1});
