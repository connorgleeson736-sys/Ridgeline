/* Distinct visible failures, bounded recovery and generation-safe warmup.
   Return to LINZ only after a complete native DEM cover is cached. */
(function(root){'use strict';
function create({fallback,restore,warm,allowed=()=>true,clock=()=>Date.now(),setTimer=setTimeout,clearTimer=clearTimeout}={}){
 let dead=false,state='linz',timer=null,active=null,generation=0,attempts=0;const failures=new Map();
 function schedule(){if(dead||state!=='fallback'||timer!==null||active)return;timer=setTimer(async()=>{timer=null;if(dead||state!=='fallback')return;if(!allowed()){schedule();return;}const token=generation,controller=new AbortController();active=controller;
  try{if(await warm(controller.signal)&&!dead&&token===generation&&state==='fallback'){restore();state='linz';failures.clear();attempts=0;}}
  catch{}finally{if(active===controller)active=null;if(!dead&&token===generation&&state==='fallback'){attempts++;schedule();}}
 },Math.min(60000,5000*2**Math.min(attempts,4)));}
 function error(e){if(dead||state!=='linz'||e.sourceId!=='terrain'||e.error?.name==='AbortError'||e.error?.status===404)return;const now=clock(),q=e.tile?.tileID?.canonical;
  for(const [key,at]of failures)if(now-at>15000)failures.delete(key);
  if(q)failures.set(q.z+'/'+q.x+'/'+q.y,now);
  const status=e.error?.status??e.error?.statusCode;if(state==='linz'&&(status===401||status===403||failures.size>=4)){fallback();state='fallback';attempts=0;schedule();}
 }
 function data(e){if(e.sourceId==='terrain'&&e.tile?.state==='loaded'&&state==='linz')failures.clear();}
 function reset(){generation++;if(timer!==null)clearTimer(timer);timer=null;active?.abort();active=null;failures.clear();attempts=0;state='linz';}
 return{error,data,recover(){if(state==='fallback'){if(timer!==null)clearTimer(timer);timer=null;attempts=0;schedule();}},reset,stats:()=>({terrainProviderState:state,distinctTerrainFailures:failures.size,recoveryAttempts:attempts,recovering:!!active}),destroy(){dead=true;reset();}};
}
async function warmCover(map,signal){
 const m=map.style?.tileManagers?.terrain,source=m?.getSource?.(),fallback=map.style?.tileManagers?.['terrain-fallback'];
 const cover=()=>(fallback?.getVisibleCoordinates?fallback.getVisibleCoordinates().map(id=>fallback.getTile(id)):(fallback?._inViewTiles?.getAllTiles?.()||[])).filter(t=>t&&t.dem&&t.state==='loaded');
 const rows=cover();
 const unique=new Map(rows.map(t=>[t.tileID.wrapped().key,t]));if(!unique.size||unique.size>32||!source?.loadTile||!m?._outOfViewCache?.add)return false;
 const prepared=[],child=new AbortController(),parentSignal=signal,stop=()=>child.abort();parentSignal.addEventListener('abort',stop,{once:true});if(parentSignal.aborted)stop();signal=child.signal;const timeout=setTimeout(stop,22000);let next=0,failed=false;
 try{await Promise.all(Array.from({length:2},async()=>{while(next<unique.size&&!failed){const sample=[...unique.values()][next++],id=sample.tileID.wrapped();if(signal.aborted){failed=true;return;}
   const found=m.getTile?.(id)||m._outOfViewCache.getByKey?.(id.key);if(found?.dem){prepared.push({tile:found,existing:true});continue;}
   const tile=new sample.constructor(id,source.tileSize||256);prepared.push({tile,existing:false});
   const cancel=()=>{tile.aborted=true;Promise.resolve(source.abortTile?.(tile)).catch(()=>{});};signal.addEventListener('abort',cancel,{once:true});try{await source.loadTile(tile);if(signal.aborted||!tile.dem){failed=true;child.abort();return;}tile.state='loaded';}catch{failed=true;child.abort();}finally{signal.removeEventListener('abort',cancel);}
  }}));
  if(signal.aborted||failed||map.style?.tileManagers?.terrain!==m)return false;
  const current=new Set(cover().map(t=>t.tileID.wrapped().key));if(current.size!==unique.size||[...current].some(k=>!unique.has(k)))return false;
  // Only publish a complete cover; until then the current fallback is retained.
  for(const row of prepared)if(!row.existing){const {tile}=row;if(!m.getTile?.(tile.tileID)&&!m._outOfViewCache.getByKey?.(tile.tileID.key))m._outOfViewCache.add(tile.tileID,tile);else await source.unloadTile?.(tile);row.existing=true;}
  return prepared.every(({tile})=>(m.getTile?.(tile.tileID)||m._outOfViewCache.getByKey?.(tile.tileID.key))?.dem);
 }finally{clearTimeout(timeout);parentSignal.removeEventListener('abort',stop);for(const row of prepared)if(!row.existing)await source.unloadTile?.(row.tile);}
}
const api={create,warmCover};if(typeof module!=='undefined')module.exports=api;else root.RidgeProviderHealth=api;
})(globalThis);
