/* Tiny directional byte-cache warmup. Does not change native LOD, geometry,
   covering tiles, GPU texture ownership, or the existing residency budgets. */
(function(root){
'use strict';
const clamp=(x,a,b)=>Math.max(a,Math.min(b,x));
function plan({hit,previous,zoom,previousZoom=zoom,baseZoom,maxZoom=19,elapsed=400}){
 if(zoom<previousZoom-.015){
  if(!hit||![hit.x,hit.y].every(Number.isFinite)||baseZoom<7)return[];
  // At most two immediate ancestors, never a surrounding zoom-out ring.
  // Existing concurrency, rate and byte-pressure gates still apply.
  return [1,2].map(d=>Math.min(maxZoom,baseZoom-d)).filter((z,i,a)=>z>=5&&a.indexOf(z)===i).map(z=>({z,x:clamp(Math.floor(hit.x*2**z),0,2**z-1),y:clamp(Math.floor(hit.y*2**z),0,2**z-1)}));
 }
 if(!hit||![hit.x,hit.y].every(Number.isFinite)||baseZoom<13||baseZoom>=maxZoom)return [];
 const z=Math.min(maxZoom,baseZoom+1),n=2**z,zooming=zoom-previousZoom>.015;
 let dx=previous?(hit.x-previous.x)*Math.min(1.5,500/Math.max(100,elapsed)):0,dy=previous?(hit.y-previous.y)*Math.min(1.5,500/Math.max(100,elapsed)):0;
 const length=Math.hypot(dx,dy),limit=.7/n;if(length>limit){dx*=limit/length;dy*=limit/length;}
 if(!zooming&&Math.hypot(dx,dy)<.025/n)return [];
 const samples=zooming?[[hit.x,hit.y],[hit.x+dx,hit.y+dy]]:[[hit.x+dx,hit.y+dy],[hit.x+dx*1.8,hit.y+dy*1.8]];
 const tiles=new Map();for(const [x,y] of samples){const a=clamp(Math.floor(x*n),0,n-1),b=clamp(Math.floor(y*n),0,n-1);tiles.set(z+'/'+a+'/'+b,{z,x:a,y:b});}
 // A zoom focus crossing a child boundary may warm its closest neighbour,
 // rather than a square of sixteen surrounding tiles.
 if(zooming){const x=hit.x*n,y=hit.y*n,fx=x-Math.floor(x),fy=y-Math.floor(y),ax=Math.min(fx,1-fx),ay=Math.min(fy,1-fy);if(Math.min(ax,ay)<.18){const a=clamp(Math.floor(x)+(ax<ay?(fx<.5?-1:1):0),0,n-1),b=clamp(Math.floor(y)+(ay<=ax?(fy<.5?-1:1):0),0,n-1);tiles.set(z+'/'+a+'/'+b,{z,x:a,y:b});}}
 return [...tiles.values()].slice(0,3);
}
function attach(map,{cache,focus,lowPower=()=>false,basemap=()=> 'linz',clock=()=>root.performance.now(),engine=root.maplibregl,concurrency=()=>2}={}){
 let dead=false,last=0,previous=null,previousZoom=map.getZoom(),gestureCount=0,wheel=null,active=0;const pending=new Set(),seen=new Map(),recent=[],controllers=new Set(),metrics={started:0,completed:0,failed:0,backpressure:0};
 const supported=engine?.getVersion?.()==='6.14.0';
 const limit=()=>{const value=concurrency();return Number.isFinite(value)?clamp(Math.round(value),0,12):2;};
 const normalized=p=>({x:(p.lng+180)/360,y:(1-Math.asinh(Math.tan(p.lat*Math.PI/180))/Math.PI)/2});
 function target(){
  const f=focus?.();if(!f?.hit)return null;
  if(wheel&&clock()-wheel.time<650&&map.unproject){if(wheel.anchor)return wheel.anchor;try{const p=map.unproject(wheel.point);if(Number.isFinite(p.lng)&&Number.isFinite(p.lat)&&p.lat>-49&&p.lat<-33&&p.lng>165&&p.lng<180){wheel.anchor=normalized(p);return wheel.anchor;}}catch{}}
  return{x:f.hit.x,y:f.hit.y};
 }
 function tick(){
  if(dead||!supported||!limit()||root.document?.hidden||lowPower()||root.navigator?.connection?.saveData)return;
  const now=clock();if(now-last<250)return;const elapsed=now-last;last=now;
  const hit=target(),zoom=map.getZoom(),id=basemap();
  if(!hit||!['linz','satellite'].includes(id)){previous=null;previousZoom=zoom;return;}
  const source=map.getSource(id),tiles=map.style?.tileManagers?.[id]?._inViewTiles?.getAllTiles?.()||[];
  // Read the actual native tile requests around this first-hit surface. Only
  // warm one finer level; no independent camera-distance LOD approximation.
  let baseZoom=-1;for(const t of tiles){const q=t.tileID?.canonical;if(q&&Math.floor(hit.x*2**q.z)===q.x&&Math.floor(hit.y*2**q.z)===q.y)baseZoom=Math.max(baseZoom,q.z);}
  const wanted=plan({hit,previous,zoom,previousZoom,baseZoom,maxZoom:Math.min(19,source?.maxzoom??19),elapsed});previous=hit;previousZoom=zoom;
  const stats=cache?.stats?.().imagery;while(recent.length&&now-recent[0]>60000)recent.shift();
  if(!wanted.length||!source?.tiles?.[0]||active>=limit()||gestureCount>=24||recent.length>=60)return;
  if((stats?.pending||0)>3||cache.canPrefetch?.()===false){metrics.backpressure++;return;}
  for(const q of wanted){
   if(active>=limit()||gestureCount>=24||recent.length>=60)break;
   const url=source.tiles[0].replaceAll('{z}',q.z).replaceAll('{x}',q.x).replaceAll('{y}',q.y),before=seen.get(url);
   if(pending.has(url)||(before&&now-before<60000)||cache.has?.(url))continue;
   const controller=new AbortController();controllers.add(controller);pending.add(url);active++;gestureCount++;recent.push(now);seen.set(url,now);metrics.started++;
   while(seen.size>128)seen.delete(seen.keys().next().value);
   cache.prefetch(url,controller.signal,{original:true}).then(()=>metrics.completed++,()=>metrics.failed++).finally(()=>{active--;controllers.delete(controller);pending.delete(url);});
  }
 }
 function start(){gestureCount=0;if(wheel)wheel.anchor=null;previous=target();previousZoom=map.getZoom();last=clock()-250;}
 function onWheel(e){const r=map.getCanvas().getBoundingClientRect(),point=[e.clientX-r.left,e.clientY-r.top],now=clock(),anchor=wheel&&now-wheel.time<700&&Math.hypot(point[0]-wheel.point[0],point[1]-wheel.point[1])<5?wheel.anchor:null;wheel={point,time:now,anchor};}
 function destroy(){if(dead)return;dead=true;for(const [n,f]of listeners)map.off(n,f);map.getCanvas().removeEventListener?.('wheel',onWheel);for(const c of controllers)c.abort();controllers.clear();pending.clear();seen.clear();}
 const listeners=[['movestart',start],['move',tick],['remove',destroy]];
 if(supported&&cache?.prefetch){listeners.forEach(([n,f])=>map.on(n,f));map.getCanvas().addEventListener?.('wheel',onWheel,{passive:true});}
 return{destroy,stats:()=>({...metrics,active,concurrencyLimit:limit(),perMinute:recent.length,enabled:supported&&limit()>0})};
}
const api={plan,attach};if(typeof module!=='undefined')module.exports=api;else root.RidgeImageryPreload=api;
})(globalThis);
