import {profileMainThread} from './main-thread-profile.mjs';
// App-only work reductions. No source/detail/mesh selection changes.
function replace(text, from, to) {
 if(!text.includes(from))throw Error('Optimisation integration changed: '+from.slice(0,80));
 return text.replace(from,to);
}
export function optimiseWeb(name,text){
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
