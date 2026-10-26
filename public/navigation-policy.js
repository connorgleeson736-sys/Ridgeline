/* Shared current-view admission. A cached foreground image may use the small
   native ready lane; speculative work pauses behind real visible demand. */
(function(root){'use strict';
function attach(map,{engine=root.maplibregl,cache,residency,quality,onPreparation=()=>{}}){
 let dead=false,lastPump=0,lastSample=0,pressure=0,healthy=0,constrained=false,viewIndex=null;let sampleHandle=null,sampleIdle=false;const ranks=new Map();const clock=()=>root.performance.now();
 function priority(value){if(ranks.has(value))return ranks.get(value);if(!viewIndex){viewIndex=new Map();for(const [source,m]of Object.entries(map.style?.tileManagers||{}))viewIndex.set(source,new Set((m?._inViewTiles?.getAllTiles?.()||[]).map(t=>{const q=t.tileID.canonical;return q.z+'/'+q.x+'/'+q.y;})));}let u;try{u=new URL(value);}catch{return 1000;}
  const q=u.pathname.match(/\/tiles\/(aerial|elevation|topo-raster)\/WebMercatorQuad\/(\d+)\/(\d+)\/(\d+)\./),esri=u.pathname.match(/^\/(imagery|topo)\/(\d+)\/(\d+)\/(\d+)$/),http=u.pathname.match(/\/MapServer\/tile\/(\d+)\/(\d+)\/(\d+)$/);
  const source=q?(q[1]==='elevation'?'terrain':q[1]==='topo-raster'?'topo':'linz'):esri?(esri[1]==='topo'?'topo':'satellite'):http?(u.pathname.includes('Topo')?'topo':'satellite'):null;
  if(!source)return 1000;const id=q?{z:+q[2],x:+q[3],y:+q[4]}:esri?{z:+esri[2],x:+esri[4],y:+esri[3]}:{z:+http[1],x:+http[3],y:+http[2]};
  const visible=viewIndex.get(source)?.has(id.z+'/'+id.x+'/'+id.y);
  const focal=root.RidgeTerrainStreaming?.priority(id,quality.surfaceFocus?.())??0;
  const score=(visible?0:100)+Math.min(20,Math.max(0,focal))+(source==='terrain'&&id.z<=5?-1:0);ranks.set(value,score);if(ranks.size>2048)ranks.delete(ranks.keys().next().value);return score;
 }
 function busy(){const q=engine.getImageRequestQueueStats?.();return constrained||!!q&&(q.queued>0||q.active>=Math.max(1,q.limit-1));}
 cache.setViewPolicy?.(priority,busy);
 const invalidate=()=>{viewIndex=null;ranks.clear();};
 const move=()=>{invalidate();if(clock()-lastPump<120)return;lastPump=clock();engine.pumpImageRequestQueue?.();};
 function sample(){sampleHandle=null;if(dead||root.document?.hidden)return;const bytes=cache.stats(),resources=residency.resources?.(bytes);
  if(resources?.pressure){pressure++;healthy=0;}else{healthy++;pressure=0;}
  const next=pressure>=3?true:healthy>=8?false:constrained;
  if(next!==constrained){constrained=next;residency.setPressure?.(next);cache.setPressure?.(next);quality.setMemoryPressure?.(next);}
  onPreparation();
 }
 function render(){if(dead||sampleHandle!==null||clock()-lastSample<1200||root.document?.hidden)return;lastSample=clock();if(root.requestIdleCallback){sampleIdle=true;sampleHandle=root.requestIdleCallback(sample,{timeout:1500});}else if(root.setTimeout){sampleIdle=false;sampleHandle=root.setTimeout(sample,0);}else sample();}
 const handlers=[['move',move],['moveend',move],['sourcedataloading',invalidate],['render',render],['remove',destroy]];handlers.forEach(([n,f])=>map.on(n,f));
 function destroy(){if(dead)return;dead=true;if(sampleHandle!==null){if(sampleIdle)root.cancelIdleCallback?.(sampleHandle);else root.clearTimeout?.(sampleHandle);sampleHandle=null;}cache.setViewPolicy?.();cache.setPressure?.(false);for(const [n,f]of handlers)map.off(n,f);}
 return{destroy,stats:()=>({memoryConstrained:constrained,pressureSamples:pressure,healthySamples:healthy})};
}
const api={attach};if(typeof module!=='undefined')module.exports=api;else root.RidgeNavigation=api;
})(globalThis);
