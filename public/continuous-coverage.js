/* Opt-in Original renderer coverage. Native managers own all tiles and draw work. */
(function(root){'use strict';
function attach(map,{enabled=()=>false,lowPower=()=>false,engine=root.maplibregl}={}){
 const supported=engine?.getVersion?.()==='6.14.0',wrapped=new Map(),expanded=new WeakSet();let dead=false,timer=null,lastTransform=null,lastExpanded=null,stamp='',updates=0;
 const active=()=>!dead&&supported&&enabled()&&!lowPower()&&!root.document?.hidden&&!root.navigator?.connection?.saveData;
 function widen(transform){
  if(!active()||!transform?.clone||expanded.has(transform))return transform;
  const key=[transform.center.lng,transform.center.lat,transform.zoom,transform.pitch,transform.bearing,transform.elevation,transform.width,transform.height,transform.fov].join('/');
  if(lastTransform===transform&&stamp===key)return lastExpanded;
  const scale=Math.min(3,8192/transform.width,8192/transform.height),copy=transform.clone();
  if(!(scale>1))return transform;
  copy.resize(transform.width*scale,transform.height*scale);copy.setFov(2*Math.atan(Math.tan(transform.fov*Math.PI/360)*scale)*180/Math.PI);
  expanded.add(copy);lastTransform=transform;lastExpanded=copy;stamp=key;return copy;
 }
 function wrap(manager){
  if(!manager||wrapped.has(manager)||typeof manager.update!=='function')return;
  const original=manager.update;
  function update(transform,...rest){updates++;return original.call(this,widen(transform),...rest);}
  manager.update=update;wrapped.set(manager,{original,update});
 }
 function sync(){
  if(dead||!supported)return;
  const managers=map.style?.tileManagers||{};
  for(const id of ['linz','satellite','topo','terrain','terrain-fallback','shade-dem'])wrap(managers[id]);
  wrap(map.terrain?.tileManager);wake();
 }
 function wake(){
  if(timer!==null||!active())return;
  timer=root.setTimeout(()=>{timer=null;if(!active())return;map.triggerRepaint();wake();},33);
 }
 function refresh(){lastTransform=null;lastExpanded=null;stamp='';sync();if(timer!==null&&!active()){root.clearTimeout(timer);timer=null;}map._update?.(true);map.triggerRepaint();}
 function destroy(){if(dead)return;dead=true;if(timer!==null)root.clearTimeout(timer);for(const [manager,{original,update}]of wrapped)if(manager.update===update)manager.update=original;wrapped.clear();for(const [event,fn]of listeners)map.off(event,fn);root.document?.removeEventListener?.('visibilitychange',refresh);}
 const listeners=[['terrain',sync],['styledata',sync],['render',wake],['remove',destroy]];
 if(supported){for(const [event,fn]of listeners)map.on(event,fn);root.document?.addEventListener?.('visibilitychange',refresh);sync();}
 return{refresh,destroy,stats:()=>({aggressiveCoverage:active(),coverageViewportScale:active()?3:1,coverageUpdates:updates})};
}
root.RidgeContinuousCoverage={attach};
})(globalThis);
