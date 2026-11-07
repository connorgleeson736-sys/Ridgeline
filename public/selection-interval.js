/* Coalesce camera-driven native selection; drawing keeps the live camera. */
(function(root){'use strict';
function attach(map,{interval=()=>0,engine=root.maplibregl}={}){
 const supported=engine?.getVersion?.()==='6.14.0',wrapped=new Map();
 let dead=false,inRender=false,allowed=true,force=true,last=-Infinity,timer=null,deferred=false,runs=0,skips=0;
 const delay=()=>Math.max(0,Math.min(200,Number(interval())||0));
 function clear(){if(timer!==null)root.clearTimeout(timer);timer=null;}
 function wake(){if(timer!==null||dead||root.document?.hidden)return;timer=root.setTimeout(()=>{timer=null;if(dead)return;force=true;map._update?.();map.triggerRepaint();},Math.max(1,last+delay()-root.performance.now()));}
 function wrap(manager){
  if(!manager||wrapped.has(manager)||typeof manager.update!=='function')return;
  const original=manager.update;
  function update(...args){const held=inRender&&!allowed;this._ridgeReuseIdeal=held;if(held){deferred=true;skips++;wake();}try{return original.apply(this,args);}finally{this._ridgeReuseIdeal=false;}}
  manager.update=update;wrapped.set(manager,{original,update});force=true;
 }
 function sync(){if(dead||!supported)return;for(const manager of Object.values(map.style?.tileManagers||{}))wrap(manager);wrap(map.terrain?.tileManager);}
 const originalRender=map._render;
 function render(...args){
  const now=root.performance.now();allowed=force||!delay()||now-last>=delay();
  if(allowed){last=now;force=false;deferred=false;runs++;clear();}
  inRender=true;try{return originalRender.apply(this,args);}finally{inRender=false;if(deferred)wake();}
 }
 function refresh(){if(dead)return;force=true;clear();sync();map._update?.();map.triggerRepaint();}
 function data(event){const source=map.getSource?.(event.sourceId)||map.style?.tileManagers?.[event.sourceId]?._source;if(event.sourceDataType==='metadata'||event.tile?.dem||event.sourceDataType==='content'&&source?.type!=='raster'){force=true;allowed=true;}if(event.sourceDataType==='content'||event.sourceDataType==='metadata')map.triggerRepaint();}
 function visibility(){if(root.document?.hidden)clear();else refresh();}
 function destroy(){if(dead)return;dead=true;clear();if(map._render===render)map._render=originalRender;for(const [manager,{original,update}]of wrapped)if(manager.update===update)manager.update=original;wrapped.clear();for(const [event,fn]of listeners)map.off(event,fn);root.document?.removeEventListener?.('visibilitychange',visibility);}
 const listeners=[['styledata',sync],['sourcedata',data],['terrain',refresh],['webglcontextrestored',refresh],['resize',refresh],['movestart',refresh],['moveend',refresh],['remove',destroy]];
 if(supported&&typeof originalRender==='function'){map._render=render;for(const [event,fn]of listeners)map.on(event,fn);root.document?.addEventListener?.('visibilitychange',visibility);sync();}
 return{refresh,destroy,stats:()=>({selectionIntervalMs:delay(),selectionRuns:runs,selectionSkippedUpdates:skips})};
}
root.RidgeSelectionInterval={attach};
})(globalThis);
