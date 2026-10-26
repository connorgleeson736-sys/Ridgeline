/* LINZ topographic-v2 schema. One shared, worker-decoded vector source; no pan-time downloads in application code. */
(function(root){
'use strict';
const SOURCE='ridgeline-topography',GLYPHS='https://basemaps.linz.govt.nz/v1/fonts/{fontstack}/{range}.pbf';
const defaults={names:true,roads:true,tracks:true,huts:true,contours:false},storageKey='ridgeline-topography-v1';
const tiles=key=>`https://basemaps.linz.govt.nz/v1/tiles/topographic-v2/3857/{z}/{x}/{y}.pbf?api=${encodeURIComponent(key)}`;
const text=(size=12,field=['get','name'])=>({'text-field':field,'text-font':['Open Sans Regular'],'text-size':size,'text-max-width':9,'text-padding':5,'symbol-sort-key':100,'text-allow-overlap':false,'text-ignore-placement':false,'text-pitch-alignment':'viewport'});
const ink=(colour='#f7f7ed')=>({'text-color':colour,'text-halo-color':'rgba(18,32,35,0.88)','text-halo-width':1.4,'text-halo-blur':.3});
const line=(id,layer,filter,minzoom,paint)=>({id:'nz-'+id,type:'line',source:SOURCE,'source-layer':layer,filter,minzoom,layout:{'line-join':'round','line-cap':'round'},paint});
const symbol=(id,layer,filter,minzoom,layout,paint=ink())=>({id:'nz-'+id,type:'symbol',source:SOURCE,'source-layer':layer,filter,minzoom,layout,paint});
const foot=['all',['==',['get','kind'],'track'],['any',['==',['get','track_use'],'foot'],['==',['get','subclass'],'foot_closed'],['==',['get','subclass'],'foot_route_closed']]];
const road=['all',['!',['in',['get','kind'],['literal',['track','rail','raceway','aerodrome']]]],['any',['==',['get','kind'],'motorway'],['has','hway_num'],['>',['to-number',['get','lane_count'],0],0],['in',['get','surface'],['literal',['sealed','metalled','unmetalled']]]]];
const highway=['all',road,['any',['==',['get','kind'],'motorway'],['has','hway_num']]];
const localRoad=['all',road,['!',['any',['==',['get','kind'],'motorway'],['has','hway_num']]]];
const vehicleTrack=['all',['==',['get','kind'],'track'],['==',['get','track_use'],'vehicle']];
const closed=['in',['get','subclass'],['literal',['foot_closed','foot_route_closed']]];
const huts=['any',['==',['get','building'],'hut'],['==',['get','amenity'],'bivouac']];
// Cartographic priorities only; they do not imply prominence, difficulty or hut availability.
const rank=['let','r',['to-number',['coalesce',['get','admin_level'],13],13],['case',['all',['>=',['var','r'],2],['<=',['var','r'],13]],['var','r'],13]];
const named=['all',['has','name'],['!=',['get','name'],'']];
const hutName=['all',named,['==',['get','building'],'hut']];
const majorTracks=['in',['get','name'],['literal',['Hooker Track','Hooker Valley Track','Milford Track','Routeburn Track','Kepler Track','Heaphy Track','Abel Tasman Coast Track','Tongariro Alpine Crossing','Rakiura Track','Paparoa Track','Lake Waikaremoana Track','Whanganui Journey']]];
// Compatible priority tiers share a bucket; filters keep the original integer
// zoom thresholds. No camera-event filter/layout mutations are needed.
function refine(layer){
 const base=layer.filter||['all'];
 const tier=(suffix,minzoom,condition,size,priority)=>({...layer,id:layer.id+suffix,minzoom,filter:['all',base,condition],layout:{...layer.layout,...(size?{'text-size':size}:{}),'symbol-sort-key':layer['source-layer']==='place_labels'?['+',priority,['/',rank,100]]:priority}});
 const after=z=>['>=',['zoom'],z];
 switch(layer.id){
  case 'nz-peaks':{
   const lower=tier('-secondary',12,['all',['>',rank,6],after(['case',['<=',rank,8],12,14])],['case',['<=',rank,8],12,11],['case',['<=',rank,8],10,30]);
   delete lower.layout['text-variable-anchor'];lower.layout['text-anchor']='top';
   return [tier('',9,['<=',rank,6],13,0),lower];
  }
  case 'nz-places':{
   const major=['any',['==',['get','place'],'city'],['all',['==',['get','place'],'island'],['<=',rank,4]]],town=['any',['==',['get','place'],'town'],['==',['get','place'],'island']],local=['in',['get','place'],['literal',['locality','village']]];
   return [tier('',7,after(['case',major,7,town,9,local,12,14]),['case',major,13,town,12,11],['case',major,5,town,20,local,50,80])];
  }
  case 'nz-lakes-overview':return [{...tier('',7,after(['case',['<=',rank,4],7,['<=',rank,8],10,12]),['case',['<=',rank,8],12,11],['case',['<=',rank,4],10,['<=',rank,8],25,45]),maxzoom:13}];
  case 'nz-lakes':return [{...layer,minzoom:13,layout:{...layer.layout,'symbol-sort-key':40}}];
  case 'nz-huts-label':return [tier('',12,after(['case',hutName,12,14]),['case',hutName,12,11],['case',hutName,10,60])];
  case 'nz-huts-dot':return [{...layer,minzoom:12,filter:['all',base,after(['case',hutName,12,14])]}];
  case 'nz-tracks-label':return [tier('',12,after(['case',majorTracks,12,14]),['case',majorTracks,12,11],['case',majorTracks,15,60])];
  case 'nz-river-polygons':return [{...layer,minzoom:13,layout:{...layer.layout,'symbol-sort-key':45}}];
  case 'nz-rivers':return [{...layer,minzoom:14,layout:{...layer.layout,'symbol-sort-key':65}}];
  case 'nz-contours-label':return [{...layer,minzoom:14,layout:{...layer.layout,'symbol-sort-key':90,'symbol-spacing':700}}];
  default:return [layer];
 }
}
function layers(){return [
 line('contours-minor','contours',['all',['!',['has','designated']],['!=',['get','type'],'index'],['!',['has','nat_form']]],14,{'line-color':'#c6b38c','line-opacity':.32,'line-width':.6}),
 line('contours-index','contours',['==',['get','type'],'index'],12,{'line-color':'#d8c49c','line-opacity':.6,'line-width':1}),
 symbol('contours-label','contours',['==',['get','type'],'index'],14,{...text(10,['concat',['to-string',['get','elevation']],' m']),'symbol-placement':'line','symbol-spacing':400},ink('#e3d5b4')),
 line('roads-local-halo','streets',localRoad,11,{'line-color':'rgba(18,30,32,.85)','line-width':['interpolate',['linear'],['zoom'],11,2.4,15,4,19,7]}),
 line('roads-local','streets',localRoad,11,{'line-color':['case',['==',['get','surface'],'unmetalled'],'#d8c9aa','#f5eee0'],'line-width':['interpolate',['linear'],['zoom'],11,1,15,2,19,4.5]}),
 line('roads-highway-halo','streets',highway,8,{'line-color':'rgba(18,30,32,.9)','line-width':['interpolate',['linear'],['zoom'],8,2.7,13,4.6,19,9]}),
 line('roads-highway','streets',highway,8,{'line-color':'#f3bf79','line-width':['interpolate',['linear'],['zoom'],8,1.2,13,2.5,19,6]}),
 line('roads-vehicle-tracks','streets',vehicleTrack,13,{'line-color':'#d8c9aa','line-width':['interpolate',['linear'],['zoom'],13,1,18,2],'line-dasharray':[2,2]}),
 symbol('roads-highway-label','streets',['all',highway,['any',['has','hway_num'],named]],11,{...text(11,['case',['has','hway_num'],['concat','SH ',['to-string',['get','hway_num']]],['get','name']]),'symbol-placement':'line','symbol-spacing':850,'symbol-sort-key':35},ink('#ffe0ae')),
 symbol('roads-local-label','streets',['all',localRoad,named],14,{...text(11),'symbol-placement':'line','symbol-spacing':800,'symbol-sort-key':75},ink('#f5eee0')),
 line('tracks-halo','streets',foot,12,{'line-color':'rgba(15,30,31,.8)','line-width':['interpolate',['linear'],['zoom'],12,3,17,4.5]}),
 line('tracks','streets',foot,12,{'line-color':['case',closed,'#ef9c97','#f6db94'],'line-width':['interpolate',['linear'],['zoom'],12,1.3,17,2.2],'line-dasharray':[3,2]}),
 symbol('tracks-label','streets',['all',foot,['has','name']],14,{...text(11,['concat',['get','name'],['case',closed,' (mapped closed)','']]),'symbol-placement':'line','symbol-spacing':650},ink('#f6db94')),
 {id:'nz-huts-dot',type:'circle',source:SOURCE,'source-layer':'pois',filter:huts,minzoom:11,paint:{'circle-radius':4,'circle-color':'#f6db94','circle-stroke-color':'#233d34','circle-stroke-width':1.5}},
 symbol('huts-label','pois',huts,11,{...text(12,['case',named,['get','name'],['case',['==',['get','amenity'],'bivouac'],'Bivouac','Hut']]),'text-anchor':'left','text-offset':[.7,0]},ink('#f6db94')),
 symbol('peaks','place_labels',['==',['get','natural'],'peak'],10,{...text(12,['get','label']),'text-font':['Open Sans Bold'],'text-variable-anchor':['top','bottom','left','right'],'text-radial-offset':.5}),
 symbol('places','place_labels',['in',['get','place'],['literal',['city','town','locality','village','suburb','island']]],9,text(12,['get','label'])),
 symbol('lakes-overview','place_labels',['==',['get','place'],'lake'],7,text(12,['get','label']),ink('#c2e9ed')),
 symbol('lakes','water_polygons',['all',['==',['get','water'],'lake'],['has','name']],11,text(12),ink('#c2e9ed')),
 symbol('rivers','water_lines',['all',['==',['get','kind'],'river'],['has','name']],12,{...text(11),'symbol-placement':'line','symbol-spacing':900},ink('#c2e9ed')),
 symbol('river-polygons','water_polygons',['all',['==',['get','kind'],'river'],['has','name']],12,text(11),ink('#c2e9ed'))
].flatMap(refine).sort((a,b)=>Number(a.type!=='line')-Number(b.type!=='line'));}
function group(id){return id.includes('roads')?'roads':id.includes('contours')?'contours':id.includes('tracks')?'tracks':id.includes('huts')?'huts':'names';}
function createUI({document,storage,onChange=()=>{}}){
 const state={...defaults};try{const saved=JSON.parse(storage?.getItem(storageKey)||'null');for(const k of Object.keys(defaults))if(typeof saved?.[k]==='boolean')state[k]=saved[k];}catch{}
 const bindings=[];for(const k of Object.keys(defaults)){const el=document.getElementById('topo-'+k);if(!el)continue;el.checked=state[k];const fn=()=>{state[k]=!!el.checked;try{storage?.setItem(storageKey,JSON.stringify(state));}catch{}onChange();};el.addEventListener('change',fn);bindings.push([el,fn]);}
 return{values:()=>({...state}),destroy(){for(const [el,fn]of bindings)el.removeEventListener('change',fn);}};
}
function attach(map,{key,settings=()=>defaults,peakLabels=()=>true,basemap=()=> 'linz',onStatus=()=>{},onNamesReady=()=>{}}){
 let dead=false,available=false,failed=false,lastVisibility='',lastStatus='',lastNames;const specs=layers();
 if(!map.getSource(SOURCE))map.addSource(SOURCE,{type:'vector',tiles:[tiles(key)],minzoom:0,maxzoom:15,bounds:[165,-49,180,-33],attribution:'<a href="https://www.linz.govt.nz/">LINZ topographic data · CC BY 4.0</a>'});
 for(const l of specs)if(!map.getLayer(l.id)){
  // Lines (including the route) share the base drape. Circles/text stay above
  // that drape and retain their existing relative order.
  const before=l.type==='line'?'route-halo':'waypoint-circles';
  map.addLayer({...l,layout:{...l.layout,visibility:'none'}},map.getLayer(before)?before:undefined);
 }
 const usesPeakNames=()=>available&&!failed&&settings().names&&peakLabels()&&basemap()!=='topo';
 function refresh(){if(dead)return;const s=settings(),rasterTopo=basemap()==='topo',peaks=peakLabels();
  const key=Object.keys(defaults).map(k=>!!s[k]).join('/')+'/'+rasterTopo+'/'+peaks;
  if(key!==lastVisibility){lastVisibility=key;for(const l of specs){
   const show=!!s[group(l.id)]&&!rasterTopo&&(!l.id.startsWith('nz-peaks')||peaks),value=show?'visible':'none';
   if(map.getLayoutProperty(l.id,'visibility')!==value)map.setLayoutProperty(l.id,'visibility',value);
  }}
  const status=rasterTopo?'Topographic basemap already includes labels. Overlays resume on aerial imagery.':failed?'Some LINZ topographic data could not load. Check your key or connection; imagery and planning remain available.':!Object.values(s).some(Boolean)?'Topographic overlays off.':available?'LINZ topographic overlays · major labels first; zoom in for smaller features.':'Loading LINZ topographic overlays…';
  if(status!==lastStatus){lastStatus=status;onStatus(status);}
  const names=usesPeakNames();if(names!==lastNames){lastNames=names;onNamesReady();}
 }
 function data(e){if(dead||e?.sourceId!==SOURCE||e.sourceDataType!=='content'||available&&!failed)return;available=true;failed=false;refresh();}
 function error(e){if(dead||e?.sourceId!==SOURCE||e.error?.name==='AbortError'||failed)return;failed=true;refresh();}
 map.on('sourcedata',data);map.on('error',error);
 const api={refresh,usesPeakNames,setKey(value){available=false;failed=false;map.getSource(SOURCE)?.setTiles([tiles(value)]);refresh();},destroy(){dead=true;map.off('sourcedata',data);map.off('error',error);}};
 refresh();return api;
}
root.RidgeTopography={GLYPHS,SOURCE,defaults,tiles,layers,createUI,attach};
})(globalThis);
