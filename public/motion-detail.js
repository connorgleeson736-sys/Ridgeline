/* Fresh camera displacement measures speed; the slower terrain ray only sets
   view scale. Speed controls requested LOD, never DEM values or camera. */
(function(root){'use strict';
const clamp=(v,a,b)=>Math.max(a,Math.min(b,v));
function attach(map,{enabled=()=>true,focus=()=>null,onChange=()=>{},clock=()=>root.performance.now()}={}){
 let dead=false,moving=false,previous=null,smoothed=0,drop=0,lastChange=-Infinity,timer=null;
 function point(){const t=map._camera?.transform,f=focus(),p=map.getCenter(),x=(p.lng+180)/360,y=(1-Math.asinh(Math.tan(clamp(p.lat,-85,85)*Math.PI/180))/Math.PI)/2;
  const span=f?.range>0?2*f.range*Math.tan((t?.fov||36.87)*Math.PI/360):(t?.height||800)/Math.max(1,t?.worldSize||512*2**map.getZoom());
  return{x,y,zoom:map.getZoom(),span:Math.max(1e-12,span),at:clock()};
 }
 function apply(value){const next=enabled()?Math.round(clamp(value,0,2)*4)/4:0;if(next===drop)return;drop=next;lastChange=clock();onChange({reduction:drop,speed:smoothed});map._update?.();map.triggerRepaint();}
 function clear(){if(timer!==null)root.clearTimeout(timer);timer=null;}
 function start(){if(dead)return;clear();moving=true;previous=point();}
 function sample(){
  if(dead||!moving||root.document?.hidden)return;
  if(!enabled()){apply(0);previous=null;smoothed=0;return;}
  const p=point();if(!previous){previous=p;return;}const dt=(p.at-previous.at)/1000;if(dt<.1)return;
  const rawDx=p.x-previous.x,dx=rawDx-Math.round(rawDx),dy=p.y-previous.y,translation=Math.hypot(dx,dy)/p.span/Math.max(.001,dt),zooming=Math.abs(p.zoom-previous.zoom)/Math.max(.001,dt)*.25;
  previous=p;const raw=clamp(Math.max(translation,zooming),0,20);smoothed+=(raw-smoothed)*(1-Math.exp(-dt/.3));
  const target=clamp(Math.log2(1+Math.max(0,smoothed-.35)/.65),0,2);
  // Reduce detail promptly; require a larger speed change to refine during
  // movement. Full detail still returns after the gesture settles.
  const candidate=Math.round(target*4)/4;
  if(p.at-lastChange>=300&&(candidate>drop&&target>=drop+.2||candidate<drop&&target<=drop-.35))apply(candidate);
 }
 function end(){moving=false;previous=null;clear();timer=root.setTimeout(()=>{timer=null;if(dead||moving)return;smoothed=0;apply(0);},350);}
 function refresh(){clear();previous=null;smoothed=0;apply(0);}
 function visibility(){if(root.document?.hidden){moving=false;refresh();}}
 function destroy(){if(dead)return;dead=true;clear();for(const [event,fn]of listeners)map.off(event,fn);root.document?.removeEventListener?.('visibilitychange',visibility);}
 const listeners=[['movestart',start],['move',sample],['render',sample],['moveend',end],['remove',destroy]];
 for(const [event,fn]of listeners)map.on(event,fn);root.document?.addEventListener?.('visibilitychange',visibility);
 return{reduction:()=>enabled()?drop:0,refresh,destroy,stats:()=>({motionDetailEnabled:enabled(),motionDetailReduction:drop,motionViewSpeed:smoothed})};
}
root.RidgeMotionDetail={attach};
})(globalThis);
