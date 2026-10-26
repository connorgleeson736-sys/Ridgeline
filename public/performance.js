/* Native terrain, bounded work, stable drawing buffers during ordinary gestures. */
(function(root){
'use strict';
function pixelRatio({dpr=1,width=1200,height=800,zoom=12,sharp=zoom>=15,lowPower=false,phoneMode=false}){
 if(lowPower)return 1;
 const native=Math.max(1,Math.min(dpr,2)),budget=sharp?6000000:2800000;
 return Math.max(1,Math.min(native,phoneMode?1.25:sharp?2:1.5,Math.sqrt(budget/Math.max(1,width*height))));
}
function cacheSize({memory=8,saveData=false}={}){return saveData?80:memory<=2?96:memory<=4?128:192;}
function tileOptions(options={}){return{maxTileCacheSize:cacheSize(options),maxTileCacheZoomLevels:8,cancelPendingTileRequestsWhileZooming:false,centerClampedToGround:true,terrainSkirtLength:'auto'};}
function lodParams(lowPower=false,phoneMode=false){return phoneMode?{levels:12,ratio:2}:{levels:lowPower?9:8,ratio:lowPower?3:3.5};}
function focusLod(native,bias=0,focus=()=>null,legacy=true){return function(zoom,distanceXY,distanceZ,range,fov){
 const requested=zoom+bias,result=native(zoom,distanceXY,distanceZ,range,fov),surface=focus();
 if(surface?.tile){
  const weights=root.RidgeSurfaceFocus.weights?.(surface)||{surface:root.RidgeSurfaceFocus.weight(surface),pivot:0};
  const target=requested+Math.max(0,Math.min(1,Math.log2(range/surface.range)));
  const local=result+bias*Math.max(weights.surface,weights.pivot);
  return local+Math.max(Math.max(0,target-local)*weights.surface,Math.max(0,requested-local)*weights.pivot);
 }
 if(!legacy)return result;
 // Keep native fine detail in the foreground and at the centre. Farther tiles
 // use the stronger distance decay, without a global source maxzoom reduction.
 const centreXY=Math.sqrt(Math.max(0,range*range-distanceZ*distanceZ));
 const t=Math.max(0,Math.min(1,(distanceXY-centreXY-range*.12)/Math.max(1e-12,range*.5))),weight=1-t*t*(3-2*t);
 const local=result+bias*weight;return local+Math.max(0,requested-local)*weight;
};}
// Shift LOD locally, fading to zero before distant terrain. Default 50 preserves
// the existing phone policy. This changes selected source/render tiles, not DEM values.
function normalizePhoneDetail(value){return Number.isFinite(value)?Math.max(0,Math.min(100,Math.round(value))):50;}
function nearbyLod(policy,detail=()=>50,focus=()=>null){return function(zoom,distanceXY,distanceZ,range,fov){
 const base=policy.call(this,zoom,distanceXY,distanceZ,range,fov),reference=focus()?.range||range;
 if(!Number.isFinite(reference)||reference<=0)return base;
 const distance=Math.hypot(distanceXY,distanceZ);if(!Number.isFinite(distance))return base;const t=Math.max(0,Math.min(1,(distance/reference-.75)/1.25)),weight=1-t*t*(3-2*t);
 return base+(normalizePhoneDetail(detail())-50)/50*.75*weight;
};}
function attach(map,{lowPower,adaptiveTerrain=()=>true,phoneMode=()=>false,phoneDetail=()=>50,imageryDetail=()=>.25,motionDetail=()=>0,onSettled=()=>{}}){
 let moving=false,ratio=map.getPixelRatio(),timer,updating=false,sharp=map.getZoom()>=15,
     memoryPressure=false,lastFrame=0,started=0,intervals=[],reduced=false,downshifts=0,disposed=false,phoneActive=!!phoneMode(),phoneCeiling=Infinity,pressureCeiling=Infinity,healthySince=0;
 const clock=()=>root.performance.now();
 const setRatio=next=>{if(disposed||updating||Math.abs(next-ratio)<.08)return;ratio=next;updating=true;try{map.setPixelRatio(next);}finally{updating=false;}lastFrame=0;};
 const apply=()=>{
  if(disposed||updating)return;
  const enabled=!!phoneMode();if(enabled!==phoneActive){phoneActive=enabled;phoneCeiling=Infinity;}
  const zoom=map.getZoom();if(zoom>=15.3)sharp=true;else if(zoom<14.7)sharp=false;
  const el=map.getContainer();setRatio(Math.max(.75,Math.min(pressureCeiling,phoneActive?phoneCeiling:Infinity,(memoryPressure?.75:1)*pixelRatio({dpr:root.devicePixelRatio||1,width:el.clientWidth,height:el.clientHeight,zoom,sharp,lowPower:lowPower(),phoneMode:phoneActive}))));
 };
 const surfaceFocus=root.RidgeSurfaceFocus?.attach(map,{phoneMode,phoneDetail,motionDetail});
 const grids=root.RidgeAdaptiveTerrain?.attach(map,{enabled:adaptiveTerrain});
 const installedLod=new Map();
 const installLod=id=>{const source=map.getSource(id);if(!source)return;const dem=id.startsWith('terrain')||id==='shade-dem',{levels,ratio}=dem?lodParams(lowPower(),phoneMode()):phoneMode()?{levels:10,ratio:2}:{levels:lowPower()?7:6,ratio:lowPower()?3:3.5},bias=dem?0:imageryDetail(),near=phoneMode()?normalizePhoneDetail(phoneDetail()):50,old=installedLod.get(id);if(old?.source===source&&old.levels===levels&&old.ratio===ratio&&old.bias===bias&&old.near===near)return;map.setSourceTileLodParams(levels,ratio,id);if(typeof source.calculateTileZoom==='function'){const policy=focusLod(source.calculateTileZoom,bias,()=>surfaceFocus?.state());const selected=phoneMode()?nearbyLod(policy,phoneDetail,()=>surfaceFocus?.state()):policy;source.calculateTileZoom=function(...args){return selected.apply(this,args)-Math.max(0,Math.min(2,motionDetail()));};}installedLod.set(id,{source,levels,ratio,bias,near});};
 const refresh=()=>{grids?.refresh();['linz','satellite','topo','terrain','terrain-fallback','shade-dem'].forEach(installLod);apply();};
 const onStart=()=>{
  if(updating||disposed)return;
  moving=true;started=clock();lastFrame=0;intervals=[];reduced=false;clearTimeout(timer);
  root.document.body.classList.add('map-moving');
  // No canvas resize at the beginning of a pan or zoom.
 };
 const onRender=()=>{
  if(!moving||updating||disposed||root.document.hidden)return;
  const now=clock(),dt=now-lastFrame;lastFrame=now;
  if(dt<4||dt>250)return;
  intervals.push(dt);if(intervals.length>24)intervals.shift();if(intervals.length===24&&(!map._ridgeFrameHealth||now-map._ridgeFrameHealth.at>1000))map._ridgeFrameHealth={at:now,p75Ms:intervals.slice().sort((a,b)=>a-b)[17]};
  if(pressureCeiling<1&&intervals.length===24){const healthy=intervals.every(v=>v<22);if(!healthy)healthySince=0;else if(!healthySince)healthySince=now;else if(now-healthySince>5000){pressureCeiling=Infinity;healthySince=0;apply();}}
  // A single, modest reduction only after sustained measured pressure.
  if(!reduced&&intervals.length===24&&now-started>750&&ratio>.75){
   const sorted=intervals.slice().sort((a,b)=>a-b);
   if(sorted[17]>28){const next=Math.max(.75,ratio<=1?Math.round(ratio*.85*20)/20:Math.floor(ratio*.85*4)/4);if(next<ratio-.08){reduced=true;downshifts++;healthySince=0;if(next<1)pressureCeiling=next;if(phoneActive)phoneCeiling=Math.min(phoneCeiling,next);setRatio(next);}}
  }
 };
 const onEnd=()=>{
  if(updating||disposed)return;moving=false;lastFrame=0;clearTimeout(timer);
  timer=setTimeout(()=>{if(disposed||moving)return;root.document.body.classList.remove('map-moving');apply();onSettled();},reduced?650:280);
 };
 const onResize=()=>{if(!updating&&!moving)apply();};
 const cleanup=()=>{disposed=true;grids?.destroy();surfaceFocus?.destroy();clearTimeout(timer);root.document.body.classList.remove('map-moving');for(const [name,fn] of listeners)map.off(name,fn);};
 const listeners=[['movestart',onStart],['render',onRender],['moveend',onEnd],['resize',onResize],['remove',cleanup]];
 listeners.forEach(([name,fn])=>map.on(name,fn));refresh();
 return{setMemoryPressure(value){memoryPressure=!!value;apply();},refresh,installLod,surfaceFocus:()=>surfaceFocus?.state(),isMoving:()=>moving,stats:()=>({pixelRatio:ratio,phoneMode:phoneActive,phoneDetail:normalizePhoneDetail(phoneDetail()),phoneResolutionCeiling:Number.isFinite(phoneCeiling)?phoneCeiling:null,memoryPressure,moving,adaptiveDownshifts:downshifts,adaptiveResolutionCeiling:Number.isFinite(pressureCeiling)?pressureCeiling:null,frameSamples:intervals.length,...surfaceFocus?.stats(),...grids?.stats()}),destroy:cleanup};
}
const api={pixelRatio,cacheSize,tileOptions,lodParams,focusLod,nearbyLod,normalizePhoneDetail,attach};if(typeof module!=='undefined')module.exports=api;else root.RidgePerformance=api;
})(globalThis);
