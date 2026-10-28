import {createCoordinateIndexCache} from './coordinate-index-cache.mjs';
import {profileMainThread} from './main-thread-profile.mjs';
// App-only work reductions. No source/detail/mesh selection changes.
function replace(text, from, to) {
 if(!text.includes(from))throw Error('Optimisation integration changed: '+from.slice(0,80));
 return text.replace(from,to);
}
export function optimiseWeb(name,text){
 if(name==='native-residency.js'){
  text=replace(text,'function relatedCoordinates(manager,regular,id,scope,onCandidates=()=>{}){',createCoordinateIndexCache.toString()+"\nconst coordinateIndexes=createCoordinateIndexCache();\nfunction relatedCoordinates(manager,regular,id,scope,onCandidates=()=>{}){");
  text=replace(text,'if(!scope.coordinateIndex){const descendants=', 'if(!scope.coordinateIndex)scope.coordinateIndex=coordinateIndexes.get(manager,()=>{const descendants=');
  text=replace(text,'scope.coordinateIndex={descendants,exact,order:new Map(manager._renderableTilesKeys.map((key,index)=>[key,index]))};}', 'return {descendants,exact,order:new Map(manager._renderableTilesKeys.map((key,index)=>[key,index]))};});');
  text=replace(text,'...budget.stats(),terrainCoordinatePairsVisited:', '...budget.stats(),...coordinateIndexes.stats(),terrainCoordinatePairsVisited:');
  text=replace(text,' manager.update=function(...args){',` const releaseHooks=new WeakMap();let releaseContext=null,stableTransitionSkips=0;
 function releaseHook(tile,key,release){
  let saved=releaseHooks.get(tile);if(saved&&saved.key===key&&saved.release===release)return saved;
  saved={tile,key,release,wrapper:function(...args){
   const frame=releaseContext;
   if(frame){visibleKeys??=new Set(manager._renderableTilesKeys);
    if(!visibleKeys.has(key)&&cost(tile)>0){frame.deferred.set(key,tile);return;}
    if(!visibleKeys.has(key))frame.empty.set(key,tile);
   }
   return release.apply(this,args);
  }};releaseHooks.set(tile,saved);return saved;
 }
 manager.update=function(...args){`);
  text=replace(text,'inUpdate=true;restored=false;visibleKeys=null;const deferred=new Map(),empty=new Map(),undo=[];', 'inUpdate=true;restored=false;visibleKeys=null;const deferred=new Map(),empty=new Map(),undo=[],previousReleaseContext=releaseContext;releaseContext={deferred,empty};');
  const start=text.indexOf('  for(const [key,tile] of Object.entries(target)){');
  const end=text.indexOf('  let changed;',start);
  if(start<0||end<0)throw Error('Release-hook integration changed');
  text=text.slice(0,start)+`  for(const key in target){if(!Object.hasOwn(target,key))continue;const tile=target[key],release=tile.releaseRTT;if(typeof release!=='function')continue;
   const hook=releaseHook(tile,key,release);tile.releaseRTT=hook.wrapper;undo.push(hook);
  }
`+text.slice(end);
  text=replace(text,'inUpdate=false;undo.forEach(fn=>fn());','inUpdate=false;for(const hook of undo)hook.tile.releaseRTT=hook.release;releaseContext=previousReleaseContext;');
  const transitionStart=text.indexOf('  const desired=manager._renderableTilesKeys,desiredSet=');
  const transitionEnd=text.indexOf('  trim();\n  // Native RTT refinement',transitionStart);
  if(transitionStart<0||transitionEnd<0)throw Error('Transition integration changed');
  let transition=text.slice(transitionStart,transitionEnd);
  transition=replace(transition,'const desired=manager._renderableTilesKeys,desiredSet=new Set(desired),previousSet=new Set(previousKeys),chosen=', 'const desiredSet=new Set(desired),chosen=');
  transition=replace(transition,'  const reconcile=heldSince.size>0||desired.length!==previousKeys.length||desired.some(key=>!previousSet.has(key));\n','');
  transition=replace(transition,'  if(reconcile)for(const key of desired)', '  for(const key of desired)');
  text=text.slice(0,transitionStart)+`  const desired=manager._renderableTilesKeys;
  const sameOrder=desired.length===previousKeys.length&&desired.every((key,i)=>key===previousKeys[i]);
  const reconcile=heldSince.size>0||desired.length!==previousKeys.length||!sameOrder&&(()=>{const previousSet=new Set(previousKeys);return desired.some(key=>!previousSet.has(key));})();
  if(reconcile){
`+transition+'  }else stableTransitionSkips++;\n'+text.slice(transitionEnd);
  text=replace(text,'...coordinateIndexes.stats(),terrainCoordinatePairsVisited:', '...coordinateIndexes.stats(),stableTransitionSkips,terrainCoordinatePairsVisited:');
 }

 if(name==='bottleneck-diagnostics.js'){
  text=replace(text,'function attach(map,',profileMainThread.toString()+'\nfunction attach(map,');
  text=replace(text,'let gpuStatus=', 'let mainThread=null;\n let gpuStatus=');
  text=replace(text,'return{...m,diagnosis:classify(m)};', 'return{...m,mainThreadBreakdown:mainThread?.snapshot()||null,diagnosis:classify(m)};');
  text=replace(text,'enabled=true;onCapture(true);','enabled=true;mainThread=profileMainThread(map,clock);onCapture(true);');
  text=replace(text,'enabled=false;onCapture(false);','enabled=false;mainThread?.destroy();mainThread=null;onCapture(false);');
  text=replace(text,'function download(){const data=', 'async function download(){let nativeLoading=null;try{nativeLoading=await root.RidgeMobile?.loadingStats();}catch{}const data=');
  text=replace(text,'{build:root.RidgeBuildInfo?.fingerprint||null,...control.snapshot()}', '{build:root.RidgeBuildInfo?.fingerprint||null,nativeLoading,...control.snapshot()}');
  text=replace(text,"},url=root.URL.createObjectURL(new Blob([JSON.stringify(data,null,2)]","};if(await root.RidgeMobile?.exportDiagnostics?.(JSON.stringify(data,null,2)))return;const url=root.URL.createObjectURL(new Blob([JSON.stringify(data,null,2)]");
 }

 if(name==='response-cache.js')text=replace(text,'null:v.data.slice(0)','null:v.data');
 if(name==='terrain-streaming.js'){
  text=replace(text,'function imageReadiness(map){',`const readinessChecks=new WeakMap();
function readyCoverage(manager,ids){
 const old=readinessChecks.get(manager);
 if(old&&old.keys.length===ids.length&&ids.every((id,i)=>id.key!==undefined&&id.key===old.keys[i]))return old.check;
 const check=completeCoverage(ids);
 if(ids.every(id=>id.key!==undefined))readinessChecks.set(manager,{keys:ids.map(id=>id.key),check});
 return check;
}
function imageReadiness(map){`);
  text=replace(text,'checks.push(completeCoverage(ids));','checks.push(readyCoverage(manager,ids));');
 }
 if(name==='tile-cache.js'){
  text=replace(text,'let bytes=0;','let bytes=0,nextExpiry=Infinity;');
  text=replace(text,' function store(id,item,',' function prune(){const now=clock();if(now<nextExpiry)return;nextExpiry=Infinity;for(const [id,item]of items){if(item.until<=now)remove(id);else nextExpiry=Math.min(nextExpiry,item.until);}}\n function store(id,item,');
  text=replace(text,'prefetchOnly=false){if(item.data','prefetchOnly=false){prune();if(item.data');
  text=replace(text,'items.set(id,item);bytes+=','items.set(id,item);nextExpiry=Math.min(nextExpiry,item.until);bytes+=');
  text=replace(text,'hasEntries:()=>items.size>0','hasEntries:()=>{prune();return items.size>0;}');
  text=replace(text,'canPrefetch:()=>bytes<maxBytes*.85&&items.size<maxItems*.9','canPrefetch:()=>{prune();return bytes<maxBytes*.85&&items.size<maxItems*.9;}');
  text=replace(text,'pending.has(url),stats:',"(pending.has(url)&&!pending.get(url).controller.signal.aborted),stats:");
  text=replace(text,'clear(){items.clear();bytes=0;','clear(){items.clear();bytes=0;nextExpiry=Infinity;');
 }
 if(name==='raster-residency.js'){
  text=replace(text,'  let needed=protect();','  let needed=protect();\n  let coarseBytes=0;for(const [tile,entry]of entries)if(tile.tileID.canonical.z<=12)coarseBytes+=entry.bytes;');
  text=replace(text,'   let coarseBytes=0;for(const [tile,entry]of entries)if(tile.tileID.canonical.z<=12)coarseBytes+=entry.bytes;','');
  text=replace(text,'   if(wrapper){evictions++;','   if(tile.tileID.canonical.z<=12)coarseBytes-=entry.bytes;\n   if(wrapper){evictions++;');
 }
 return text;
}
