const once=(text,from,to)=>{if(text.split(from).length!==2)throw Error('Additional audit integration changed: '+from.slice(0,85));return text.replace(from,to);};
export function additionalEngine(text,queue){
 text=once(text,'touchmove(e){this._map.fire(new er(e.type,this._map,e))}','touchmove(e){if(this._map.listens("touchmove"))this._map.fire(new er(e.type,this._map,e))}');
 let q=queue;
 q=once(q,"request.headers||={};request.headers.accept=", "request.ridgeImageBitmapOptions=options;request.headers||={};request.headers.accept=");
 q=once(q,'j.onSuccess({data,cacheControl:response.cacheControl,expires:response.expires});','j.onSuccess({data,cacheControl:response.cacheControl,expires:response.expires,ridgeFallback:response.ridgeFallback});');
 text=once(text,queue.trim(),q.trim());
 text=once(text,'if(delete e.abortController,e.aborted){e.state=`unloaded`;return}if(i){','if(delete e.abortController,e.aborted){if(i?.ridgeOwned)i.data?.close?.();e.state=`unloaded`;return}if(i){');
 const start=text.indexOf('let t=this.map.painter.context,r=t.gl,a=i.data??new xf'),end=text.indexOf('e.state=`loaded`',start)+'e.state=`loaded`'.length;
 if(start<0||end<start)throw Error('Raster upload integration changed');
 const body=text.slice(start,end);
 text=text.slice(0,start)+`const ridgeOwned=i.ridgeOwned?i.data:null;try{${body.replace('e.state=`loaded`','e._ridgeFallback=i.ridgeFallback||null,e.state=`loaded`')}}finally{ridgeOwned?.close?.();}`+text.slice(end);
 text=once(text,'catch(t){e.state=`errored`,t.status===404?', 'catch(t){e.state=e.texture&&e._ridgeFallback?`loaded`:`errored`,t.status===404?');
 return text;
}
export function additionalWeb(name,text){
 const r=(from,to)=>{text=once(text,from,to);};
 if(name==='terrain-streaming.js'){
  r('const readinessChecks=new WeakMap();',`// Native imagery demand covers the camera frustum, not every off-screen
// corner of a terrain patch. Waiting for those corners can block refinement
// forever after all requested images have arrived. Unknown demand keeps the
// conservative full-patch rule.
function visibleCoverage(manager,ids,complete=completeCoverage(ids)){
 const wanted=manager?._ridgeIdeal?.ids;
 if(!Array.isArray(wanted)||!wanted.length)return complete;
 return id=>{
  if(complete(id))return true;
  const p=id.canonical||id,wrap=id.wrap||0;
  for(const target of wanted){
   if((target.wrap||0)!==wrap)continue;
   const q=target.canonical||target,z=Math.min(p.z,q.z);
   if(Math.floor(p.x/2**(p.z-z))!==Math.floor(q.x/2**(q.z-z))||Math.floor(p.y/2**(p.z-z))!==Math.floor(q.y/2**(q.z-z)))continue;
   if(!complete(q.z>p.z?target:id))return false;
  }
  return true;
 };
}
const readinessChecks=new WeakMap();`);
  r('return old.check;','return visibleCoverage(manager,ids,old.check);');
  r(' return check;\n}\nfunction imageReadiness', ' return visibleCoverage(manager,ids,check);\n}\nfunction imageReadiness');
  r('priority,completeCoverage,partitionCover,imageReadiness','priority,completeCoverage,visibleCoverage,partitionCover,imageReadiness');
 }
 if(name==='selection-interval.js')r("event.sourceDataType==='metadata'||event.tile?.dem||event.sourceDataType==='content'&&source?.type!=='raster'","event.sourceDataType==='metadata'||event.tile?.dem||event.sourceDataType==='content'");
 if(name==='maplibre-direct-sampling.js'){
  // Direct ownership represents one complete draped surface. If symbols or
  // circles separate terrain stacks, let native composition own every pass.
  r("const eligible=on&&map.getProjection",`let stackCount=0,inStack=false;
  for(const layer of visible){const draped=['background','fill','line','raster','hillshade','color-relief'].includes(layer.type);if(draped&&!inStack)stackCount++;inStack=draped;}
  const eligible=on&&stackCount===1&&map.getProjection`);
  r('if(!manager||!Array.isArray(coords))return false;',`if(!manager)return false;
  if(coords===undefined&&rtt._coordsAscending[layer.source]&&!tile.getRTT(0))continue;
  if(!Array.isArray(coords))return false;`);
  r('let on=!!enabled,dead=false,frames=', 'let epoch=0,on=!!enabled,dead=false,frames=');
  r('const rtt=p.renderToTexture,state=frames.get(rtt),rows=state?.plans.get(tile);',`const rtt=p.renderToTexture,current=frames.get(rtt),saved=tile._ridgeDirectCoverage;
  const fallback=!tile.getRTT(0)&&saved?.valid?.()&&map.style?._layers?.[saved.state.base.id]===saved.state.base;
  const state=current?.plans.has(tile)?current:fallback?saved.state:null,rows=current?.plans.get(tile)||(fallback?saved.rows:null);`);
  r('if(!on||rtt._stacks.length!==1||!rows||frame.data.isRenderingGlobe)return false;','if(dead||rtt._stacks.length!==1||!rows||frame.data.isRenderingGlobe||!on&&!fallback)return false;');
  r("mesh.indexBuffer,mesh.segments);return true;",`mesh.indexBuffer,mesh.segments);
  const snapshot=rows.map(row=>({row,texture:row.image.texture})),stamp=epoch,owner=tile.tileID.canonical;
  const committed={base:state.base,background:state.background,spin:state.spin,adjust:state.adjust};
  tile._ridgeDirectCoverage={state:committed,rows,valid:()=>!dead&&epoch===stamp&&snapshot.every(v=>v.row.image.texture===v.texture&&v.texture?.texture!==null),coverage:id=>{const q=id.canonical,d=2**(q.z-owner.z);return q.z>=owner.z&&(id.wrap||0)===(tile.tileID.wrap||0)&&Math.floor(q.x/d)===owner.x&&Math.floor(q.y/d)===owner.y&&covers(id,rows);}};
  return true;`);
  r('function restored(){transparent=null;', 'function restored(){epoch++;transparent=null;');
 }
 if(name==='native-residency.js'){
  r('minZoom:q.z','minZoom:style.tileManagers[layer.source].getTile?.(id)?._ridgeFallback?.z??q.z');
  r('function lowerQuality(tile,saved,renderer,coverage){','function lowerQuality(tile,saved,renderer,coverage,style){');
  r('lowerQuality(tile,saved.quality,this,root.RidgeTerrainStreaming?.completeCoverage)','lowerQuality(tile,saved.quality,this,root.RidgeTerrainStreaming?.completeCoverage,scope.style)');
  r('ids.filter(id=>id.canonical.z>=r.minZoom)','ids.filter(id=>(style?.tileManagers?.[entry.source]?.getTile?.(id)?._ridgeFallback?.z??id.canonical.z)>=r.minZoom)');
  r('checks.set(r.minZoom,coverage(ids.filter(id=>(style?.tileManagers?.[entry.source]?.getTile?.(id)?._ridgeFallback?.z??id.canonical.z)>=r.minZoom)))',`checks.set(r.minZoom,(()=>{const selected=ids.filter(id=>(style?.tileManagers?.[entry.source]?.getTile?.(id)?._ridgeFallback?.z??id.canonical.z)>=r.minZoom),full=coverage(selected);return root.RidgeTerrainStreaming?.visibleCoverage?.(style?.tileManagers?.[entry.source],selected,full)||full;})())`);
  // MapLibre's colour patch deliberately uses a DEM one zoom lower
  // (deltaZoom), bounded by source maxzoom. A neighbour's finer request must
  // not turn that already complete native dependency into a permanent wait.
  r('if(loadedZoom===undefined)return true;',`if(loadedZoom===undefined)return true;
   const sourceMax=manager.tileManager?._source?.maxzoom??id.canonical.z;
   const requestedZoom=Math.min((id.overscaledZ??id.canonical.z)-(manager.deltaZoom??0),sourceMax);
   if(loadedZoom>=requestedZoom)return true;`);
  r('for(let z=q.z;z>loadedZoom;z--)','for(let z=Math.min(q.z,requestedZoom);z>loadedZoom;z--)');
  r('const cost=tile=>', 'const direct=tile=>!!tile?._ridgeDirectCoverage?.valid?.();\n const available=tile=>cost(tile)>0||direct(tile);\n const cost=tile=>');
  text=text.replaceAll('cost(tile)>0','available(tile)');
  // The helper itself must measure RTT cost rather than recurse.
  text=text.replace('const available=tile=>available(tile)||direct(tile);','const available=tile=>cost(tile)>0||direct(tile);');
  r('cost(entry.tile)===0','!available(entry.tile)');
  r('function trim(){while(total>limit&&warm.size)', 'function trim(){while((total>limit||warm.size>128)&&warm.size)');
  r('const covered=(tile,id=tile.tileID)=>{const saved=', 'const covered=(tile,id=tile.tileID)=>{if(direct(tile)&&tile._ridgeDirectCoverage.coverage(id))return true;const saved=');
  r('const before=clock(),result=renderLayer.call(this,layer,...args);','const before=clock(),result=renderLayer.call(this,layer,...args);const painted=painting.filter(tile=>!!tile.getRTT(index));');
  r('budget.record(rtt._stacks[index]||[],clock()-before,painting.length)','budget.record(rtt._stacks[index]||[],clock()-before,painted.length)');
  r('}const coverage=readiness();for(const tile of painting)if(tile.getRTT(index))','}const coverage=painted.length?readiness():null;for(const tile of painted)if(tile.getRTT(index))');
  r("if(tile?.state!=='errored'||typeof manager._removeTile", "if(tile?.state==='loaded'&&tile._ridgeFallback?.retry&&typeof manager._reloadTile==='function'){manager._reloadTile(id.key,'reloading');return true;}\n if(tile?.state!=='errored'||typeof manager._removeTile");
  r("const listeners=[['terrain',sync]", "const listeners=[['moveend',()=>retry.scan()],['terrain',sync]");
  r('recycled=boundRecyclePool(map.painter,root.navigator?.connection?.saveData?8*1048576:16*1048576);sync();map.triggerRepaint();','recycled=boundRecyclePool(map.painter,root.navigator?.connection?.saveData?8*1048576:16*1048576);sync();retry.scan();map.triggerRepaint();');
 }
 if(name==='regional-blend.js'){
  r('hardMask=false,compensate=false,checkCancelled=', 'hardMask=false,compensate=false,bitmap=false,bitmapOptions,checkCancelled=');
  r("checkCancelled();const blob=await result.convertToBlob({type:'image/png'});", `checkCancelled();if(bitmap&&typeof result.transferToImageBitmap==='function')return result.transferToImageBitmap();const blob=await result.convertToBlob({type:'image/png'});`);
 }
 if(name==='regional-imagery-worker.js'){
  r('const data=await RidgeRegionalBlend.composite({...job,mask,checkCancelled});checkCancelled();self.postMessage({id:job.id,data},[data]);',`const data=await RidgeRegionalBlend.composite({...job,mask,checkCancelled});
  try{checkCancelled();self.postMessage({id:job.id,data},[data]);}catch(error){data?.close?.();throw error;}`);
 }
 if(name==='regional-scheduler.js')r('if(j.settled)return;j.settled=true;','if(j.settled){value?.close?.();return;}j.settled=true;');
 if(name==='regional-imagery.js'){
  r("worker.onerror=()=>failWorker(Error('Imagery worker unavailable'));","worker.onerror=event=>{event.preventDefault?.();failWorker(Error('Imagery worker unavailable'));};");
  r('if(!job)return;jobs.delete(data.id);','if(!job){data.data?.close?.();return;}jobs.delete(data.id);');
  r('data.cancelled?job.reject(Scheduler.abort()):data.error?job.reject(Error(data.error)):job.resolve(data.data);','data.cancelled?job.reject(Scheduler.abort()):data.error?job.reject(Error(data.error)):job.resolve(data.data);');
  r('meta:{z:meta.z,x:meta.x,y:meta.y,width:meta.width,height:meta.height}},options.signal','meta:{z:meta.z,x:meta.x,y:meta.y,width:meta.width,height:meta.height},bitmap:options.ridgeBitmap===true,bitmapOptions:options.ridgeBitmapOptions},options.signal');
  r('const data=await schedule(async()=>', 'const data=await schedule(async()=>');
  r("return new Response(data,{headers:{'Content-Type':'image/png','Cache-Control':ttl?'private, max-age='+ttl:'no-store'}});",`const headers=root.RidgeImageResult.headers([a,b],ttl);
   if(options.signal?.aborted){data?.close?.();throw Scheduler.abort();}
   return root.RidgeImageResult.result(data,headers);`);
  // Gap composition passes its explicit bitmap option to the same worker.
  r('return await compose(data,signal);','return await compose({...data,bitmap:options.ridgeBitmap===true,bitmapOptions:options.ridgeBitmapOptions},signal);');
  r("if(options.signal?.aborted)throw Object.assign(Error('Cancelled'),{name:'AbortError'});\n   if(compensate)","if(options.signal?.aborted){data?.close?.();throw Object.assign(Error('Cancelled'),{name:'AbortError'});}\n   if(compensate)");
 }
 if(name==='gap-imagery.js'){
  r("return new Response(data,{headers:{'Content-Type':'image/png','Cache-Control':ttl?'private, max-age='+ttl:'no-store'}});",`const headers=root.RidgeImageResult.headers(pair.filter(v=>v.status==='fulfilled').map(v=>v.value).filter(Boolean),ttl);
  if(dead||options.signal?.aborted){data?.close?.();throw Object.assign(Error('Cancelled'),{name:'AbortError'});}
  return root.RidgeImageResult.result(data,headers);`);
  // The earlier cancellation check must also dispose a completed bitmap.
  r("if(dead||options.signal?.aborted)throw Object.assign(Error('Cancelled'),{name:'AbortError'});\n  stats[coarse?", "if(dead||options.signal?.aborted){data?.close?.();throw Object.assign(Error('Cancelled'),{name:'AbortError'});}\n  stats[coarse?");
 }
 if(name==='tile-cache.js'){
  r('let bytes=0,nextExpiry=Infinity;', 'let bytes=0,nextExpiry=Infinity,bitmapSequence=0;');
  r('let job=pending.get(url);',`const bitmap=returnData&&context.original===true&&!context.overview&&context.imageOptions?.premultiplyAlpha!=='none'&&typeof root.ImageBitmap==='function',pendingKey=bitmap?url+'#bitmap-'+(++bitmapSequence):url;
  let job=pending.get(pendingKey);`);
  text=text.replaceAll('pending.delete(url)','pending.delete(pendingKey)').replaceAll('pending.set(url,job)','pending.set(pendingKey,job)').replaceAll('pending.get(url)===job','pending.get(pendingKey)===job');
  r('ridgeOriginal:context.original===true,','ridgeOriginal:context.original===true,ridgeBitmap:bitmap,ridgeBitmapOptions:context.imageOptions,');
  r('const data=await response.arrayBuffer();', 'const data=response.ridgeBitmap||await response.arrayBuffer();job.fallback=root.RidgeImageResult.fallback(response.headers);');
  r('if(controller.signal.aborted)throw aborted();\n    const cc=', 'if(controller.signal.aborted){data?.close?.();throw aborted();}\n    const cc=');
  r('if(job.until)store(url,','if(job.until&&!response.ridgeBitmap)store(url,');
  r('return data;\n   }finally', 'job.bitmap=response.ridgeBitmap||null;return data;\n   }finally');
  r('if(release())resolve(returnData?{data:data.slice(0),', 'if(release())resolve(returnData?{data:job.bitmap||data.slice(0),ridgeOwned:!!job.bitmap,ridgeFallback:job.fallback,');
  r('}:{});},error=>{if(release())reject(error);});', '}:{});else job.bitmap?.close?.();},error=>{if(release())reject(error);});');
  r("installed.load(request.url,controller.signal,{original:true})","installed.load(request.url,controller.signal,{original:true,imageOptions:request.ridgeImageBitmapOptions})");
 }
 return text;
}
