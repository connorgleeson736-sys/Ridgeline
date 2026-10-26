/* Allocation estimates, never a portable VRAM query. No GL calls/readback.
   Snapshot work is on report/idle admission paths, not the render loop. */
(function(root){
'use strict';
const MiB=1048576,textureBytes=(texture,mips=true)=>{const s=texture?.size;return s?.length===2?Math.ceil(s[0]*s[1]*4*(mips?4/3:1)):0;};
function boundSourceRecycler(painter,bytes=4*MiB){
 if(!painter?._tileTextures||typeof painter.saveTileTexture!=='function'||typeof painter.getTileTexture!=='function')return null;
 const save=painter.saveTileTexture,get=painter.getTileTexture;let drops=0,dead=false;
 function trim(){let total=0;const pool=painter._tileTextures;if(!pool||Object.values(pool).some(rows=>!Array.isArray(rows)))return 0;
  for(const rows of Object.values(pool))for(const t of rows)total+=textureBytes(t);
  for(const rows of Object.values(pool))while(total>bytes&&rows.length){const t=rows.shift();total-=textureBytes(t);t.destroy();drops++;}return total;
 }
 function put(texture){save.call(this,texture);trim();}
 painter.saveTileTexture=put;trim();
 return{setBudget(n){if(Number.isFinite(n)&&n>=0){bytes=n;trim();}},destroy(){if(dead)return;dead=true;if(painter.saveTileTexture===put)painter.saveTileTexture=save;},stats:()=>({sourceRecyclerBytes:trim(),sourceRecyclerBudgetBytes:bytes,sourceRecyclerDrops:drops})};
}
function snapshot(map,{drapes,byteStats={},cacheBudgetMiB=352,offscreenDEMOverBudget=false}={}){
 const textures=new Set(),buffers=new Set(),tiles=new Set(),rtts=new Set();let activeSourceGPU=0,offscreenSourceGPU=0,decodedCPU=0,activeRTT=0,offscreenRTT=0,sourceRecycler=0,rttRecycler=0,auxiliaryGPU=0,pendingNative=0;
 function texture(t,mips=true){if(!t||textures.has(t))return 0;textures.add(t);return textureBytes(t,mips);}
 function tile(t,active,type){if(tiles.has(t))return;tiles.add(t);let gpu=texture(t.texture)+texture(t.demTexture,false);if(t.fbo?.width&&t.fbo?.height)auxiliaryGPU+=t.fbo.width*t.fbo.height*4;
  const data=t.dem?.data;if(data?.byteLength){const owner=data.buffer||data;if(!buffers.has(owner)){buffers.add(owner);decodedCPU+=data.byteLength;}}
  if(['loading','reloading','expired'].includes(t.state))pendingNative++;
  if(active)activeSourceGPU+=gpu;else offscreenSourceGPU+=gpu;
 }
 const managers=[...new Set(Object.values(map.style?.tileManagers||{}))];
 for(const m of managers){const type=m.getSource?.()?.type;if(!['raster','raster-dem'].includes(type))continue;for(const t of m._inViewTiles?.getAllTiles?.()||[])tile(t,true,type);}
 for(const m of managers){const type=m.getSource?.()?.type;if(!['raster','raster-dem'].includes(type))continue;for(const rows of Object.values(m._outOfViewCache?.data||{}))for(const {value:t}of rows)tile(t,false,type);}
 const views=drapes||{visible:map.terrain?.tileManager?.getRenderableTiles?.()||[],warm:[]};
 for(const [group,active]of [[views.visible,true],[views.warm,false]])for(const t of group||[])for(const obj of t.rttObjects||[]){if(!obj||rtts.has(obj))continue;rtts.add(obj);const already=obj.texture&&textures.has(obj.texture),bytes=already?0:texture(obj.texture)||Math.ceil(obj.size**2*4*4/3);if(active)activeRTT+=bytes;else offscreenRTT+=bytes;}
 for(const rows of Object.values(map.painter?._tileTextures||{}))if(Array.isArray(rows))for(const t of rows)sourceRecycler+=texture(t);
 for(const obj of map.painter?._rttObjectRecyclePool||[])rttRecycler+=texture(obj.texture);
 const p=map.painter,gl=p?.context?.gl,w=gl?.drawingBufferWidth||p?.width||0,h=gl?.drawingBufferHeight||p?.height||0;
 const framebufferEstimate=w*h*8+(p?._rttSharedFbo?.size||0)**2*4; // RGBA + depth/stencil baseline, driver buffers unknown.
 const compressed=(byteStats.imagery?.bytes||0)+(byteStats.terrain?.bytes||0),pendingComposition=(byteStats.regional?.queuedCompositionBytes||0)+(byteStats.regional?.activeCompositionInputBytes||0),compositionTemporary=(byteStats.regional?.compositesActive||0)*256*256*4*7;
 const gpuEstimate=activeSourceGPU+offscreenSourceGPU+activeRTT+offscreenRTT+sourceRecycler+rttRecycler+auxiliaryGPU+framebufferEstimate;
 const measuredEstimate=(byteStats.regional?.inputBytes||0)+compressed+decodedCPU+gpuEstimate+pendingComposition+compositionTemporary;
 const ceiling=cacheBudgetMiB*MiB*1.5,pressure=measuredEstimate>ceiling||offscreenDEMOverBudget;
 return{estimated:true,cacheBudgetMiB,estimatedWorkingSetCeilingBytes:ceiling,pressure,regionalInputBytes:byteStats.regional?.inputBytes||0,compressedBytes:compressed,decodedMainThreadDEMBytes:decodedCPU,activeSourceGPUBytes:activeSourceGPU,offscreenSourceGPUBytes:offscreenSourceGPU,activeRTTBytes:activeRTT,offscreenRTTBytes:offscreenRTT,sourceRecyclerBytes:sourceRecycler,rttRecyclerBytes:rttRecycler,auxiliaryGPUBytes:auxiliaryGPU,framebufferEstimateBytes:framebufferEstimate,pendingCompositionInputBytes:pendingComposition,compositionTemporaryEstimateBytes:compositionTemporary,pendingNativeTiles:pendingNative,gpuEstimateBytes:gpuEstimate,estimatedWorkingSetBytes:measuredEstimate,protectedDEMOverBudget:offscreenDEMOverBudget,unmeasured:'Driver/OS allocations, worker copies, transient native decode buffers, vector resources, route workers and optional renderer overlap are not fully observable; this is not total tab memory or available VRAM.'};
}
const api={textureBytes,boundSourceRecycler,snapshot};if(typeof module!=='undefined')module.exports=api;else root.RidgeResources=api;
})(globalThis);
