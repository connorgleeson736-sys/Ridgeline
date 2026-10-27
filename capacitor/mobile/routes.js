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
 let routes=[],active,opened=new Set(),selected=new Map();
 const list=document.getElementById('saved-list'),map=api.getMap();
 function overlay(){if(!api.isReady())return;const data={type:'FeatureCollection',features:routes.filter(r=>opened.has(r.id)&&r.id!==active&&r.points.length>1).map(r=>({type:'Feature',properties:{},geometry:{type:'LineString',coordinates:r.points}}))};if(!map.getSource('other-routes')){map.addSource('other-routes',{type:'geojson',data});map.addLayer({id:'other-routes-line',type:'line',source:'other-routes',paint:{'line-color':'#518999','line-width':3,'line-opacity':.9},layout:{'line-cap':'round','line-join':'round'}});}else map.getSource('other-routes').setData(data);}
 function fitOpened(){const b=[Infinity,Infinity,-Infinity,-Infinity];for(const route of routes)if(opened.has(route.id))for(const p of route.points){b[0]=Math.min(b[0],p[0]);b[1]=Math.min(b[1],p[1]);b[2]=Math.max(b[2],p[0]);b[3]=Math.max(b[3],p[1]);}if(Number.isFinite(b[0]))map.fitBounds([[b[0],b[1]],[b[2],b[3]]],{padding:{top:80,left:45,right:45,bottom:60},maxZoom:14,duration:0});}
 function render(value,current){routes=value;active=current;const existing=new Set(routes.map(r=>r.id));opened=new Set([...opened].filter(id=>existing.has(id)));selected=new Map([...selected].filter(([id])=>existing.has(id)));if(active)opened.add(active);list.replaceChildren();
  if(!routes.length)list.append(el('p','No saved routes.','empty-saved'));
  for(const route of routes){const item=el('article',undefined,'saved-item'),heading=el('label',undefined,'route-selection'),check=el('input');check.type='checkbox';check.checked=selected.has(route.id);check.setAttribute('aria-label','Select '+route.name+' for combining');check.onchange=()=>{if(check.checked)selected.set(route.id,false);else selected.delete(route.id);render(routes,active);};heading.append(check,el('strong',route.name));const cardHeading=el('div',undefined,'route-card-heading'),copy=el('div',undefined,'route-card-copy'),distance=routeLength(route.points);copy.append(heading,el('p',`${distance>=1000?(distance/1000).toFixed(2)+' km':Math.round(distance)+' m'} · ${route.points.length} ${route.points.length===1?'waypoint':'waypoints'}`,'route-summary'));cardHeading.append(thumbnail(route),copy);item.append(cardHeading);
   const actions=el('div',undefined,'saved-tools');actions.append(button(active===route.id?'Editing':'Open',()=>{if(api.openRoute(route.id)){opened.add(route.id);render(api.routes(),api.activeRoute());fitOpened();}}));
   if(opened.has(route.id))actions.append(button('Close',()=>{if(active===route.id&&!api.unloadRoute())return;opened.delete(route.id);render(api.routes(),api.activeRoute());}));
   const rename=button('Rename',()=>{rename.hidden=true;const form=el('form',undefined,'rename-route-form'),input=el('input');input.value=route.name;input.maxLength=80;input.required=true;input.setAttribute('aria-label','Rename '+route.name);const save=el('button','Save name');save.type='submit';form.append(input,save,button('Cancel rename',()=>{form.remove();rename.hidden=false;}));form.onsubmit=e=>{e.preventDefault();try{if(api.renameRoute(route.id,input.value))render(api.routes(),api.activeRoute());}catch(error){showError(error);}};item.append(form);input.focus();input.select();});actions.append(rename);
   actions.append(button('Delete',()=>{if(api.deleteRoute(route.id)){opened.delete(route.id);selected.delete(route.id);render(api.routes(),api.activeRoute());}}));item.append(actions);
   if(selected.has(route.id)){const order=el('div',undefined,'merge-order');order.append(el('span',`Join position ${[...selected.keys()].indexOf(route.id)+1}`),button(selected.get(route.id)?'Direction: reversed':'Direction: original',()=>{selected.set(route.id,!selected.get(route.id));render(routes,active);}));item.append(order);}list.append(item);
  }
  if(selected.size>=2){const box=el('div',undefined,'merge-controls');box.append(el('p','Joins follow selection order. Gaps become straight connecting lines.'),button('Combine selected routes',()=>{const entries=[...selected].map(([id,reverse])=>({...routes.find(r=>r.id===id),reverse}));const points=combineRoutes(entries);if(api.createCombined(points)){opened.clear();selected.clear();render(api.routes(),api.activeRoute());}}));list.append(box);}overlay();return true;
 }
 map.on('style.load',overlay);
 return {render,closed(){if(active)opened.delete(active);active=null;overlay();},opened:()=>[...opened]};
}
