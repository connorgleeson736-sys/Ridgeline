'use strict';
importScripts('./terrain-data.js?v=3','./terrain-patches.js?v=1');
let engine,generation=0,preparedBytes=0,preparedLimit=8*1024*1024;let patchBytes=0,patchHits=0;const jobs=new Map(),prepared=new Map(),patches=new Map();
function remember(id,heights){if(prepared.has(id))preparedBytes-=prepared.get(id).byteLength;prepared.set(id,heights);preparedBytes+=heights.byteLength;while(preparedBytes>preparedLimit*.5&&prepared.size>1){const k=prepared.keys().next().value;preparedBytes-=prepared.get(k).byteLength;prepared.delete(k);}}
function trimPatches(){while(patchBytes>preparedLimit*.5&&patches.size){const k=patches.keys().next().value,p=patches.get(k);patchBytes-=p.heights.byteLength+p.normals.byteLength;patches.delete(k);}}
async function measured(x,y,z,signal){
 const id=`${z}/${x}/${y}`,cached=prepared.get(id);if(cached){prepared.delete(id);prepared.set(id,cached);return cached;}
 // Source engine already deduplicates cancellable raw requests. Prepared edge grids
 // are reused, especially by four imagery children above source zoom 18.
 const result=await engine.tile(x,y,z,signal);if(!signal.aborted)remember(id,result);return result;
}
onmessage=async({data:d})=>{
 if(d.type==='init'){generation++;engine?.destroy();engine=RidgeTerrainData.createEngine({key:d.key});prepared.clear();preparedBytes=0;patches.clear();patchBytes=0;patchHits=0;postMessage({type:'ready'});return;}
 if(d.type==='configure'){engine?.configure(d.options);preparedLimit=Math.max(1048576,d.options.preparedBytes||preparedLimit);while(preparedBytes>preparedLimit*.5&&prepared.size){const k=prepared.keys().next().value;preparedBytes-=prepared.get(k).byteLength;prepared.delete(k);}trimPatches();return;}
 if(d.type==='cancel'){jobs.get(d.id)?.abort();return;}
 if(d.type!=='tile')return;const controller=new AbortController(),stamp=generation;jobs.set(d.id,controller);
 try{const id=`${d.z}/${d.x}/${d.y}`;let patch=patches.get(id),processingMs=0;if(patch){patchHits++;patches.delete(id);patches.set(id,patch);}else{let heights,size=257;if(d.z<=18)heights=(await measured(d.x,d.y,d.z,controller.signal)).slice();else{
  const parent=await measured(d.x>>1,d.y>>1,18,controller.signal);size=129;heights=new Float32Array(size*size);const ox=(d.x%2)*128,oy=(d.y%2)*128;for(let j=0;j<size;j++)for(let i=0;i<size;i++)heights[j*size+i]=parent[(j+oy)*257+i+ox];
 }
 if(controller.signal.aborted||stamp!==generation)return;
 const start=performance.now();patch=RidgePatches.prepare(heights,{x:d.x,y:d.y,z:d.z,size});processingMs=performance.now()-start;patches.set(id,patch);patchBytes+=patch.heights.byteLength+patch.normals.byteLength;trimPatches();}
 // Transfer copies so a cached prepared patch survives the message transfer.
 const mesh={...patch,heights:patch.heights.slice(),normals:patch.normals.slice()};
 if(!controller.signal.aborted)postMessage({id:d.id,type:'tile',mesh,stats:{...engine.stats(),preparedBytes:preparedBytes+patchBytes,patchHits,processingMs}},[mesh.heights.buffer,mesh.normals.buffer]);
 }catch(e){if(!controller.signal.aborted)postMessage({id:d.id,type:'error',message:e.message,name:e.name});}finally{jobs.delete(d.id);}
};
