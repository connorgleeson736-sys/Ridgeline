/* Two native layers; data loads only on first visible use. No frame listeners. */
(function(root){'use strict';
const SOURCE='imagery-source-boundary',IDS=['imagery-boundary-dots','imagery-boundary-labels'],KEY='ridgeline-imagery-borders-v1',initialised=new WeakSet();
function attach(map,{basemap=()=> 'linz',data=root.RidgeImageryBoundaryData}={}){
 if(!data)return{refresh(){},destroy(){}};
 const control=root.document?.getElementById('imagery-borders');
 if(control&&!initialised.has(control)){try{control.checked=root.localStorage?.getItem(KEY)!=='0';}catch{}initialised.add(control);}
 let dead=false,created=false,last;
 function create(){
  if(!map.getSource(SOURCE))map.addSource(SOURCE,{type:'geojson',data:data.url,maxzoom:18,tolerance:.25,buffer:32});
  if(!map.getLayer(IDS[0]))map.addLayer({id:IDS[0],type:'line',source:SOURCE,minzoom:8,layout:{visibility:'visible','line-cap':'round','line-join':'round'},paint:{'line-color':'#111111','line-width':1,'line-opacity':.55,'line-dasharray':[.2,3.8]}},map.getLayer('route-halo')?'route-halo':undefined);
  if(!map.getLayer(IDS[1]))map.addLayer({id:IDS[1],type:'symbol',source:SOURCE,minzoom:11,layout:{visibility:'visible','symbol-placement':'line','symbol-spacing':500,'text-field':'LINZ imagery  |  Esri World Imagery','text-size':10,'text-font':['Open Sans Regular'],'text-rotation-alignment':'map','text-pitch-alignment':'viewport','text-keep-upright':true,'text-max-angle':35,'text-padding':8,'text-allow-overlap':false,'text-ignore-placement':false},paint:{'text-color':'#202020','text-opacity':.8,'text-halo-color':'rgba(255,255,255,0.75)','text-halo-width':.7}},map.getLayer('waypoint-circles')?'waypoint-circles':undefined);
  created=true;
 }
 function refresh(){if(dead)return;const visible=basemap()==='linz'&&(control?.checked??true);if(visible===last)return;if(visible&&!created)create();else if(created)for(const id of IDS)if(map.getLayer(id))map.setLayoutProperty(id,'visibility',visible?'visible':'none');last=visible;}
 function changed(){try{root.localStorage?.setItem(KEY,control.checked?'1':'0');}catch{}refresh();}
 control?.addEventListener('change',changed);refresh();
 return{refresh,destroy(){if(dead)return;dead=true;control?.removeEventListener('change',changed);for(const id of [...IDS].reverse())if(map.getLayer(id))map.removeLayer(id);if(map.getSource(SOURCE))map.removeSource(SOURCE);}};
}
root.RidgeImageryBoundary={attach};
})(globalThis);
