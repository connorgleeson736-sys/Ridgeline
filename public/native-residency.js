/* Version-guarded adapter for MapLibre 6.14.0 terrain drapes. Native source data,
   frustum and requested LOD stay authoritative. Keep measured DEM dependencies
   and complete rendered covers until replacements are ready; never edit DEMs. */
(function(root){
'use strict';
// Only zoom-independent pixel projections may bypass zoom-only baking.
// Vector geometry with a real bucket, patterns, dynamic paint and custom
// layers retain native zoom invalidation (constant-width lines still change).
function zoomIndependent(tile,style,coords,zoom,layerIds=style?._order,paintChecks){
 if(!style?._order||!coords||!Number.isFinite(zoom))return false;let raster=false;
 for(const id of layerIds){const layer=style._layers[id];if(!layer||layer.isHidden?.(zoom))continue;
  if(['symbol','circle','fill-extrusion','heatmap'].includes(layer.type))continue;
  if(['line','fill'].includes(layer.type)){
   for(const q of coords[layer.source]?.[tile.tileID.key]||[]){const source=style.tileManagers?.[layer.source]?.getTile?.(q);if(!source||typeof source.getBucket!=='function'||source.getBucket(layer))return false;}continue;
  }
  if(!['raster','background'].includes(layer.type)||typeof layer.serialize!=='function'||layer.hasTransition?.())return false;
  raster=true;let safe=paintChecks?.get(layer);
  if(safe===undefined){const paint=layer.serialize().paint||{};safe=true;
   for(const [key,value]of Object.entries(paint))if(key.endsWith('-pattern')&&value||Array.isArray(value)&&typeof value[0]==='string'||value&&typeof value==='object'&&!Array.isArray(value)){safe=false;break;}
   paintChecks?.set(layer,safe);
  }if(!safe)return false;
 }return raster;
}
// Keep a compact, clipped resolution requirement for each imagery source
// used in a completed patch. This is source geometry only, no image copies.
function imageryQuality(tile,style,coords){
 const result=[],seen=new Set(),patch=tile.tileID.canonical;
 for(const layer of Object.values(style?._layers||{})){
  if(layer.type!=='raster'||seen.has(layer.source))continue;
  const ids=coords?.[layer.source]?.[tile.tileID.key];if(!ids?.length)continue;seen.add(layer.source);
  if(style.tileManagers?.[layer.source]?.getSource?.()?.type!=='raster')return null;
  const requirements=ids.map(id=>{const q=id.canonical,clip=q.z>patch.z?q:patch;return{id:{canonical:{z:clip.z,x:clip.x,y:clip.y}},minZoom:q.z};});
  result.push({source:layer.source,requirements});
 }return result.length?result:null;
}
function lowerQuality(tile,saved,renderer,coverage){
 if(!saved?.length||typeof coverage!=='function')return false;let lower=false;
 for(const entry of saved){
  const old=tile.rttFingerprint[entry.source],next=renderer._rttFingerprints[entry.source]?.[tile.tileID.key],ids=renderer._coordsAscending[entry.source]?.[tile.tileID.key];
  if(!old||!next||!ids||old._revision!==next._revision||old._visibleLayerIds!==next._visibleLayerIds)return false;
  const checks=new Map();for(const r of entry.requirements){if(!checks.has(r.minZoom))checks.set(r.minZoom,coverage(ids.filter(id=>id.canonical.z>=r.minZoom)));if(!checks.get(r.minZoom)(r.id))lower=true;}
 }return lower;
}
// The pinned regular-tile path allocates/clones before rejecting unrelated
// patches. Prefilter its candidates, then retain the native matrix arithmetic.
// This index lives for one synchronous prepare call, never across tile changes.
function relatedCoordinates(manager,regular,id,scope,onCandidates=()=>{}){
 if(!scope.coordinateIndex){const descendants=new Map(),exact=new Map();for(const key of manager._renderableTilesKeys){const q=manager._tiles[key].tileID.canonical,k=q.z+'/'+q.x+'/'+q.y;(exact.get(k)||exact.set(k,[]).get(k)).push(key);for(let z=q.z;z>=0;z--){const d=2**(q.z-z),k=z+'/'+Math.floor(q.x/d)+'/'+Math.floor(q.y/d);(descendants.get(k)||descendants.set(k,[]).get(k)).push(key);}}scope.coordinateIndex={descendants,exact,order:new Map(manager._renderableTilesKeys.map((key,index)=>[key,index]))};}
 const q=id.canonical,{descendants,exact}=scope.coordinateIndex,found=new Set(descendants.get(q.z+'/'+q.x+'/'+q.y)||[]);
 for(let z=q.z-1;z>=0;z--){const d=2**(q.z-z);for(const key of exact.get(z+'/'+Math.floor(q.x/d)+'/'+Math.floor(q.y/d))||[])found.add(key);}
 const keys=[...found].sort((a,b)=>scope.coordinateIndex.order.get(a)-scope.coordinateIndex.order.get(b));onCandidates(manager._renderableTilesKeys.length,keys.length);
 return regular.call(Object.assign(Object.create(manager),{_renderableTilesKeys:keys}),id);
}
// A patch can own several drape stacks. Estimate and charge their combined
// CPU submission cost, rather than letting the last stack replace the estimate.
function compositionBudget(){
 const costs=new Map();let keys=[],spent=0,prepared=0,limit=4;
 const estimate=()=>keys.reduce((sum,key)=>sum+(costs.get(key)??1),0)||1;
 function begin(style,zoom,moving){keys=[];let stack=null;
  for(const id of style._order||[]){const l=style._layers[id];if(l.isHidden?.(zoom))continue;
   const draped=['background','fill','line','raster','hillshade','color-relief'].includes(l.type)||l.type==='custom'&&l.implementation?.renderToTerrainTile;
   if(draped){if(!stack){stack=[];keys.push(stack);}stack.push(id);}else stack=null;
  }
  keys=keys.map(ids=>ids.join(','));for(const key of costs.keys())if(!keys.includes(key))costs.delete(key);
  spent=0;prepared=0;limit=moving?2:4;return estimate();
 }
 function record(ids,elapsed,count){if(!count)return;const key=ids.join(','),sample=Math.max(.05,elapsed)/count,old=costs.get(key);costs.set(key,old===undefined?sample:old*.8+sample*.2);spent+=Math.max(0,elapsed);prepared+=count;}
 return{begin,record,estimate,stats:()=>({compositionBudgetMs:limit,compositionSubmissionMs:spent,compositionStacks:keys.length,preparedStackPatches:prepared,estimatedPatchSubmissionMs:estimate()})};
}
function retainDrapes(manager,painter,{bytes=96*1048576,clock=()=>root.performance.now(),focus=()=>null,shouldInvalidate=()=>true,imageryUpdate=()=>false,imageryEvent=()=>null,contentUpdate=()=>false,readiness=()=>()=>true}={}){
 if(!manager?._tiles||!Array.isArray(manager._renderableTilesKeys)||typeof manager.update!=='function'||typeof manager.releaseAllRTT!=='function'||typeof manager.releaseRTT!=='function'||typeof manager.destruct!=='function')return null;
 const target=manager._tiles,warm=new Map(),original={update:manager.update,releaseRTT:manager.releaseRTT,releaseAllRTT:manager.releaseAllRTT,destruct:manager.destruct};
 let limit=bytes,total=0,inUpdate=false,restored=false,visibleKeys=null,disposed=false,hits=0,evictions=0,invalidations=0,fullInvalidations=0,geometryOnlyEvents=0,continuityHolds=0,demPressureDrops=0,deferredRefreshes=0,readyRefreshes=0,partialCoverHolds=0,transitionPatches=0,dirty=new WeakSet();const stableStacks=new WeakMap();let partialZoomReuses=0;const heldSince=new Map(),imageCoverage=new WeakMap(),fingerprintKeys=new WeakMap(),returns=new Map();
 const returnMetrics={restored:0,drawn:0,repainted:0,zoomReuse:0,qualityHolds:0,ignoredImageryEvents:0,speculativeEvictions:0,reasons:{none:0,zoom:0,sourceTiles:0,visibleLayers:0,revision:0}},returnTimes=[];let latencyCache=null,coordinateCandidates=0,coordinateSkipped=0;
 const markReturn=(tile,kind='exact')=>{if(!returns.has(tile)){returns.set(tile,{at:clock(),repainted:false,kind});returnMetrics.restored++;root.RidgeTileTrace?.emit('rtt-adopted','terrain-rtt',tile.tileID.canonical,{tier:'finished-rtt'});while(returns.size>256)returns.delete(returns.keys().next().value);}};
 const victim=()=>{for(const [key,entry]of warm)if(entry.speculative)return key;return warm.keys().next().value;};
 const cost=tile=>(tile.rttObjects||[]).reduce((sum,obj)=>sum+(obj?obj.size*obj.size*4*4/3:0),0);
 function drop(key,eviction=false){const entry=warm.get(key);if(!entry)return;warm.delete(key);total-=entry.bytes;if(entry.speculative&&eviction)returnMetrics.speculativeEvictions++;returns.delete(entry.tile);dirty.delete(entry.tile);imageCoverage.delete(entry.tile);entry.tile.releaseRTT(painter);}
 function flush(){lastRender=null;for(const key of [...warm.keys()])drop(key);heldSince.clear();returns.clear();dirty=new WeakSet();}
 function trim(){while(total>limit&&warm.size){drop(victim(),true);evictions++;}}
 const proxy=new Proxy(target,{get(obj,key){
  if(inUpdate&&typeof key==='string'&&!Object.hasOwn(obj,key)&&warm.has(key)){
   const entry=warm.get(key);warm.delete(key);total-=entry.bytes;obj[key]=entry.tile;restored=true;hits++;markReturn(entry.tile);manager._lastTilesetChange=clock();
  }
  return obj[key];
 }});manager._tiles=proxy;
 // Native source arrivals and RTT fingerprints can otherwise clear the last
 // complete image while only part of its replacement is available. Preserve
 // it through the real prepareForRender path; styles/revisions still invalidate.
 const budget=compositionBudget();
 const rtt=painter.renderToTexture,prepare=rtt?.prepareForRender,difference=rtt?._textureDifference,renderLayer=rtt?.renderLayer;let scope=null,undoRender=()=>{},lastRender=null;
 const currentReadiness=()=>scope?(scope.coverage??=readiness()):readiness();
 const covered=(tile,id=tile.tileID)=>{const saved=imageCoverage.get(tile);return saved?saved.owner===tile.rttObjects&&saved.coverage(id):currentReadiness()(id);};
 const recordImageCoverage=(tile,coverage=readiness(),stableZoom=false,quality=null)=>imageCoverage.set(tile,{coverage,owner:tile.rttObjects,stableZoom,quality});
 if(typeof prepare==='function'&&typeof difference==='function'){
  const regular=manager._getTerrainCoordsForRegularTile;
  function coordinates(id){if(this!==manager||!scope?.coordinatePreparation)return regular.call(this,id);return relatedCoordinates(this,regular,id,scope,(all,used)=>{coordinateCandidates+=used;coordinateSkipped+=all-used;});}
  if(typeof regular==='function')manager._getTerrainCoordsForRegularTile=coordinates;
  function prepareCovered(...args){this._ridgeCostPerPatch=budget.begin(args[0],args[1],args[2]);const previous=scope;scope={style:args[0],undo:[],paintChecks:new Map(),coordinatePreparation:!Object.values(args[0]?._layers||{}).some(layer=>layer.type==='custom')};try{return prepare.apply(this,args);}finally{for(const restore of scope.undo)restore();scope=previous;}}
  function preservePhoto(tile,keep){const release=tile.releaseRTT;tile.releaseRTT=function(...params){const saved=keep.map(({index})=>[index,tile.rttObjects[index]]);for(const[index]of saved)tile.rttObjects[index]=undefined;try{return release.apply(this,params);}finally{for(const[index,obj]of saved)tile.rttObjects[index]=obj;for(const row of keep)for(const source of row.sources){const next=rtt._rttFingerprints[source]?.[tile.tileID.key];if(next)tile.rttFingerprint[source]=next;}partialZoomReuses+=saved.length;}};scope.undo.push(()=>{tile.releaseRTT=release;});}
  function differenceCovered(tile){let reason=difference.call(this,tile);if(reason==='none'&&!dirty.has(tile)){if(returns.has(tile))returnMetrics.reasons.none++;return reason;}if((reason==='none'||reason==='zoom')&&dirty.has(tile))reason='sourceTiles';
   if(reason!=='none'&&returns.has(tile))root.RidgeTileTrace?.emit('rtt-invalidated','terrain-rtt',tile.tileID.canonical,{reason});
   if(returns.has(tile)&&Object.hasOwn(returnMetrics.reasons,reason))returnMetrics.reasons[reason]++;
   const saved=imageCoverage.get(tile);
   if(reason==='zoom'&&!dirty.has(tile)&&saved?.owner===tile.rttObjects&&saved.stableZoom){
    // Source keys/revisions/layer order are identical; native fingerprints
    // differ only in zoom. Keep the exact pixels and accept the new zoom.
    for(const [source,rows]of Object.entries(this._rttFingerprints)){const next=rows[tile.tileID.key];if(next)tile.rttFingerprint[source]=next;}
    returnMetrics.zoomReuse++;return'none';
   }
   if(reason==='sourceTiles'&&!dirty.has(tile)&&saved?.owner===tile.rttObjects&&saved.stableZoom&&covered(tile)&&scope?.style&&zoomIndependent(tile,scope.style,this._coordsAscending,this._lastPrepareZoom,undefined,scope.paintChecks)&&lowerQuality(tile,saved.quality,this,root.RidgeTerrainStreaming?.completeCoverage)){
    // Returning source selection may temporarily contain only a coarse parent.
    // Keep sharper finished pixels without waiting for source reactivation.
    returnMetrics.qualityHolds++;return'none';
   }
   if((reason==='sourceTiles'||reason==='zoom')&&cost(tile)>0&&covered(tile)&&!currentReadiness()(tile.tileID)){deferredRefreshes++;return'none';}
   if(reason==='zoom'&&!dirty.has(tile)&&scope?.undo){const keep=(stableStacks.get(tile)||[]).filter(row=>row&&tile.rttObjects[row.index]===row.owner&&row.owner&&zoomIndependent(tile,scope.style,this._coordsAscending,this._lastPrepareZoom,row.ids,scope.paintChecks));if(keep.length)preservePhoto(tile,keep);}
   return reason;
  }
  const rttTypes=new Set(['background','fill','line','raster','hillshade','color-relief']);
  function renderCovered(layer,...args){
   if(args[1]?.context===painter.context)lastRender={style:args[0],state:args[1]};
   const eligible=rttTypes.has(layer.type)||layer.type==='custom'&&layer.implementation?.renderToTerrainTile!==undefined;
   const flush=rtt._prevRenderedToTexture&&!eligible||eligible&&rtt._renderableLayerIds?.at(-1)===layer.id;
   if(!flush)return renderLayer.call(this,layer,...args);
   const index=rtt._prevRenderedToTexture?rtt._stacks.length-1:rtt._stacks.length;
   const painting=(rtt._renderableTiles||[]).filter(tile=>!tile.getRTT(index));
   for(const tile of painting)if(returns.has(tile))returns.get(tile).repainted=true;
   const before=clock(),result=renderLayer.call(this,layer,...args);
   if(painting.length){budget.record(rtt._stacks[index]||[],clock()-before,painting.length);rtt._ridgeCostPerPatch=budget.estimate();}
   if(painting.length){const ids=rtt._stacks[index]||[],sources=[...new Set(ids.map(id=>args[0]._layers[id]?.source).filter(Boolean))];for(const tile of painting)if(tile.getRTT(index)){const rows=stableStacks.get(tile)||[];const stable=zoomIndependent(tile,args[0],rtt._coordsAscending,rtt._lastPrepareZoom,ids);rows[index]=stable?{index,ids:ids.slice(),sources,owner:tile.getRTT(index)}:null;stableStacks.set(tile,rows);}const coverage=readiness();for(const tile of painting)if(tile.getRTT(index)){if(dirty.has(tile)){dirty.delete(tile);readyRefreshes++;}const stable=zoomIndependent(tile,args[0],rtt._coordsAscending,rtt._lastPrepareZoom);recordImageCoverage(tile,coverage,stable,stable?imageryQuality(tile,args[0],rtt._coordsAscending):null);}}
   if(returns.size)for(const tile of rtt._renderableTiles||[]){const pending=returns.get(tile);if(!pending||!tile.getRTT(index))continue;
    returns.delete(tile);returnMetrics.drawn++;if(pending.repainted){returnMetrics.repainted++;root.RidgeTileTrace?.emit('rtt-repaint-submitted','terrain-rtt',tile.tileID.canonical);}if(root.RidgeTileTrace?.enabled?.()!==false)root.RidgeTileTrace?.emit('rtt-draw-submitted','terrain-rtt',tile.tileID.canonical,{elapsedMs:Math.max(0,clock()-pending.at),contributors:Object.entries(rtt._coordsAscending||{}).flatMap(([source,rows])=>(rows[tile.tileID.key]||[]).map(id=>source+'/'+id.canonical.z+'/'+id.canonical.x+'/'+id.canonical.y))});
    returnTimes.push(Math.max(0,clock()-pending.at));if(returnTimes.length>64)returnTimes.shift();latencyCache=null;
   }
   return result;
  }
  rtt.prepareForRender=prepareCovered;rtt._textureDifference=differenceCovered;if(typeof renderLayer==='function')rtt.renderLayer=renderCovered;
  undoRender=()=>{if(manager._getTerrainCoordsForRegularTile===coordinates)manager._getTerrainCoordsForRegularTile=regular;if(rtt.prepareForRender===prepareCovered)rtt.prepareForRender=prepare;if(rtt._textureDifference===differenceCovered)rtt._textureDifference=difference;if(rtt.renderLayer===renderCovered)rtt.renderLayer=renderLayer;};
 }
 manager.update=function(...args){
  const previousScope=scope;scope={};try{
  const previousKeys=manager._renderableTilesKeys.slice();
  inUpdate=true;restored=false;visibleKeys=null;const deferred=new Map(),empty=new Map(),undo=[];
  // The native update releases drapes immediately before deleting a tile that
  // left the frustum. Defer only that release, never explicit invalidations.
  for(const [key,tile] of Object.entries(target)){
   const release=tile.releaseRTT;if(typeof release!=='function')continue;
   tile.releaseRTT=function(...releaseArgs){
    visibleKeys??=new Set(manager._renderableTilesKeys);
    if(!visibleKeys.has(key)&&cost(tile)>0){deferred.set(key,tile);return;}
    if(!visibleKeys.has(key))empty.set(key,tile);
    return release.apply(this,releaseArgs);
   };undo.push(()=>{tile.releaseRTT=release;});
  }
  let changed;
  try{changed=original.update.apply(this,args);}finally{
   inUpdate=false;undo.forEach(fn=>fn());
   for(const [key,tile] of deferred)if(!Object.hasOwn(target,key)){
    if(warm.has(key))drop(key);const size=cost(tile);warm.set(key,{tile,bytes:size,speculative:false});total+=size;
   }
  }
  // Keep a valid rendered cover until its replacement has BOTH imagery and
  // measured DEM coverage. A timeout must never replace it with sea-level DEM.
  const desired=manager._renderableTilesKeys,desiredSet=new Set(desired),previousSet=new Set(previousKeys),chosen=new Map(),waiting=new Set(),heldTargets=new Set(),ancestorCoverage=new Map(),demSources=new Map(),usableTiles=new Map();let requestedDEM=null,descendants=null;
  // With the same draw set and no held transition, no cover is being replaced.
  // Keep native updates/rendering; skip only our extra transition search.
  const reconcile=heldSince.size>0||desired.length!==previousKeys.length||desired.some(key=>!previousSet.has(key));
  const sourceDEM=id=>{if(!demSources.has(id.key))demSources.set(id.key,manager.getSourceTile?.(id,true));return demSources.get(id.key);};
  const hasDEM=id=>!!sourceDEM(id)?.dem;
  const usable=tile=>{if(!usableTiles.has(tile))usableTiles.set(tile,cost(tile)>0&&hasDEM(tile.tileID)&&covered(tile));return usableTiles.get(tile);};
  const geometryReady=id=>{
   const source=sourceDEM(id);if(!source?.dem)return false;
   const loadedZoom=source.tileID?.canonical?.z;if(loadedZoom===undefined)return true;
   requestedDEM??=new Set((manager.tileManager?._inViewTiles?.getAllTiles?.()||[]).filter(t=>!['errored','unloaded'].includes(t.state)).map(t=>{const q=t.tileID.canonical;return q.z+'/'+q.x+'/'+q.y;}));
   const q=id.canonical;for(let z=q.z;z>loadedZoom;z--){const d=2**(q.z-z);if(requestedDEM.has(z+'/'+Math.floor(q.x/d)+'/'+Math.floor(q.y/d)))return false;}
   return true;
  };
  const replacementReady=id=>geometryReady(id)&&currentReadiness()(id);
  const childrenFor=id=>{
   if(!descendants){descendants=new Map();const levels=new Set(desired.map(key=>target[key].tileID.canonical.z));for(const entry of warm.values()){
    const parents=[];for(const z of levels)if(z<entry.tile.tileID.canonical.z){const key=entry.tile.tileID.scaledTo(z).key;if(desiredSet.has(key))parents.push(key);}
    if(!parents.length||!usable(entry.tile))continue;
    for(const key of parents){if(!descendants.has(key))descendants.set(key,[]);descendants.get(key).push(entry);}
   }}return descendants.get(id.key)||[];
  };
  let transitionAllowance=64;
  if(reconcile)for(const key of desired){const child=target[key];if(!child||usable(child)||replacementReady(child.tileID))continue;
   let fallback=false;
   // Rapid zooms can cross more than two levels in one gesture.
   for(let z=child.tileID.canonical.z-1;z>=0;z--){
    const entry=warm.get(child.tileID.scaledTo(z).key);if(!entry||cost(entry.tile)===0||!hasDEM(entry.tile.tileID))continue;
    const ancestorKey=entry.tile.tileID.key;
    if(!ancestorCoverage.has(ancestorKey))ancestorCoverage.set(ancestorKey,desired.every(key=>{const id=target[key].tileID;return!id.equals(entry.tile.tileID)&&!id.isChildOf(entry.tile.tileID)||covered(entry.tile,id);}));
    if(!ancestorCoverage.get(ancestorKey))continue;
    chosen.set(entry.tile.tileID.key,entry);waiting.add(entry.tile.tileID.key);heldTargets.add(key);fallback=true;break;
   }
   if(!fallback){
    // When zooming OUT, keep a complete old child cover until the new parent
    // is ready. Never draw a partial child set over an unready parent.
    const children=childrenFor(child.tileID);
    if(children.length&&root.RidgeTerrainStreaming?.completeCoverage?.(children.map(entry=>entry.tile.tileID))?.(child.tileID)){
     for(const entry of children){chosen.set(entry.tile.tileID.key,entry);waiting.add(entry.tile.tileID.key);}heldTargets.add(key);
    }else if(children.length&&transitionAllowance>=4){
     const partition=root.RidgeTerrainStreaming?.partitionCover?.(child.tileID,children,{maxTiles:transitionAllowance});
     if(partition){
      for(const row of partition){let entry=row.entry;
       if(!entry){const id=row.id;entry=warm.get(id.key);
        if(!entry&&empty.has(id.key))entry={tile:empty.get(id.key),bytes:0,transition:true};
        if(!entry){id.terrainRttPosMatrix32f=child.tileID.terrainRttPosMatrix32f?.slice();
         entry={tile:new child.constructor(id,manager.tileSize),bytes:0,transition:true};transitionPatches++;
        }
       }
       chosen.set(entry.tile.tileID.key,entry);waiting.add(entry.tile.tileID.key);
      }
      transitionAllowance-=partition.length;heldTargets.add(key);partialCoverHolds++;
     }
    }
   }
  }
  if(chosen.size){
   const parents=[...chosen.values()].sort((a,b)=>a.tile.tileID.canonical.z-b.tile.tileID.canonical.z),selected=[];
   for(const entry of parents)if(!selected.some(p=>entry.tile.tileID.isChildOf(p.tile.tileID)))selected.push(entry);
   const filtered=desired.filter(key=>!heldTargets.has(key)&&!selected.some(p=>target[key].tileID.equals(p.tile.tileID)||target[key].tileID.isChildOf(p.tile.tileID)));
   for(const entry of selected){const key=entry.tile.tileID.key;if(warm.delete(key))total-=entry.bytes;target[key]=entry.tile;if(!entry.transition)markReturn(entry.tile,'fallback');filtered.push(key);continuityHolds++;if(!heldSince.has(key))heldSince.set(key,clock());}
   manager._renderableTilesKeys=filtered;
   const filteredSet=new Set(filtered),same=previousKeys.length===filtered.length&&previousKeys.every(key=>filteredSet.has(key));
   if(!same)manager._lastTilesetChange=clock();changed=!same;
  }
  for(const key of heldSince.keys())if(!waiting.has(key))heldSince.delete(key);
  trim();
  // Native RTT refinement releases at most one stale source/zoom drape per
  // frame. Process the visible face first, not the map pivot behind a ridge.
  const focal=focus(),hit=focal?.hit;
  if(hit){
   const scores=new Map();for(const key of manager._renderableTilesKeys){const q=target[key].tileID.canonical,n=2**q.z;
    const dx=Math.max(q.x/n-hit.x,0,hit.x-(q.x+1)/n),dy=Math.max(q.y/n-hit.y,0,hit.y-(q.y+1)/n);
    scores.set(key,root.RidgeTerrainStreaming?root.RidgeTerrainStreaming.priority(target[key].tileID,focal):Math.hypot(dx,dy));}
   manager._renderableTilesKeys.sort((a,b)=>scores.get(a)-scores.get(b));
  }
  if(returns.size)for(const tile of returns.keys())if(target[tile.tileID.key]!==tile&&warm.get(tile.tileID.key)?.tile!==tile)returns.delete(tile);
  return changed||restored;
  }finally{scope=previousScope;}
 };
 manager.releaseRTT=function(id){
  // Geometry-only DEM arrivals do not change the colour drape. Keep both
  // visible and warm textures; native elevation/depth bookkeeping still runs.
  if(!shouldInvalidate()){geometryOnlyEvents++;return;}
  if(contentUpdate()){
   for(const tile of [...Object.values(target),...[...warm.values()].map(entry=>entry.tile)])
    if(cost(tile)>0&&(tile.tileID.equals(id)||tile.tileID.isChildOf(id)||id.isChildOf(tile.tileID))){dirty.add(tile);invalidations++;}
   return;
  }
  if(imageryUpdate()){
   for(const tile of [...Object.values(target),...[...warm.values()].map(entry=>entry.tile)])
    if(cost(tile)>0&&(tile.tileID.equals(id)||tile.tileID.isChildOf(id)||id.isChildOf(tile.tileID))){
     const event=imageryEvent(),fingerprint=event?.sourceId?tile.rttFingerprint?.[event.sourceId]:null;
     if(event?.sourceId){
      // New/coarser/hidden-provider tiles cannot change pixels baked from
      // other source tiles. Native source-key changes handle actual refinement.
      if(!fingerprint){returnMetrics.ignoredImageryEvents++;continue;}
      if(typeof fingerprint._tileKeys==='string'){let keys=fingerprintKeys.get(fingerprint);if(!keys){keys=new Set(fingerprint._tileKeys.split(','));fingerprintKeys.set(fingerprint,keys);}
      if(!keys.has(String(id.key))){returnMetrics.ignoredImageryEvents++;continue;}}
     }
     dirty.add(tile);invalidations++;
    }
   return;
  }
  for(const [key,{tile}] of warm)if(tile.tileID.equals(id)||tile.tileID.isChildOf(id)||id.isChildOf(tile.tileID)){invalidations++;drop(key);}
  return original.releaseRTT.call(this,id);
 };
 manager.releaseAllRTT=function(...args){fullInvalidations++;flush();return original.releaseAllRTT.apply(this,args);};
 function restore(){if(disposed)return;disposed=true;flush();undoRender();manager._tiles=target;Object.assign(manager,original);}
 manager.destruct=function(...args){restore();return original.destruct.apply(this,args);};
 let plannedTransform=null,plannedRows=[];
 function planComposition(where){
  if(!where?.transform||!lastRender||!root.RidgeTerrainComposition||!root.RidgePrepareAhead?.planExpandedView)return[];
  const visible=manager.getRenderableTiles();if(!visible.length)return[];
  if(plannedTransform!==where.transform){plannedRows=root.RidgePrepareAhead.planExpandedView({ _camera:{transform:lastRender.state.transform} },{transform:where.transform,focus:focus(),visible:visible.map(t=>t.tileID),options:{tileSize:manager.tileSize,minzoom:manager.minzoom||0,maxzoom:manager.maxzoom??22,terrain:painter.renderToTexture.terrain}});plannedTransform=where.transform;}
  const rows=plannedRows,estimate=root.RidgeTerrainComposition.estimate(lastRender.style,lastRender.state,rtt.rttSize);
  return rows.map(id=>({id,estimate})).filter(({id})=>!Object.hasOwn(target,id.key)&&!warm.has(id.key)).slice(0,Math.max(0,Math.floor((limit*.9-total)/Math.max(1,estimate))));
 }
 function prepareComposition(id){
  if(disposed||!lastRender||!root.RidgeTerrainComposition||!root.RidgeTerrainStreaming?.completeCoverage)return'unsupported';
  if(Object.hasOwn(target,id.key)||warm.has(id.key))return'reused';
  const visible=manager.getRenderableTiles(),example=visible[0];if(!example)return'unsupported';
  const reserve=root.RidgeTerrainComposition.estimate(lastRender.style,lastRender.state,rtt.rttSize);if(total+reserve>limit*.9)return'pressure';
  // Cache owns completed patches only; failed/incomplete composition is atomic.
  const tile=new example.constructor(id,manager.tileSize);let committed=false;
  const sourceCache=manager._sourceTileCache,hadSource=Object.hasOwn(sourceCache,id.key);
  try{
   if(!manager.getSourceTile(id,true)?.dem)return'coverage';
   const result=root.RidgeTerrainComposition.compose({manager,painter,tile,...lastRender,coverage:root.RidgeTerrainStreaming.completeCoverage});
   if(!result||!result.coverage(id)||!cost(tile))return'coverage';
   if(total+cost(tile)>limit*.9)return'pressure';
   recordImageCoverage(tile,result.coverage,result.zoomIndependent===true,result.quality||null);warm.set(id.key,{tile,bytes:cost(tile),speculative:true});total+=cost(tile);committed=true;return'prepared';
  }finally{if(!hadSource)delete sourceCache[id.key];if(!committed)tile.releaseRTT(painter);}
 }
 return{hasWaitingCovers:()=>heldSince.size>0,flush,recordImageCoverage,planComposition,prepareComposition,setBudget(value){const before=total;limit=Math.max(0,value);trim();return total<before;},
  resources:()=>({visible:manager._renderableTilesKeys.map(k=>target[k]).filter(Boolean),warm:[...warm.values()].map(e=>e.tile)}),
  protectedDEMs(){const result=new Set();for(const tile of [...manager._renderableTilesKeys.map(key=>target[key]).filter(Boolean),...[...warm.values()].map(entry=>entry.tile)]){const dem=manager.getSourceTile?.(tile.tileID,true);if(dem?.dem)result.add(dem);}return result;},
  relieveDEMPressure(){if(!warm.size)return false;drop(victim(),true);demPressureDrops++;return true;},
  destroy:restore,stats:()=>({...budget.stats(),terrainCoordinatePairsVisited:coordinateCandidates,terrainCoordinatePairsSkipped:coordinateSkipped,partialZoomStackReuses:partialZoomReuses,refreshBacklog:rtt?.ridgeRefreshBacklog||0,refreshesThisFrame:rtt?.ridgeRefreshes||0,estimatedPatchSubmissionMs:rtt?._ridgeCostPerPatch??null,residentDrapes:warm.size,residentBytes:Math.round(total),budgetBytes:limit,reuses:hits,evictions,invalidations,fullInvalidations,geometryOnlyEvents,continuityHolds,partialCoverHolds,transitionPatches,demPressureDrops,waitingCovers:heldSince.size,deferredRefreshes,readyRefreshes,cacheReturns:{...returnMetrics,reasons:{...returnMetrics.reasons},pending:returns.size,latencyMs:latencyCache??=(function(){const sorted=returnTimes.slice().sort((a,b)=>a-b);return{samples:sorted.length,p50:sorted[Math.floor((sorted.length-1)*.5)]??null,p95:sorted[Math.floor((sorted.length-1)*.95)]??null,max:sorted.at(-1)??null};})()}})};
}
// The pinned engine invalidates colour RTTs on every source tile arrival,
// including the geometry DEM. Track that synchronous event without replacing
// its camera, depth, elevation or symbol-placement work. Unknown paths use the
// full native invalidation. Any style layer using this DEM also opts out.
function terrainEventContext(map){
 const original=map._handleTerrainDataEvent;let geometryOnly=false,imagery=false,currentImagery=null,vectorContent=false;
 if(typeof original!=='function')return{shouldInvalidate:()=>true,isImageryUpdate:()=>false,imageryEvent:()=>null,isVectorContent:()=>false,destroy(){}};
 function handle(event,terrainSourceId){
  const previous=geometryOnly,previousImagery=imagery,previousEvent=currentImagery,previousVector=vectorContent;
  vectorContent=event.dataType==='source'&&!!event.tile&&event.sourceDataType==='content'&&map.style?.tileManagers?.[event.sourceId]?.getSource?.()?.type==='vector';
  imagery=event.dataType==='source'&&!!event.tile&&map.style?.tileManagers?.[event.sourceId]?.getSource?.()?.type==='raster';
  currentImagery=imagery?event:null;
  geometryOnly=event.dataType==='source'&&!!event.tile&&event.sourceId===terrainSourceId&&
   map.style?.tileManagers?.[terrainSourceId]?.getSource?.()?.type==='raster-dem'&&
   !Object.values(map.style?._layers||{}).some(layer=>layer.source===terrainSourceId||layer.type==='custom');
  try{return original.call(this,event,terrainSourceId);}finally{geometryOnly=previous;imagery=previousImagery;currentImagery=previousEvent;vectorContent=previousVector;}
 }
 map._handleTerrainDataEvent=handle;
 return{shouldInvalidate:()=>!geometryOnly,isImageryUpdate:()=>imagery,imageryEvent:()=>currentImagery,isVectorContent:()=>vectorContent,destroy(){if(map._handleTerrainDataEvent===handle)map._handleTerrainDataEvent=original;}};
}
// refreshTiles marks an errored raster 'expired', which hasData() treats as
// renderable even when no texture exists. Re-enter normal native selection as
// a fresh loading tile, preserving parent fallback until upload is complete.
function retryFailedTile(map,sourceId,id){
 const manager=map.style?.tileManagers?.[sourceId],tile=manager?.getTile?.(id);
 if(tile?.state!=='errored'||typeof manager._removeTile!=='function'||typeof map._update!=='function')return false;
 if((tile.texture||tile.dem)&&typeof manager._reloadTile==='function'){manager._reloadTile(id.key,'reloading');return true;}
 manager._removeTile(id.key);map._update();return true;
}
// Bound only unused recycled targets, never textures owned by visible/warm
// tiles. Upstream clears this pool at idle; a long gesture may never go idle.
function boundRecyclePool(painter,bytes=16*1048576){
 if(!Array.isArray(painter?._rttObjectRecyclePool)||typeof painter.releaseRTT!=='function')return null;
 const original=painter.releaseRTT;let drops=0;
 const trim=()=>{const pool=painter._rttObjectRecyclePool;let used=pool.reduce((n,t)=>n+t.size*t.size*4*4/3,0);
  while(used>bytes&&pool.length){const t=pool.shift();used-=t.size*t.size*4*4/3;t.texture.destroy();drops++;}return Math.round(used);};
 function release(...args){const value=original.apply(this,args);trim();return value;}
 painter.releaseRTT=release;trim();return{setBudget(value){if(Number.isFinite(value)&&value>=0&&value!==bytes){bytes=value;trim();}},destroy(){if(painter.releaseRTT===release)painter.releaseRTT=original;},stats:()=>({spareRTTBytes:trim(),spareRTTBudgetBytes:bytes,spareRTTDrops:drops})};
}
function attach(map,{engineVersion,demDetail=()=>.25,imageryDetail=()=>.25,residentMB=()=>96,cacheMiB=null,focus=()=>null,loadingLimits=()=>null}={}){
 let constrained=false,current=null,cache=null,detail=null,dead=false;const Retry=root.RidgeTileRetry||(typeof require==='function'?require('./tile-retry.js'):null),retry=Retry.create(map,retryFailedTile,{setTimer:typeof setTimeout==='function'?setTimeout:undefined,clearTimer:typeof clearTimeout==='function'?clearTimeout:undefined});
 const cachePlan=()=>cacheMiB&&root.RidgeTiles?.budgetPlan?root.RidgeTiles.budgetPlan(cacheMiB(),{memory:root.navigator?.deviceMemory||8,saveData:!!root.navigator?.connection?.saveData}):null;
 const sources=engineVersion==='6.14.0'?root.RidgeRasterResidency?.createPool({bytes:(root.navigator?.connection?.saveData?32:(root.navigator?.deviceMemory||8)<4?64:128)*1048576,pendingLimit:root.navigator?.connection?.saveData?2:4,priority:id=>root.RidgeTerrainStreaming?.priority(id,focus())}):null;
 const compatible=engineVersion==='6.14.0';
 let sourceRecycled=compatible?root.RidgeResources?.boundSourceRecycler(map.painter):null;
 let recycled=compatible?boundRecyclePool(map.painter,root.navigator?.connection?.saveData?8*1048576:16*1048576):null;
 const elevation=compatible?root.RidgeRasterResidency?.createPool({bytes:(root.navigator?.connection?.saveData?12:(root.navigator?.deviceMemory||8)<4?24:48)*1048576,pendingLimit:root.navigator?.connection?.saveData?1:2,types:['raster-dem'],protect:()=>cache?.protectedDEMs()||new Set(),relieve:()=>cache?.relieveDEMPressure()||false,priority:id=>root.RidgeTerrainStreaming?.priority(id,focus())}):null;
 const events=compatible?terrainEventContext(map):null;
 const visibility=compatible?root.RidgeTerrainVisibility?.attach(map,{engineVersion}):null;
 function setLoadingLimits(values){if(!values)return;sources?.setPendingLimit(values.imageryPending);elevation?.setPendingLimit(values.demPending);}
 function sync(){
  if(dead||!compatible)return;retry.prune();
  setLoadingLimits(loadingLimits());
  const budget=cachePlan(),viewBytes=(budget?.views??residentMB())*1048576*(constrained?.7:1);
  for(const id of ['linz','satellite','topo'])sources?.attach(map.style?.tileManagers?.[id]);
  for(const id of ['terrain','terrain-fallback','shade-dem'])elevation?.attach(map.style?.tileManagers?.[id]);
  const terrain=map.terrain,manager=terrain?.tileManager;
  if(manager!==current){cache?.destroy();cache=null;current=manager;detail=null;if(manager&&map.painter)cache=retainDrapes(manager,map.painter,{bytes:viewBytes,focus,shouldInvalidate:events.shouldInvalidate,imageryUpdate:events.isImageryUpdate,imageryEvent:events.imageryEvent,contentUpdate:events.isVectorContent,readiness:()=>root.RidgeTerrainStreaming?.imageReadiness(map)||(()=>true)});}
  if(cache?.setBudget(viewBytes))map.triggerRepaint();
  if(budget){sources?.setBudget(budget.imagery*1048576*(constrained?.7:1));elevation?.setBudget(budget.elevation*1048576*(constrained?.7:1));const sourceAllowance=Math.min(8*1048576,budget.spare*1048576*.25);sourceRecycled?.setBudget(sourceAllowance);recycled?.setBudget(budget.spare*1048576-(sourceRecycled?sourceAllowance:0));}
  if(!manager||!cache)return;
  const next=Math.round(512/2**Math.max(0,Math.min(1.5,demDetail())));
  const imageSize=Math.round(next*terrain.qualityFactor*2**imageryDetail()),stamp=next+'/'+imageSize;
  if(detail===stamp)return;
  manager.releaseAllRTT();manager.tileSize=next;
  // Source and rendered tile selection must use the same terrain detail scale.
  manager.tileManager.tileSize=next;
  if(map.painter.renderToTexture)map.painter.renderToTexture.rttSize=imageSize;
  detail=stamp;map.triggerRepaint();
 }
 function onError(e){if(!dead&&compatible&&!root.RidgePrepareAhead?.isBackgroundError?.(map,e))retry.error(e);}
 function onData(e){retry.data(e);}
 function onContextRestored(){if(dead||!compatible)return;retry.reset();cache?.destroy();cache=null;current=null;recycled?.destroy();sourceRecycled?.destroy();sourceRecycled=root.RidgeResources?.boundSourceRecycler(map.painter);recycled=boundRecyclePool(map.painter,root.navigator?.connection?.saveData?8*1048576:16*1048576);sync();map.triggerRepaint();}
 const online=()=>{if(!dead&&compatible)retry.recover();};root.addEventListener?.('online',online);
 function destroy(){if(dead)return;dead=true;root.removeEventListener?.('online',online);retry.destroy();sources?.destroy();elevation?.destroy();cache?.destroy();visibility?.destroy();events?.destroy();recycled?.destroy();sourceRecycled?.destroy();for(const [name,fn] of listeners)map.off(name,fn);}
 const listeners=[['terrain',sync],['error',onError],['sourcedata',onData],['webglcontextlost',()=>{retry.reset();cache?.flush();sources?.flush();elevation?.flush();}],['webglcontextrestored',onContextRestored],['remove',destroy]];listeners.forEach(([n,f])=>map.on(n,f));sync();
 function resources(byteStats={}){const dem=elevation?.stats();return root.RidgeResources?.snapshot(map,{drapes:cache?.resources(),byteStats,cacheBudgetMiB:cachePlan()?.totalMiB??352,offscreenDEMOverBudget:!!dem&&dem.sourceResidentBytes>dem.sourceBudgetBytes});}
 return{hasWaitingCovers:()=>cache?.hasWaitingCovers()||false,setPressure(value){constrained=!!value;sync();},refresh:sync,setLoadingLimits,destroy,resources,planComposition:where=>cache?.planComposition(where)||[],prepareComposition:id=>cache?.prepareComposition(id)||'unsupported',stats:()=>({...retry.stats(),supported:compatible,cacheBudgetMiB:cachePlan()?.totalMiB??null,...visibility?.stats(),...cache?.stats(),...sources?.stats(),...recycled?.stats(),...sourceRecycled?.stats(),...Object.fromEntries(Object.entries(elevation?.stats()||{}).map(([key,value])=>['dem'+key[0].toUpperCase()+key.slice(1),value]))})};
}
const api={compositionBudget,relatedCoordinates,zoomIndependent,imageryQuality,retainDrapes,terrainEventContext,retryFailedTile,boundRecyclePool,attach};if(typeof module!=='undefined')module.exports=api;else root.RidgeResidency=api;
})(globalThis);
