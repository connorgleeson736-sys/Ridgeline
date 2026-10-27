export function combineRoutes(routes){
 if(routes.length<2)throw Error('Select at least two routes to combine.');
 const points=[];
 for(const route of routes){const part=(route.reverse?[...route.points].reverse():route.points);for(const p of part){const last=points.at(-1);if(!last||last[0]!==p[0]||last[1]!==p[1])points.push(p.slice());}}
 if(points.length>2000)throw Error('Combined routes exceed 2,000 points. Choose fewer routes.');
 return points;
}
export function routeLength(points){
 let distance=0;const rad=Math.PI/180;
 for(let i=1;i<points.length;i++){const a=points[i-1],b=points[i],lat=(b[1]-a[1])*rad,lon=(b[0]-a[0])*rad,h=Math.sin(lat/2)**2+Math.cos(a[1]*rad)*Math.cos(b[1]*rad)*Math.sin(lon/2)**2;distance+=6371008.8*2*Math.asin(Math.min(1,Math.sqrt(h)));}return distance;
}
export function routePreview(points){
 if(!points.length)return [];
 const projected=points.map(p=>[p[0]*Math.PI/180,Math.asinh(Math.tan(p[1]*Math.PI/180))]);
 const xs=projected.map(p=>p[0]),ys=projected.map(p=>p[1]),west=Math.min(...xs),east=Math.max(...xs),south=Math.min(...ys),north=Math.max(...ys),scale=Math.min(56/Math.max(1e-10,east-west),40/Math.max(1e-10,north-south));
 return projected.map(p=>[36+(p[0]-(west+east)/2)*scale,28-(p[1]-(south+north)/2)*scale]);
}
function thumbnail(route){const ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg');svg.classList.add('route-thumbnail');svg.setAttribute('viewBox','0 0 72 56');svg.setAttribute('role','img');svg.setAttribute('aria-label','Route preview for '+route.name);const points=routePreview(route.points);if(!points.length)return svg;const line=document.createElementNS(ns,'polyline');line.setAttribute('points',points.map(p=>p.map(n=>n.toFixed(2)).join(',')).join(' '));line.setAttribute('fill','none');line.setAttribute('stroke','#bb6b44');line.setAttribute('stroke-width','2');svg.append(line);for(const [point,color]of [[points[0],'#487358'],[points.at(-1),'#cf7648']]){const dot=document.createElementNS(ns,'circle');dot.setAttribute('cx',point[0]);dot.setAttribute('cy',point[1]);dot.setAttribute('r','2.6');dot.setAttribute('fill',color);dot.setAttribute('stroke','white');dot.setAttribute('stroke-width','1');svg.append(dot);}return svg;}
export function attachRoutes(api,{button,el,showError}){
 let routes=[],active,opened=new Set(),selected=new Map(),overlayStamp,started=false,mergeStamp;
 const list=document.getElementById('saved-list'),sidebar=document.getElementById('sidebar'),map=api.getMap(),records=new Map();
 const samePoints=(a,b)=>a===b||a?.length===b.length&&a.every((p,i)=>p[0]===b[i][0]&&p[1]===b[i][1]);
 function overlay(){
  if(!api.isReady())return;
  const passive=routes.filter(r=>opened.has(r.id)&&r.id!==active&&r.points.length>1),stamp=JSON.stringify(passive.map(r=>[r.id,records.get(r.id).version]));
  if(map.getSource('other-routes')&&stamp===overlayStamp)return;
  const data={type:'FeatureCollection',features:passive.map(r=>({type:'Feature',properties:{},geometry:{type:'LineString',coordinates:r.points}}))};
  if(!map.getSource('other-routes')){map.addSource('other-routes',{type:'geojson',data});map.addLayer({id:'other-routes-line',type:'line',source:'other-routes',paint:{'line-color':'#518999','line-width':3,'line-opacity':.9},layout:{'line-cap':'round','line-join':'round'}});}else map.getSource('other-routes').setData(data);
  overlayStamp=stamp;
 }
 function fitOpened(){const b=[Infinity,Infinity,-Infinity,-Infinity];for(const route of routes)if(opened.has(route.id))for(const p of route.points){b[0]=Math.min(b[0],p[0]);b[1]=Math.min(b[1],p[1]);b[2]=Math.max(b[2],p[0]);b[3]=Math.max(b[3],p[1]);}if(Number.isFinite(b[0]))map.fitBounds([[b[0],b[1]],[b[2],b[3]]],{padding:{top:80,left:45,right:45,bottom:60},maxZoom:14,duration:0});}
 function makeCard(record){
  const id=record.route.id,item=el('article',undefined,'saved-item'),heading=el('label',undefined,'route-selection'),check=el('input'),name=el('strong'),summary=el('p',undefined,'route-summary'),cardHeading=el('div',undefined,'route-card-heading'),copy=el('div',undefined,'route-card-copy'),actions=el('div',undefined,'saved-tools');
  check.type='checkbox';check.onchange=()=>{if(check.checked)selected.set(id,false);else selected.delete(id);render(routes,active);};heading.append(check,name);copy.append(heading,summary);cardHeading.append(copy);item.append(cardHeading,actions);
  Object.assign(record,{item,check,name,summary,cardHeading,actions,drawnVersion:0});
 }
 function updateCard(record){
  if(!record.item)makeCard(record);
  const {route,item,check,name,summary,cardHeading,actions}=record,id=route.id;
  if(name.textContent!==route.name){name.textContent=route.name;check.setAttribute('aria-label','Select '+route.name+' for combining');record.preview?.setAttribute('aria-label','Route preview for '+route.name);}
  if(record.drawnVersion!==record.version){
   const distance=routeLength(route.points);summary.textContent=`${distance>=1000?(distance/1000).toFixed(2)+' km':Math.round(distance)+' m'} · ${route.points.length} ${route.points.length===1?'waypoint':'waypoints'}`;
   const preview=thumbnail(route);if(record.preview)record.preview.replaceWith(preview);else cardHeading.prepend(preview);record.preview=preview;record.drawnVersion=record.version;
  }
  check.checked=selected.has(id);
  const actionStamp=String(active===id)+'/'+opened.has(id);
  if(record.actionStamp!==actionStamp){
   record.actionStamp=actionStamp;actions.replaceChildren();
   actions.append(button(active===id?'Editing':'Open',()=>{if(api.openRoute(id)){opened.add(id);render(api.routes(),api.activeRoute());fitOpened();}}));
   if(opened.has(id))actions.append(button('Close',()=>{if(active===id&&!api.unloadRoute())return;opened.delete(id);render(api.routes(),api.activeRoute());}));
   const rename=button('Rename',()=>{
    if(item.querySelector('.rename-route-form'))return;
    rename.hidden=true;const form=el('form',undefined,'rename-route-form'),input=el('input');input.value=record.route.name;input.maxLength=80;input.required=true;input.setAttribute('aria-label','Rename '+record.route.name);const save=el('button','Save name');save.type='submit';form.append(input,save,button('Cancel rename',()=>{form.remove();rename.hidden=false;}));
    form.onsubmit=e=>{e.preventDefault();try{if(api.renameRoute(id,input.value)){form.remove();rename.hidden=false;render(api.routes(),api.activeRoute());}}catch(error){showError(error);}};item.append(form);input.focus();input.select();
   });actions.append(rename,button('Delete',()=>{if(api.deleteRoute(id)){opened.delete(id);selected.delete(id);render(api.routes(),api.activeRoute());}}));
  }
  const orderStamp=selected.has(id)?[...selected.keys()].indexOf(id)+'/'+selected.get(id):'';
  if(record.orderStamp!==orderStamp){record.orderStamp=orderStamp;record.order?.remove();record.order=null;if(orderStamp){const order=el('div',undefined,'merge-order');order.append(el('span',`Join position ${[...selected.keys()].indexOf(id)+1}`),button(selected.get(id)?'Direction: reversed':'Direction: original',()=>{selected.set(id,!selected.get(id));render(routes,active);}));item.append(order);record.order=order;}}
 }
 let merge,empty;
 function drawList(){
  if(!sidebar.classList.contains('open'))return;
  if(!started){list.replaceChildren();started=true;}
  if(!routes.length){if(!empty){empty=el('p','No saved routes.','empty-saved');list.append(empty);}}else{empty?.remove();empty=null;}
  let cursor=list.firstElementChild;
  for(const route of routes){const record=records.get(route.id);updateCard(record);if(cursor!==record.item)list.insertBefore(record.item,cursor);cursor=record.item.nextElementSibling;}
  const stamp=JSON.stringify([...selected]);
  if(stamp!==mergeStamp){mergeStamp=stamp;merge?.remove();merge=null;if(selected.size>=2){merge=el('div',undefined,'merge-controls');merge.append(el('p','Joins follow selection order. Gaps become straight connecting lines.'),button('Combine selected routes',()=>{const entries=[...selected].map(([id,reverse])=>({...routes.find(r=>r.id===id),reverse}));const points=combineRoutes(entries);if(api.createCombined(points)){opened.clear();selected.clear();render(api.routes(),api.activeRoute());}}));list.append(merge);}}
 }
 function render(value,current){
  routes=value;active=current;const existing=new Set(routes.map(r=>r.id));opened=new Set([...opened].filter(id=>existing.has(id)));selected=new Map([...selected].filter(([id])=>existing.has(id)));if(active)opened.add(active);
  for(const [id,record]of records)if(!existing.has(id)){record.item?.remove();records.delete(id);}
  for(const route of routes){let record=records.get(route.id);if(!record){record={version:0};records.set(route.id,record);}if(!samePoints(record.points,route.points))record.version++;record.points=route.points;record.route=route;}
  overlay();drawList();return true;
 }
 new MutationObserver(drawList).observe(sidebar,{attributes:true,attributeFilter:['class']});
 map.on('style.load',()=>{overlayStamp=undefined;overlay();});
 return {render,closed(){if(active)opened.delete(active);active=null;overlay();},opened:()=>[...opened]};
}
