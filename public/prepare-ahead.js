/* Optional expanded-frustum preparation after visible idle. Native TileManager/cache owns
   every tile; no hidden map, camera mutation or extra cache. */
(function(root){
'use strict';
const MiB=1048576,backgroundTiles=new WeakSet();
function isBackgroundError(map,event){const tile=event.tile;return !!tile&&backgroundTiles.has(tile)&&map.style?.tileManagers?.[event.sourceId]?.getTile?.(tile.tileID)!==tile;}
// A larger virtual viewport at the same camera position and pixel density.
// Extra horizontal angle is adjustable from 30 to 70 degrees in total.
// No live transform, draw buffer, source selection or camera is changed.
const EXTRA_DEGREES=30,MAX_PLANNED=1024;
function normalizeAngle(value){return Number.isFinite(value)?Math.max(30,Math.min(70,Math.round(value))):30;}
function expandedTransform(transform,extraDegrees=30){
 if(!transform?.clone||!transform.width||!transform.height)return null;
 const copy=transform.clone(),v=transform.fov*Math.PI/180,h=2*Math.atan(Math.tan(v/2)*transform.width/transform.height);
 const wanted=Math.min(175*Math.PI/180,h+normalizeAngle(extraDegrees)*Math.PI/180);
 const scale=Math.min(16,16384/transform.width,16384/transform.height,Math.tan(wanted/2)/Math.tan(h/2),Math.tan(80*Math.PI/180)/Math.tan(v/2));
 if(!Number.isFinite(scale)||scale<=1)return null;
 copy.resize(transform.width*scale,transform.height*scale);copy.setFov(2*Math.atan(Math.tan(v/2)*scale)*180/Math.PI);
 if(copy.zoom!==transform.zoom||Math.abs(copy.center.lng-transform.center.lng)>1e-9||Math.abs(copy.center.lat-transform.center.lat)>1e-9||Math.abs(copy.cameraToCenterDistance/transform.cameraToCenterDistance-1)>1e-8)return null;
 return copy;
}
// Distance in viewing angle from the current screen rectangle to a tile's
// projected footprint. No terrain raycasts, GL calls or shared lookup writes.
function angularPriority(id,transform){
 const q=id.canonical||id,n=2**q.z,f=transform?.cameraToCenterDistance,c=transform?.centerPoint;
 if(!c||!Number.isFinite(f)||f<=0||!transform._coordinateClipPoint)return Infinity;
 const matrix=transform._pixelMatrix3D||transform._pixelMatrix;if(!matrix)return Infinity;
 const xs=[],ys=[];for(const [dx,dy]of [[0,0],[1,0],[1,1],[0,1],[.5,.5]]){
  const clip=transform._coordinateClipPoint({x:(q.x+dx)/n,y:(q.y+dy)/n},transform.elevation||0,matrix);if(!(clip[3]>0))continue;
  const x=clip[0]/clip[3],y=clip[1]/clip[3];if(Number.isFinite(x)&&Number.isFinite(y)){xs.push(Math.atan((x-c.x)/f));ys.push(Math.atan((y-c.y)/f));}
 }
 if(!xs.length)return Infinity;
 const left=Math.atan(-c.x/f),right=Math.atan((transform.width-c.x)/f),top=Math.atan(-c.y/f),bottom=Math.atan((transform.height-c.y)/f);
 const dx=Math.max(left-Math.max(...xs),Math.min(...xs)-right,0),dy=Math.max(top-Math.max(...ys),Math.min(...ys)-bottom,0);
 return Math.hypot(dx,dy)*180/Math.PI;
}
function orderByAngle(ids,transform,focus){
 const scores=new Map();for(const id of ids)scores.set(id,{angle:angularPriority(id,transform),distance:root.RidgeTerrainStreaming?.priority(id,focus)||0});
 return ids.sort((a,b)=>{const x=scores.get(a),y=scores.get(b);return x.angle-y.angle||x.distance-y.distance;});
}
function planExpandedView(map,{engine=root.maplibregl,options={},visible=[],focus=null,transform}={}){
 const live=map._camera?.transform,expanded=transform||expandedTransform(live);if(!live||!expanded||typeof engine?.Map?.prototype.coveringTiles!=='function')return[];
 const settings={...options},state=focus,provider=expanded.getCoveringTilesDetailsProvider(),distance=provider.distanceToTile2d,previousTile=state?.tile;
 // Source and renderer focus weights must track the candidate tile on this
 // temporary traversal, then relinquish the shared focus state synchronously.
 provider.distanceToTile2d=function(...args){if(state)state.tile=args[2];return distance.apply(this,args);};
 try{
 if(!settings.calculateTileZoom&&!Object.hasOwn(settings,'maxContentElevation'))live.getCoveringTilesDetailsProvider().allowVariableZoom(live,settings);
 const policy=settings.calculateTileZoom;if(policy)settings.calculateTileZoom=(z,xy,dz,range)=>policy(z,xy,dz,range,live.fov);
 // Elevation bounds may populate lookup caches during traversal. Keep those
 // writes on shadow owners rather than adding off-screen IDs to the live map.
 if(settings.terrain?.tileManager){const terrain=settings.terrain,manager=terrain.tileManager;settings.terrain=Object.assign(Object.create(terrain),{tileManager:Object.assign(Object.create(manager),{_sourceTileCache:{...manager._sourceTileCache}}),_demMatrixCache:new Map(terrain._demMatrixCache||[])});}
  const covered=root.RidgeTerrainStreaming?.completeCoverage?.(visible),seen=new Set();
  const rows=engine.Map.prototype.coveringTiles.call({_camera:{transform:expanded}},settings).filter(id=>{
   const q=id.canonical,key=q.z+'/'+q.x+'/'+q.y;if(seen.has(key)||covered?.(id))return false;seen.add(key);
   const n=2**q.z,west=q.x/n*360-180,east=(q.x+1)/n*360-180,lat=y=>Math.atan(Math.sinh(Math.PI*(1-2*y/n)))*180/Math.PI;
   return id.wrap===0&&east>=165&&west<=180&&lat(q.y)>=-49&&lat(q.y+1)<=-33;
  });
  return orderByAngle(rows,live,state).slice(0,MAX_PLANNED);
 }finally{provider.distanceToTile2d=distance;if(state)state.tile=previousTile;}
}
const limits={sourceRate:96,sourceFraction:.6,sourceMiB:64,compositionRate:48,compositionTasks:64,compositionFraction:.6,compositionMiB:64};
const cached=(manager,id)=>manager?._outOfViewCache?.data?.[id.wrapped().key]?.find(row=>row.value.tileID.equals(id))?.value;
function discard(manager,tile){
 // Never abort or unload a tile that native selection has adopted into view.
 if(!tile||manager.getTile?.(tile.tileID)===tile||tile.uses>0)return false;
 const cache=manager._outOfViewCache,row=cache?.data?.[tile.tileID.wrapped().key]?.find(row=>row.value===tile);
 if(row){cache.remove(tile.tileID,row);return true;}return false;
}
function attach(map,{enabled=()=>false,angle=()=>30,lowPower=()=>false,basemap=()=> 'linz',focus=()=>null,residency,cache,onProgress=()=>{},engine=root.maplibregl,clock=()=>root.performance.now(),timers=root,document=root.document}={}){
 let dead=false,lost=false,moving=false,timer=null,idle=null,active=null,queue=[],stamp='',batch=0,spent=0,deadline=0,compositionQueue=null,compositionCount=0,compositionSpent=0,compositionResumeAt=0,compositionAtBatch=-1,idleSeen=false,expanded=null;
 const seen=new Map(),recent=[],compositionRecent=[],metrics={started:0,preparedImagery:0,preparedDEM:0,preparedCompositions:0,compositionReuses:0,compositionSkipped:0,compositionMs:0,reused:0,failed:0,cancelled:0,pressurePauses:0};let reason='disabled';
 const supported=engine?.getVersion?.()==='6.14.0'&&typeof engine.MercatorCoordinate?.fromLngLat==='function';
 const healthy=()=>{const h=map._ridgeFrameHealth;return h&&clock()-h.at<15000&&h.p75Ms<=20;};
 const stats=()=>({...metrics,supported,enabled:!!enabled(),extraDegrees:normalizeAngle(angle()),visibleReady:!!enabled()&&visibleReady(),active:active?1:0,phase:active?(active.ready?'GPU preparation':'loading'):null,queued:queue.length,compositionQueued:compositionQueue?.length||0,reason});
 const notify=()=>onProgress(stats());
 const remember=(key,until)=>{seen.set(key,until);while(seen.size>256)seen.delete(seen.keys().next().value);};
 const clearSchedule=()=>{if(timer!==null)timers.clearTimeout(timer);timer=null;if(idle!==null)timers.cancelIdleCallback?.(idle);idle=null;};
 function matches(task){return map.style?.tileManagers?.[task.sourceId]===task.manager&&task.manager.getSource()===task.source&&task.source.tiles?.[0]===task.template&&
  (task.type!=='raster-dem'||map.terrain?.tileManager?.tileManager===task.manager)&&
  (task.type!=='raster'||basemap()===task.sourceId);}
 function cancelActive(){if(!active)return;const task=active;active=null;timers.clearTimeout(task.timeout);
  task.manager.off('data',task.onData);task.manager.off('error',task.onError);
  // Completed native GPU resources remain useful, even if a gesture just began.
  if(task.tile&&!task.tile.texture&&!task.tile.dem&&(task.tile.abortController||task.tile.state==='errored'))discard(task.manager,task.tile);
  metrics.cancelled++;
 }
 function pause(why,reset=false){clearSchedule();cancelActive();reason=why;if(reset){queue=[];stamp='';compositionQueue=null;expanded=null;}notify();}
 function allowed(){return !dead&&supported&&enabled()&&!lowPower()&&!root.navigator?.connection?.saveData&&!document?.hidden&&!lost&&!moving&&!map.isMoving?.();}
 function visibleReady(){return idleSeen&&map.areTilesLoaded?.()===true&&!map.painter?.renderToTexture?.needsFollowUpFrame&&!(residency?.hasWaitingCovers?residency.hasWaitingCovers():residency?.stats?.().waitingCovers>0);}
 function schedule(delay=650){notify();if(dead||timer!==null||idle!==null||!allowed())return;
  timer=timers.setTimeout(()=>{timer=null;if(!allowed())return;
   if(typeof timers.requestIdleCallback==='function')idle=timers.requestIdleCallback(deadline=>{idle=null;if(deadline.timeRemaining()<4){schedule(250);return;}runPump();});
   else runPump();
  },delay);
 }
 function position(){expanded??=expandedTransform(map._camera?.transform,normalizeAngle(angle()));return expanded?{transform:expanded,hit:focus()?.hit}:null;}
 function viewStamp(){const t=map._camera?.transform;if(!t)return'';return[t.center.lng.toFixed(7),t.center.lat.toFixed(7),t.zoom.toFixed(4),t.pitch.toFixed(2),t.bearing.toFixed(2),t.elevation,t.width,t.height,t.fov,basemap(),normalizeAngle(angle())].join('/');}
 function build(){const where=position();if(!where)return[];const tasks=[],demManager=map.terrain?.tileManager?.tileManager,renderTasks=residency?.planComposition?.(where)||[];
  const ids=[basemap(),...Object.keys(map.style?.tileManagers||{}).filter(id=>map.style.tileManagers[id]===demManager)];
  for(const sourceId of new Set(ids)){
   const manager=map.style?.tileManagers?.[sourceId],source=manager?.getSource?.(),type=source?.type;
   if(!['raster','raster-dem'].includes(type)||!source.tiles?.[0]||!manager._sourceLoaded||manager._outOfViewCache?.max!==Number.MAX_SAFE_INTEGER||typeof manager._addTile!=='function'||typeof manager._removeTile!=='function')continue;
   const visible=manager._inViewTiles?.getAllTiles?.()||[];
   const selected=planExpandedView(map,{engine,transform:where.transform,focus:focus(),visible:visible.map(t=>t.tileID),options:{tileSize:source.tileSize||256,minzoom:source.minzoom||0,maxzoom:source.maxzoom??19,roundZoom:!manager.usedForTerrain&&source.roundZoom,reparseOverscaled:source.reparseOverscaled,terrain:map.terrain,calculateTileZoom:source.calculateTileZoom,maxContentElevation:0}});
   // Native rendering needs exact DEM ancestors, and complete imagery cover
   // for each expanded render patch, including patches crossing its outer edge.
   const required=renderTasks.map(({id})=>{const z=Math.max(source.minzoom||0,Math.min(source.maxzoom??19,id.canonical.z-(type==='raster-dem'?(map.terrain.tileManager.deltaZoom||0):0)));return id.scaledTo(z);});
   const unique=new Map();for(const id of [...required,...selected])if(!unique.has(id.key))unique.set(id.key,id);const rows=orderByAngle([...unique.values()],map._camera.transform,focus()).slice(0,MAX_PLANNED);
   for(const id of rows){if(source.hasTile&&!source.hasTile(id)||manager.getTile(id))continue;
    const tile=cached(manager,id);if(tile&&(type==='raster'?tile.texture:tile.demTexture&&!tile.needsTerrainPrepare)){metrics.reused++;continue;}
    const q=id.canonical,key=sourceId+'/'+q.z+'/'+q.x+'/'+q.y;if((seen.get(key)||0)>clock())continue;
    const size=source.tileSize||256,estimate=type==='raster'?Math.ceil(size*size*4*4/3):4*(size+4)**2*4;
    tasks.push({sourceId,manager,source,template:source.tiles[0],type,id,key,estimate});
   }
  }
  const images=tasks.filter(t=>t.type==='raster'),dems=tasks.filter(t=>t.type==='raster-dem'),result=[];
  for(let i=0;i<Math.max(images.length,dems.length);i++){if(images[i])result.push(images[i]);if(dems[i])result.push(dems[i]);}return result;
 }
 function room(task,{s,bytes,pressure}){if(!s?.supported||!bytes||pressure)return false;
  const dem=task.type==='raster-dem',used=dem?s.demSourceResidentBytes:s.sourceResidentBytes,limit=dem?s.demSourceBudgetBytes:s.sourceBudgetBytes;
  const encoded=dem?bytes.terrain:bytes.imagery;
  return !(s.pendingOffscreen||s.demPendingOffscreen)&&Number.isFinite(used)&&Number.isFinite(limit)&&used+task.estimate<=limit*.9&&
   encoded?.bytes<encoded?.budgetBytes*.85&&!(bytes.imagery?.pending||bytes.terrain?.pending)&&
   spent+task.estimate<=Math.min(limits.sourceMiB*MiB,((s.sourceBudgetBytes||0)+(s.demSourceBudgetBytes||0))*limits.sourceFraction);
 }
 function cleanup(task){timers.clearTimeout(task.timeout);task.manager.off('data',task.onData);task.manager.off('error',task.onError);if(active===task)active=null;}
 function fail(task,kind='load error'){if(active!==task)return;cleanup(task);if(!task.tile?.texture&&!task.tile?.dem)discard(task.manager,task.tile);remember(task.key,clock()+30000);metrics.failed++;metrics.lastFailure=kind;schedule(350);}
 function finish(task){
  if(!matches(task)){pause('source changed',true);return;}
  const tile=task.tile;if(!tile||tile.aborted||tile.state!=='loaded'||cached(task.manager,task.id)!==tile&&task.manager.getTile(task.id)!==tile){fail(task,'tile unavailable');return;}
  try{
   if(task.type==='raster-dem'){
    // Native terrain chooses sourceZ = renderZ - deltaZoom. A representative
    // child targets this exact cached DEM without changing the render tile set.
    const q=task.id.canonical,delta=map.terrain.tileManager.deltaZoom||0,z=q.z+delta,ID=task.id.constructor;
    if(z>25)throw Error('Unsupported terrain zoom');const id=new ID(z,0,z,q.x*2**delta,q.y*2**delta);
    const terrain=map.terrain,matrixKey=tile.tileID.key+'/'+id.key,matrices=terrain._demMatrixCache,sources=terrain.tileManager._sourceTileCache;
    const hadMatrix=matrices?.has(matrixKey),hadSource=sources&&Object.hasOwn(sources,id.key);let data;
    try{data=terrain.getTerrainData(id);}finally{
     // These representative render IDs are not added to the draw set. Keep
     // only the GPU DEM, not extra per-render uniform/source lookup entries.
     if(!hadMatrix)matrices?.delete(matrixKey);if(sources&&!hadSource)delete sources[id.key];
    }
    if(data.tile!==tile||!tile.demTexture||tile.needsTerrainPrepare)throw Error('DEM preparation unavailable');metrics.preparedDEM++;
   }else{if(!tile.texture)throw Error('Imagery GPU texture unavailable');metrics.preparedImagery++;}
   cleanup(task);remember(task.key,clock()+60000);reason='ready';schedule(0);
  }catch{fail(task,'preparation failed');}
 }
 function start(task){
  if(!matches(task))return;active=task;task.onData=e=>{if(e.tile===task.tile&&task.tile.state==='loaded'){task.ready=true;timers.clearTimeout(task.timeout);schedule(0);}};task.onError=e=>{if(e.tile===task.tile)fail(task);};
  task.manager.on('data',task.onData);task.manager.on('error',task.onError);task.timeout=timers.setTimeout(()=>fail(task,'timeout'),22000);
  try{task.tile=task.manager._addTile(task.id);backgroundTiles.add(task.tile);task.manager._removeTile(task.id.key,{prepareAhead:true});task.ready=task.tile.state==='loaded';
   if(task.tile.aborted||!cached(task.manager,task.id)&&task.manager.getTile(task.id)!==task.tile){fail(task);return;}
   metrics.started++;recent.push(clock());batch++;spent+=task.estimate;reason='preparing';if(task.ready)schedule(0);
  }catch{fail(task,'preparation failed');}
 }
 function runPump(){try{pump();}finally{notify();}}
 function pump(){
  if(!allowed()){pause('paused');return;}if(active&&!matches(active)){pause('source changed',true);return;}
  if(!visibleReady()){reason='visible tiles first';if(clock()<deadline)schedule(750);return;}
  if(active){if(active.ready)finish(active);return;}
  const nextStamp=viewStamp();if(nextStamp!==stamp){stamp=nextStamp;try{queue=build();}catch{metrics.failed++;pause('expanded view unavailable',true);return;}batch=0;spent=0;deadline=clock()+15000;compositionQueue=null;compositionCount=0;compositionSpent=0;compositionResumeAt=0;compositionAtBatch=-1;}
  while(recent.length&&clock()-recent[0]>60000)recent.shift();
  if(!queue.length){composeNext();return;}
  if(batch>0&&batch%4===0&&compositionAtBatch!==batch){compositionAtBatch=batch;compositionQueue=null;composeNext();schedule(0);return;}
  if(recent.length>=limits.sourceRate*(healthy()?1.5:1)){reason='rate limit';composeNext();if(queue.length&&reason==='ready')reason='rate limit';if(queue.length)schedule(Math.max(1000,Math.min(15000,(recent[0]||clock())+60001-clock())));return;}
  const bytes=cache?.stats?.(),snapshot={s:residency?.stats?.(),bytes,pressure:residency?.resources?.(bytes)?.pressure},blocked=[];
  while(queue.length){const task=queue.shift();if(!matches(task))continue;
   const tile=cached(task.manager,task.id);if(task.manager.getTile(task.id)||tile&&(task.type==='raster'?tile.texture:tile.demTexture&&!tile.needsTerrainPrepare)){metrics.reused++;continue;}
   if(!room(task,snapshot)){blocked.push(task);continue;}
   queue.unshift(...blocked);start(task);return;
  }
  queue.push(...blocked);if(blocked.length){metrics.pressurePauses++;reason='cache or loading pressure';composeNext();if(reason==='ready')reason='cache limit';if(clock()<deadline)schedule(750);return;}composeNext();
 }
 function composeNext(){
  const s=residency?.stats?.(),bytes=cache?.stats?.();
  if(residency?.resources?.(bytes)?.pressure){metrics.pressurePauses++;reason='working set pressure';return;}
  if(!s||!bytes||s.pendingOffscreen||s.demPendingOffscreen||bytes.imagery?.pending||bytes.terrain?.pending){reason='visible tiles first';if(clock()<deadline)schedule(750);return;}
  while(compositionRecent.length&&clock()-compositionRecent[0]>60000)compositionRecent.shift();
  if(clock()<compositionResumeAt){reason='composition cooling down';schedule(compositionResumeAt-clock());return;}
  if(compositionCount>=limits.compositionTasks||compositionRecent.length>=limits.compositionRate*(healthy()?1.5:1)){reason='composition budget';return;}
  if(compositionQueue===null)try{compositionQueue=residency?.planComposition?.(position())||[];}catch{metrics.failed++;compositionQueue=[];reason='expanded view unavailable';return;}
  const task=compositionQueue.shift();if(!task){reason='ready';return;}
  if(s.residentBytes+task.estimate>s.budgetBytes*.9||compositionSpent+task.estimate>Math.min(limits.compositionMiB*MiB,s.budgetBytes*limits.compositionFraction)){metrics.pressurePauses++;reason='composition cache pressure';return;}
  // Large off-screen targets can consume substantial GPU time despite CPU idle.
  if(task.estimate>16*MiB){metrics.pressurePauses++;reason='composition texture budget';return;}
  const before=clock();let result;try{result=residency.prepareComposition(task.id);}catch{result='failed';}
  const elapsed=Math.max(0,clock()-before);metrics.compositionMs+=elapsed;compositionResumeAt=elapsed>8?clock()+Math.min(5000,Math.max(250,elapsed*20)):0;compositionCount++;compositionRecent.push(clock());
  if(result==='prepared'){metrics.preparedCompositions++;compositionSpent+=task.estimate;}else if(result==='reused')metrics.compositionReuses++;else metrics.compositionSkipped++;
  reason=result==='prepared'?'composition ready':'composition '+result;schedule(Math.max(16,compositionResumeAt-clock()));
 }
 function refresh(){pause('view changed',true);if(!allowed()){reason=enabled()?'paused':'disabled';notify();return;}deadline=clock()+15000;schedule();}
 function onIdle(){idleSeen=true;if(!allowed())return;deadline=clock()+15000;schedule();}
 function onStart(){idleSeen=false;moving=true;pause('navigation',true);}
 function onEnd(){moving=false;refresh();}
 function onLoading(e){if(active&&(!e.sourceId||e.sourceId===active.sourceId)&&e.tile?.tileID?.key===active.id.key)return;idleSeen=false;pause('visible tiles first');}
 function onData(e){if(!e.tile&&e.sourceDataType==='content'){seen.clear();pause('source changed',true);refresh();}}
 function onLost(){idleSeen=false;lost=true;pause('context lost',true);seen.clear();}
 function onRestored(){lost=false;seen.clear();refresh();}
 function onVisibility(){if(document?.hidden)pause('hidden');else refresh();}
 function destroy(){if(dead)return;pause('removed',true);dead=true;for(const [n,f]of listeners)map.off(n,f);document?.removeEventListener?.('visibilitychange',onVisibility);seen.clear();}
 const listeners=[['terrain',()=>{idleSeen=false;refresh();}],['resize',()=>{idleSeen=false;refresh();}],['idle',onIdle],['movestart',onStart],['moveend',onEnd],['sourcedataloading',onLoading],['sourcedata',onData],['webglcontextlost',onLost],['webglcontextrestored',onRestored],['remove',destroy]];
 if(supported){for(const [n,f]of listeners)map.on(n,f);document?.addEventListener?.('visibilitychange',onVisibility);refresh();}
 return{refresh,destroy,stats};
}
const api={expandedTransform,planExpandedView,angularPriority,orderByAngle,normalizeAngle,attach,isBackgroundError,EXTRA_DEGREES};if(typeof module!=='undefined')module.exports=api;else root.RidgePrepareAhead=api;
})(globalThis);
