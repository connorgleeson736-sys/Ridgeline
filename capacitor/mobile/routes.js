export function combineRoutes(routes){
 if(routes.length<2)throw Error('Select at least two routes to combine.');
 const points=[];
 for(const route of routes){const part=(route.reverse?[...route.points].reverse():route.points);for(const p of part){const last=points.at(-1);if(!last||last[0]!==p[0]||last[1]!==p[1])points.push(p.slice());}}
 if(points.length>2000)throw Error('Combined routes exceed 2,000 points. Choose fewer routes.');
 return points;
}
export function attachRoutes(api,{button,el,showError}){
 let routes=[],active,opened=new Set(),selected=new Map();
 const list=document.getElementById('saved-list'),map=api.getMap();
 function overlay(){if(!api.isReady())return;const data={type:'FeatureCollection',features:routes.filter(r=>opened.has(r.id)&&r.id!==active&&r.points.length>1).map(r=>({type:'Feature',properties:{},geometry:{type:'LineString',coordinates:r.points}}))};if(!map.getSource('other-routes')){map.addSource('other-routes',{type:'geojson',data});map.addLayer({id:'other-routes-line',type:'line',source:'other-routes',paint:{'line-color':'#518999','line-width':3,'line-opacity':.9},layout:{'line-cap':'round','line-join':'round'}});}else map.getSource('other-routes').setData(data);}
 function render(value,current){routes=value;active=current;const existing=new Set(routes.map(r=>r.id));opened=new Set([...opened].filter(id=>existing.has(id)));selected=new Map([...selected].filter(([id])=>existing.has(id)));if(active)opened.add(active);list.replaceChildren();
  if(!routes.length)list.append(el('p','No saved routes.','empty-saved'));
  for(const route of routes){const item=el('article',undefined,'saved-item'),heading=el('label',undefined,'route-selection'),check=el('input');check.type='checkbox';check.checked=selected.has(route.id);check.setAttribute('aria-label','Select '+route.name+' for combining');check.onchange=()=>{if(check.checked)selected.set(route.id,false);else selected.delete(route.id);render(routes,active);};heading.append(check,el('strong',route.name));item.append(heading);
   const actions=el('div',undefined,'saved-tools');actions.append(button(active===route.id?'Editing':'Open',()=>{if(api.openRoute(route.id)){opened.add(route.id);render(api.routes(),api.activeRoute());}}));
   if(opened.has(route.id))actions.append(button('Close',()=>{if(active===route.id&&!api.unloadRoute())return;opened.delete(route.id);render(api.routes(),api.activeRoute());}));
   actions.append(button('Delete',()=>{if(api.deleteRoute(route.id)){opened.delete(route.id);selected.delete(route.id);render(api.routes(),api.activeRoute());}}));item.append(actions);
   if(selected.has(route.id)){const order=el('div',undefined,'merge-order');order.append(el('span',`Join position ${[...selected.keys()].indexOf(route.id)+1}`),button(selected.get(route.id)?'Direction: reversed':'Direction: original',()=>{selected.set(route.id,!selected.get(route.id));render(routes,active);}));item.append(order);}list.append(item);
  }
  if(selected.size>=2){const box=el('div',undefined,'merge-controls');box.append(el('p','Joins follow selection order. Gaps become straight connecting lines.'),button('Combine selected routes',()=>{const entries=[...selected].map(([id,reverse])=>({...routes.find(r=>r.id===id),reverse}));const points=combineRoutes(entries);if(api.createCombined(points)){opened.clear();selected.clear();render(api.routes(),api.activeRoute());}}));list.append(box);}overlay();return true;
 }
 map.on('style.load',overlay);
 return {render,closed(){if(active)opened.delete(active);active=null;overlay();},opened:()=>[...opened]};
}
