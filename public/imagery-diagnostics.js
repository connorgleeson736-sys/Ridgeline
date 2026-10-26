/* Passive, bounded issue capture. No image copies, GPU readback, polling,
   network requests, URLs, API keys or route coordinates are collected. */
(function(root){
'use strict';
const allowed=new Set(['linz','satellite','topo','terrain','terrain-fallback','shade-dem']);
function attach(map,{stats=()=>({}),capacity=256,stages=true,clock=()=>root.performance.now()}={}){
 const spans=new Map(),durations={},stageRing=[],stageLimit=384;
 const previousStage={'native-image-admitted':'native-image-queued','decode-finished':'decode-start','body-ready':'fetch-start','transform-start':'transform-queued','transform-finished':'transform-start','source-ready-upload-submitted':'source-load-start','rtt-draw-submitted':'rtt-adopted'};
 function stageRecord(row){let span=spans.get(row.id);if(!span){span={};spans.set(row.id,span);while(spans.size>1024)spans.delete(spans.keys().next().value);}
  const previous=previousStage[row.stage],elapsed=Number.isFinite(row.elapsedMs)&&['source-ready-upload-submitted','rtt-draw-submitted'].includes(row.stage)?row.elapsedMs:previous&&span[previous]!==undefined?row.ms-span[previous]:null;
  if(elapsed!==null){const d=durations[row.stage]??={totalSamples:0,values:[],movingSamples:0};d.totalSamples++;if(row.moving)d.movingSamples++;d.values.push(Math.max(0,elapsed));if(d.values.length>512)d.values.shift();}span[row.stage]=row.ms;
  stageRing.push(row);if(stageRing.length>stageLimit)stageRing.shift();
 }
 let capturing=!!stages,unsubscribe=capturing?root.RidgeTileTrace?.subscribe(stageRecord):null;
 function setEnabled(value){value=!!value;if(dead||value===capturing)return;capturing=value;unsubscribe?.();unsubscribe=value?root.RidgeTileTrace?.subscribe(stageRecord):null;lastFrame=null;}
 const ring=new Array(capacity);let cursor=0,count=0,dead=false;
 const frames=new Float32Array(240);let frameCursor=0,frameCount=0,moving=false,lastFrame=null;
 const start=()=>{moving=true;root.RidgeTileTrace?.setMoving(true);lastFrame=null;},end=()=>{moving=false;root.RidgeTileTrace?.setMoving(false);lastFrame=null;};
 const render=()=>{if(dead||!capturing||!moving||root.document?.hidden){lastFrame=null;return;}const now=clock();if(lastFrame!==null){const dt=now-lastFrame;if(dt>0&&dt<=5000){frames[frameCursor]=dt;frameCursor=(frameCursor+1)%frames.length;frameCount=Math.min(frames.length,frameCount+1);}}lastFrame=now;};
 const push=row=>{if(dead)return;ring[cursor]={ms:Math.round(clock()),...row};cursor=(cursor+1)%capacity;count=Math.min(capacity,count+1);};
 const record=(kind,e)=>{if(dead||!allowed.has(e.sourceId)||!capturing&&kind!=='error')return;const q=e.tile?.tileID?.canonical;if(!q)return;
  push({kind,source:e.sourceId,z:q.z,x:q.x,y:q.y,state:e.tile.state,status:e.error?.status||e.error?.statusCode||null,error:e.error?.name||null});
 };
 const rendererError=e=>push({kind:'renderer-error',error:e.error?.name||'Error',textureBindFailure:/\bbind\b/.test(e.message||'')});
 root.addEventListener?.('error',rendererError);
 const loading=e=>record('loading',e),data=e=>{if(e.sourceDataType==='metadata')root.RidgeTileTrace?.resetSource?.(e.sourceId);record('data',e);},lost=()=>push({kind:'webgl-context-lost'}),restored=()=>push({kind:'webgl-context-restored'}),error=e=>{if(!e.sourceId&&e.error)push({kind:'renderer-error',error:e.error.name||'Error',textureBindFailure:/\bbind\b/.test(e.error.message||'')});else record('error',e);};
 function snapshot(){
  const sources={};for(const id of allowed){const manager=map.style?.tileManagers?.[id];if(!manager)continue;
   const active=manager._inViewTiles?.getAllTiles?.()||[],cached=Object.values(manager._outOfViewCache?.data||{}).flatMap(a=>a.map(e=>e.value));
   const rows=tiles=>tiles.slice(0,160).map(t=>({z:t.tileID.canonical.z,x:t.tileID.canonical.x,y:t.tileID.canonical.y,state:t.state,texture:!!t.texture,dem:!!t.dem}));
   sources[id]={active:rows(active),activeCount:active.length,cachedCount:cached.length,cached:rows(cached)};
  }
  const manager=map.terrain?.tileManager,terrain=(manager?.getRenderableTiles?.()||[]).slice(0,256).map(tile=>{
   const q=tile.tileID.canonical,dem=manager.getSourceTile?.(tile.tileID,true);return{z:q.z,x:q.x,y:q.y,dem:!!dem?.dem,demZoom:dem?.tileID?.canonical?.z??null,rttSizes:(tile.rttObjects||[]).filter(Boolean).map(t=>t.size)};
  });
  const intervals=Array.from(frames.subarray(0,frameCount)).sort((a,b)=>a-b),frameTimings={samples:frameCount,p50Ms:intervals[Math.floor((frameCount-1)*.5)]??null,p95Ms:intervals[Math.floor((frameCount-1)*.95)]??null,maxMs:intervals.at(-1)??null};
  const centre=map.getCenter();return{build:root.RidgeBuildInfo||{baseline:'f44153a2a551955524f229ed83801d310ebb33e7',fingerprint:null},stageMeasurementNotes:{fetch:'Fetch span includes transport and regional routing/transforms; transform queue/cost is recorded separately.',nativeLoad:'Source span includes queue, fetch, decode and upload submission. Native queue wait and image decode have separate stages; upload completion is not GPU completion.',draw:'RTT draw submission is a CPU event, not GPU completion or screen presentation.'},imageQueue:root.maplibregl?.getImageRequestQueueStats?.()||null,stageTimings:stageSummary(),stages:stageRing.slice(),engine:'MapLibre 6.14.0',settings:Object.fromEntries(['imagery-detail','terrain-detail','cache-budget','prepare-ahead','natural-colour','hillshade','low-power','phone-mode'].map(id=>{const el=root.document?.getElementById?.(id);return[id,el?(el.type==='checkbox'?el.checked:el.value):null];})),camera:{longitude:centre.lng,latitude:centre.lat,zoom:map.getZoom(),pitch:map.getPitch(),bearing:map.getBearing(),elevation:map.getCenterElevation?.()??null},frameTimings,webglContextLost:map.painter?.context?.gl?.isContextLost?.()??null,terrain,visibleMissingDEMs:terrain.filter(t=>!t.dem).length,stats:stats(),sources,events:Array.from({length:count},(_,i)=>ring[(cursor-count+i+capacity)%capacity])};
 }
 function stageSummary(){return Object.fromEntries(Object.entries(durations).map(([key,d])=>{const values=d.values.slice().sort((a,b)=>a-b);return[key,{samples:values.length,totalSamples:d.totalSamples,movingSamples:d.movingSamples,p50Ms:values[Math.floor((values.length-1)*.5)],p95Ms:values[Math.floor((values.length-1)*.95)]}];}));}
 function download(){const url=root.URL.createObjectURL(new Blob([JSON.stringify(snapshot(),null,2)],{type:'application/json'})),a=root.document.createElement('a');a.href=url;a.download='ridgeline-imagery-report.json';a.click();setTimeout(()=>root.URL.revokeObjectURL(url),1000);}
 function destroy(){if(dead)return;dead=true;unsubscribe?.();stageRing.length=0;spans.clear();root.removeEventListener?.('error',rendererError);for(const [n,f] of listeners)map.off(n,f);ring.fill(undefined);count=0;}
 const listeners=[['sourcedataloading',loading],['sourcedata',data],['error',error],['webglcontextlost',lost],['webglcontextrestored',restored],['movestart',start],['moveend',end],['render',render],['remove',destroy]];listeners.forEach(([n,f])=>map.on(n,f));
 return{setEnabled,snapshot,download,destroy};
}
const api={attach};if(typeof module!=='undefined')module.exports=api;else root.RidgeImageryDiagnostics=api;
})(globalThis);
