/* Bounded, shared source-tile residency for imagery or decoded elevation.
   Native caches still own tiles. Leaving the frustum does not cancel useful
   in-flight imagery, and viewport size no longer silently shrinks residency. */
(function(root){
'use strict';
function createPool({bytes=128*1048576,pendingLimit=8,types=['raster'],priority=null,protect=()=>new Set(),relieve=()=>false}={}){
 const entries=new Map(),adapters=new Map();let total=0,reuses=0,evictions=0,completedOffscreen=0,dead=false,protectedPressure=0,fallbackReads=0,pendingCount=0,ancestorRestores=0;
 const cost=(tile,size,type)=>{
  if(type==='raster-dem'){
   // Reserve decoded main/worker arrays, a DEM GPU texture, and a possible
   // hillshade target. Conservative even before GPU preparation happens.
   const stride=tile.dem?.stride||size+4,[w,h]=tile.demTexture?.size||[stride,stride];
   return Math.ceil(2*(tile.dem?.data?.byteLength||stride*stride*4)+w*h*4+(size+2)**2*4);
  }
  const [w,h]=tile.texture?.size||[size,size];return Math.ceil(w*h*4*4/3);
 };
 function forget(tile){const entry=entries.get(tile);if(entry){total-=entry.bytes;if(entry.pending)pendingCount--;entries.delete(tile);}return entry;}
 function trim(){
  // The common path must not traverse every rendered/warm DEM dependency.
  if(total<=bytes)return;
  let needed=protect();
  while(total>bytes&&entries.size){
   // Keep a small coarse coverage reserve, within the same byte budget.
   let coarseBytes=0;for(const [tile,entry]of entries)if(tile.tileID.canonical.z<=12)coarseBytes+=entry.bytes;
   let victim,oldest;for(const row of entries)if(!needed.has(row[0])){oldest??=row;if(row[0].tileID.canonical.z>12||coarseBytes>bytes*.2){victim=row;break;}}victim??=oldest;
   if(!victim){if(relieve()){needed=protect();continue;}protectedPressure++;break;}
   const [tile,entry]=victim,wrapper=entry.cache.data[tile.tileID.wrapped().key]?.find(e=>e.value===tile);
   if(wrapper){evictions++;entry.cache.remove(tile.tileID,wrapper);}else forget(tile);
  }
 }
 function pendingRoom(){let count=0;for(const tile of entries.keys())if(['loading','reloading','expired'].includes(tile.state)&&++count>=pendingLimit)return false;return pendingLimit>0;}
 function attach(manager){
  const type=manager?.getSource?.()?.type;
  if(dead||adapters.has(manager)||!types.includes(type))return;
  const cache=manager._outOfViewCache;if(!cache||!manager._inViewTiles?.deleteTileById||!cache.getAndRemove||!cache.onRemove)return;
  const bump=()=>{root.RidgeTerrainComposition?.invalidateSource?.(manager);manager._ridgeResidencyRevision=(manager._ridgeResidencyRevision||0)+1;};bump();
  const source=manager.getSource(),sourceId=source.id||source._options?.id,loadTile=source.loadTile;let wrappedLoad=null;
  if(root.RidgeTileTrace&&typeof loadTile==='function'){wrappedLoad=async function(tile,...args){if(root.RidgeTileTrace.enabled?.()===false)return loadTile.call(this,tile,...args);const start=root.performance.now();const attempt=root.RidgeTileTrace.emit('source-load-start',sourceId,tile.tileID.canonical);try{const result=await loadTile.call(this,tile,...args);root.RidgeTileTrace.emit('source-ready-upload-submitted',sourceId,tile.tileID.canonical,{attempt,elapsedMs:root.performance.now()-start});return result;}catch(error){root.RidgeTileTrace.emit('request-error',sourceId,tile.tileID.canonical);throw error;}};source.loadTile=wrappedLoad;}
  const original={updateCacheSize:manager.updateCacheSize,removeTile:manager._removeTile,retain:manager._updateRetainedTiles,add:cache.add,getAndRemove:cache.getAndRemove,getByKey:cache.getByKey,onRemove:cache.onRemove};
  manager.updateCacheSize=function(){cache.setMaxSize(Number.MAX_SAFE_INTEGER);};manager.updateCacheSize();
  if(typeof original.retain==='function')manager._updateRetainedTiles=function(ids,zoom){
   const scores=new Map(ids.map(id=>[id.key,(type==='raster-dem'&&id.canonical.z<=5)?-2:priority?.(id)]));
   const ordered=ids.slice().sort((a,b)=>(scores.get(a.key)??0)-(scores.get(b.key)??0)||(type==='raster-dem'&&Number.isFinite(scores.get(a.key))&&Number.isFinite(scores.get(b.key))?a.canonical.z-b.canonical.z:0));
   const retained=original.retain.call(this,ordered,zoom);
   const usable=tile=>!!tile&&!tile.aborted&&tile.state!=='unloaded'&&(type==='raster-dem'?!!tile.dem:!!tile.texture);
   let loaded;const covers=new Map(),missingParents=new Map();let checkedRevision=manager._ridgeResidencyRevision;
   for(const id of ordered){
    if(usable(this.getTile(id)))continue;
    const z=id.canonical.z;
    // A complete sharper child cover already meets this request.
    if(!covers.has(z))covers.set(z,root.RidgeTerrainStreaming?.completeCoverage?.((loaded??=Object.values(retained).filter(id=>usable(this.getTile(id)))).filter(q=>q.canonical.z>=z)));
    if(covers.get(z)?.(id))continue;
    let savedOverview=null,found=false;
    for(let level=z-1;level>=Math.max(0,source.minzoom||0);level--){
     const parent=id.scaledTo(level);
     // Siblings share failed ancestor probes. Recheck after any residency
     // mutation, and after explicitly adding an overview below.
     if(checkedRevision!==manager._ridgeResidencyRevision){missingParents.clear();checkedRevision=manager._ridgeResidencyRevision;}
     if(missingParents.has(parent.key)){if(!savedOverview&&missingParents.get(parent.key))savedOverview=parent;continue;}
     let tile=this.getTile(parent);
     if(!usable(tile)){
      const cached=cache.data?.[parent.wrapped().key]?.[0]?.value;
      if(!usable(cached)){const overview=!!(source.tiles?.[0]&&root.RidgeNZOverview?.hasTile(source.tiles[0],parent));missingParents.set(parent.key,overview);if(!savedOverview&&overview)savedOverview=parent;continue;}
      // Only restore resident decoded data; never start a parent download here.
      tile=this._addTile(parent);ancestorRestores++;
     }
     if(usable(tile)){retained[parent.key]=parent;found=true;break;}
    }
    if(!found&&savedOverview){this._addTile(savedOverview);missingParents.clear();retained[savedOverview.key]=savedOverview;}
   }
   return retained;
  };
  cache.add=function(id,tile){bump();forget(tile);const size=manager.getSource().tileSize,allocation=cost(tile,size,type);const pending=['loading','reloading','expired'].includes(tile.state);entries.set(tile,{cache,bytes:allocation,pending});if(pending)pendingCount++;total+=allocation;
   // Residency is governed by byte pressure, not an expiry timer while hidden.
   // Native _addTile reinstalls freshness/reload timers when this tile returns.
   const result=original.add.call(this,id,tile);trim();return result;};
  cache.getAndRemove=function(id){const tile=original.getAndRemove.call(this,id);if(tile){bump();forget(tile);
   if(tile.state==='unloaded'||tile.state==='errored'&&!tile.texture&&!tile.dem){cache.onRemove(tile);return null;}
   if(tile.state==='errored'&&(tile.texture||tile.dem)&&typeof manager._reloadTile==='function'){
    tile.state='loaded';Promise.resolve().then(()=>{if(!dead&&manager.getTile?.(tile.tileID)===tile&&tile.state==='loaded')manager._reloadTile(tile.tileID.key,'reloading');});
   }
   root.RidgeTileTrace?.emit('source-adopted',sourceId,tile.tileID.canonical,{tier:'native-source'});reuses++;}return tile;};
  if(typeof original.getByKey==='function')cache.getByKey=function(key){const tile=original.getByKey.call(this,key),entry=entries.get(tile);if(entry){entries.delete(tile);entries.set(tile,entry);fallbackReads++;}return tile;};
  cache.onRemove=function(tile){bump();forget(tile);manager._clearTileReloadTimer?.(tile.tileID.key);if(tile.state==='loading'||tile.state==='reloading'){tile.aborted=true;manager._abortTile(tile);}return original.onRemove.call(this,tile);};
  manager._removeTile=function(id,options){
   const tile=this._inViewTiles.getTileById(id);
   // Native removal aborts unfinished raster loads. Park a bounded number in
   // the same source cache instead, allowing requests to finish while hidden.
   // Explicit idle preparation may park one job even when gesture leftovers
   // are disabled. The caller checks byte budgets and cancels it on movement.
   if(tile&&tile.uses===1&&['loading','reloading','expired'].includes(tile.state)&&
    (tile.texture||tile.dem||pendingRoom()||options?.prepareAhead&&![...entries.keys()].some(t=>['loading','reloading','expired'].includes(t.state)))){
    tile.uses--;this._inViewTiles.deleteTileById(id);this._clearTileReloadTimer(id);cache.add(tile.tileID,tile,tile.getExpiryTimeout());return;
   }
   return original.removeTile.call(this,id);
  };
  const onData=e=>{bump();const tile=e.tile,entry=entries.get(tile);if(entry&&tile.state==='loaded'){if(entry.pending){entry.pending=false;pendingCount--;}total-=entry.bytes;entry.bytes=cost(tile,manager.getSource().tileSize,type);total+=entry.bytes;completedOffscreen++;trim();}};
  manager.on('data',onData);
  adapters.set(manager,{cache,undo:()=>{if(source.loadTile===wrappedLoad)source.loadTile=loadTile;manager.off('data',onData);cache.reset();manager.updateCacheSize=original.updateCacheSize;manager._removeTile=original.removeTile;manager._updateRetainedTiles=original.retain;cache.add=original.add;cache.getAndRemove=original.getAndRemove;cache.getByKey=original.getByKey;cache.onRemove=original.onRemove;}});
 }
 function flush(){for(const {cache} of adapters.values())cache.reset();}
 function destroy(){if(dead)return;dead=true;for(const {undo} of adapters.values())undo();adapters.clear();entries.clear();total=0;pendingCount=0;}
 return{attach,flush,destroy,trim,setPendingLimit(value){if(Number.isFinite(value))pendingLimit=Math.max(0,Math.min(24,Math.round(value)));},setBudget(value){if(Number.isFinite(value)&&value>=0&&value!==bytes){bytes=value;trim();}},stats:()=>({sourceResidentTiles:entries.size,sourceResidentBytes:total,sourceBudgetBytes:bytes,sourceReuses:reuses,sourceEvictions:evictions,completedOffscreen,protectedPressure,fallbackReads,ancestorRestores,pendingLimit,pendingOffscreen:pendingCount})};
}
const api={createPool};if(typeof module!=='undefined')module.exports=api;else root.RidgeRasterResidency=api;
})(globalThis);
