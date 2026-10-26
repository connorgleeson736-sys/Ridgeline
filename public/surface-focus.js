/* MapLibre 6.14.0 adapter: select detail around the first measured surface in
   the centre ray, rather than a navigation pivot hidden behind a mountain.
   Only LOD policy changes; native frustum tests, DEMs and camera stay intact. */
(function(root){
'use strict';
const clamp=(x,a,b)=>Math.max(a,Math.min(b,x));
function weights(state){
 if(!state?.tile||!state.hit)return{surface:0,pivot:0};
 const {z,x,y}=state.tile,n=2**z;
 const around=(h,range,scale)=>{
  const dx=Math.max(x/n-h.x,0,h.x-(x+1)/n),dy=Math.max(y/n-h.y,0,h.y-(y+1)/n);
  const radius=Math.max(1e-12,range*Math.tan(state.fov*Math.PI/360)*scale);
  const t=clamp((Math.hypot(dx,dy)-radius*.35)/(radius*1.15),0,1);return 1-t*t*(3-2*t);
 };
 return{surface:around(state.hit,state.range,.85),pivot:state.pivot?around(state.pivot,state.pivotRange||state.range,.40):0};
}
function weight(state){return weights(state).surface;}

function attach(map,{engine=root.maplibregl,clock=()=>root.performance.now(),focusLod=root.RidgePerformance?.focusLod,phoneMode=()=>false,phoneDetail=()=>50,motionDetail=()=>0}={}){
 let state=null,provider=null,original=null,last=-Infinity,samples=0,dead=false;
 const supported=engine?.getVersion?.()==='6.14.0'&&typeof engine.MercatorCoordinate?.fromLngLat==='function';
 let renderPolicy=null,phoneRenderPolicy=null;
 if(supported&&focusLod&&engine.Map?.prototype.setSourceTileLodParams){
  // Obtain the pinned engine's native renderer policy without mutating any live
  // source. Use a coarser distant budget; protect the surface and a smaller pivot area.
  const holder={};engine.Map.prototype.setSourceTileLodParams.call({getSource:()=>holder,_update(){}},10.3,2.75,'terrain');
  renderPolicy=focusLod(holder.calculateTileZoom,0,()=>state,false);
  const phoneHolder={};engine.Map.prototype.setSourceTileLodParams.call({getSource:()=>phoneHolder,_update(){}},12,2,'terrain');
  const phoneBase=focusLod(phoneHolder.calculateTileZoom,0,()=>state,false);
  phoneRenderPolicy=root.RidgePerformance?.nearbyLod?root.RidgePerformance.nearbyLod(phoneBase,phoneDetail,()=>state):phoneBase;
 }
 const movingRenderPolicy=function(...args){const policy=phoneMode()?phoneRenderPolicy:renderPolicy;return policy.apply(this,args)-Math.max(0,Math.min(2,motionDetail()));};
 function sample(force=false){
  if(dead||!supported)return;
  const now=clock();if(!force&&now-last<240)return;last=now;
  const t=map._camera?.transform,terrain=map.terrain;
  if(!terrain||!t?.screenTerrainPointToMercatorCoordinate||!t.centerPoint){state=null;return;}
  let hit;try{hit=t.screenTerrainPointToMercatorCoordinate(t.centerPoint,terrain);}catch{state=null;return;}
  samples++;
  if(!hit||![hit.x,hit.y,hit.z].every(Number.isFinite)){state=null;return;}
  const centre=engine.MercatorCoordinate.fromLngLat(t.center,t.elevation),d=t.cameraToCenterDistance/t.worldSize;
  const p=t.pitch*Math.PI/180,b=t.bearing*Math.PI/180;
  const camera={x:centre.x-d*Math.sin(p)*Math.sin(b),y:centre.y+d*Math.sin(p)*Math.cos(b),z:centre.z+d*Math.cos(p)};
  // The native terrain ray returns x/y in normalized Mercator but z in metres.
  const surface=engine.MercatorCoordinate.fromLngLat(hit.toLngLat(),hit.z);
  const range=Math.hypot(surface.x-camera.x,surface.y-camera.y,surface.z-camera.z);
  if(!Number.isFinite(range)||range<1e-12){state=null;return;}
  const previous=state;state={hit:surface,range,pivot:centre,pivotRange:d,fov:t.fov,tile:null};
  if(!previous||Math.hypot(previous.hit.x-surface.x,previous.hit.y-surface.y)>range*.035||Math.abs(previous.range-range)>range*.06)map._update?.();
 }
 function install(){
  if(dead||!supported)return;
  const t=map._camera?.transform,next=t?.getCoveringTilesDetailsProvider?.();
  if(next===provider)return;restore();
  if(!next||typeof next.distanceToTile2d!=='function'||typeof next.allowVariableZoom!=='function')return;
  provider=next;original={distanceToTile2d:next.distanceToTile2d,allowVariableZoom:next.allowVariableZoom};
  provider.distanceToTile2d=function(px,py,tile,bounds){if(state)state.tile=tile;return original.distanceToTile2d.call(this,px,py,tile,bounds);};
  provider.allowVariableZoom=function(transform,options){
   // The terrain render hierarchy normally uses a different hard-coded LOD
   // from its DEM source. Apply the same focus target so sharp downloaded
   // imagery is not flattened into an oversized, low-resolution terrain drape.
   if(renderPolicy&&options.terrain===map.terrain&&!options.calculateTileZoom&&!Object.hasOwn(options,'maxContentElevation'))options.calculateTileZoom=movingRenderPolicy;
   return original.allowVariableZoom.call(this,transform,options);
  };
 }
 function restore(){if(provider&&original)Object.assign(provider,original);provider=null;original=null;}
 function onRender(){install();sample();}
 function onEnd(){install();sample(true);}
 function destroy(){if(dead)return;dead=true;restore();state=null;for(const [n,f] of listeners)map.off(n,f);}
 const listeners=[['render',onRender],['moveend',onEnd],['terrain',onEnd],['remove',destroy]];
 if(supported){listeners.forEach(([n,f])=>map.on(n,f));install();sample();}
 return{state:()=>state,destroy,stats:()=>({surfaceFocus:supported&&!!state,surfaceSamples:samples})};
}
const api={attach,weight,weights};if(typeof module!=='undefined')module.exports=api;else root.RidgeSurfaceFocus=api;
})(globalThis);
